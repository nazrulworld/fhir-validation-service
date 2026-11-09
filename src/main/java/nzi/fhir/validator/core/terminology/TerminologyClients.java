package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.apache.ApacheRestfulClientFactory;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The process-wide pool of terminology server clients (spec 007 research R11). One HAPI client per (server, FHIR
 * version), shared by every snapshot. Every call goes through {@link #call}, which enforces, in order:
 * <ol>
 *     <li>the allowlist, immediately before the call (FR-010 c);</li>
 *     <li>the server's circuit breaker (FR-019);</li>
 *     <li>the request's terminology time budget (FR-019a).</li>
 * </ol>
 * Each client has its own HTTP client: redirects are never followed, every address is checked by the
 * {@link NetworkGuard} DNS resolver (FR-011), no {@code /metadata} call is made, and connect and read timeouts are
 * {@code min(FV_TX_TIMEOUT_MS, budget left)} per request. A failed call is answered with a "not checked" warning,
 * never an error, and marks the request degraded (research R6, R7).
 */
public class TerminologyClients {
    private static final Logger logger = LogManager.getLogger(TerminologyClients.class);

    /** No clients: every call is refused as not permitted. */
    public static final TerminologyClients NONE = new TerminologyClients(TxServerAllowlistSource.EMPTY, NetworkGuard.strict(),
            1_000, 1);

    /** Outcomes of calls, for metrics (FR-026); must be quick and never throw. */
    public interface Listener {
        Listener NONE = new Listener() { };

        default void call(String server, SupportedFhirVersion version, String outcome, long elapsedNanos) { }
    }

    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    public static final String TIMEOUT = "timeout";
    public static final String REFUSED = "refused";
    public static final String CIRCUIT_OPEN = "circuit_open";
    public static final String BUDGET_EXHAUSTED = "budget_exhausted";

    private record Key(String url, SupportedFhirVersion version) {
    }

    private final TxServerAllowlistSource allowlist;
    private final NetworkGuard guard;
    private final long timeoutMs;
    private final int maxConnections;
    private final Map<Key, IgAwareRemoteTerminologySupport> clients = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<FhirContext, ApacheRestfulClientFactory> factories = new java.util.IdentityHashMap<>();
    private HttpClient http;
    private volatile Listener listener = Listener.NONE;

    /**
     * @param guard          {@link NetworkGuard#strict()} in production
     * @param timeoutMs      connect and read timeout of one call ({@code FV_TX_TIMEOUT_MS})
     * @param maxConnections pooled connections per server ({@code FV_TX_MAX_CONNECTIONS_PER_SERVER})
     */
    public TerminologyClients(TxServerAllowlistSource allowlist, NetworkGuard guard, long timeoutMs, int maxConnections) {
        this.allowlist = allowlist;
        this.guard = guard;
        this.timeoutMs = timeoutMs;
        this.maxConnections = maxConnections;
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    public TxServerAllowlistSource allowlist() {
        return allowlist;
    }

    /** The pooled client of {@code url} for {@code version}. */
    IgAwareRemoteTerminologySupport client(FhirContext ctx, SupportedFhirVersion version, String url) {
        String normalised = TerminologySettings.normaliseUrl(url);
        return clients.computeIfAbsent(new Key(normalised, version), k -> newClient(ctx, normalised));
    }

    /** The guarded HTTP client shared by every server (research R11). */
    HttpClient buildHttpClient() {
        return GuardedHttp.client(guard, timeoutMs, maxConnections, maxConnections * 50);
    }

    private IgAwareRemoteTerminologySupport newClient(FhirContext ctx, String url) {
        return new IgAwareRemoteTerminologySupport(ctx, url, factoryFor(ctx));
    }

    /**
     * The guarded client factory of {@code ctx}, installed as the context's restful client factory. HAPI builds every
     * request of a generic client through {@code FhirContext.getRestfulClientFactory()}, not through the factory the
     * client was created with, so only installing it there makes every terminology request use the guarded HTTP
     * client (verified by TerminologyFailureIT). The service makes no other HAPI client calls: packages are fetched
     * by {@code SafePackageFetcher} and profiles never leave the process.
     */
    private synchronized ApacheRestfulClientFactory factoryFor(FhirContext ctx) {
        return factories.computeIfAbsent(ctx, c -> {
            ApacheRestfulClientFactory factory = new ApacheRestfulClientFactory(c) {
                // Always this guarded client: HAPI otherwise rebuilds its own native client whenever a timeout or pool
                // setting changes, which would drop the redirect, address and budget rules.
                @Override
                public synchronized HttpClient getNativeHttpClient() {
                    return http();
                }
            };
            factory.setServerValidationMode(ServerValidationModeEnum.NEVER);
            c.setRestfulClientFactory(factory);
            return factory;
        });
    }

    private synchronized HttpClient http() {
        if (http == null) {
            http = buildHttpClient();
        }
        return http;
    }

    /**
     * Runs one terminology call to {@code url}, or answers {@code degraded} without calling.
     *
     * @param work     the remote call
     * @param degraded the "not checked" answer, given the curated reason text
     */
    public <T> T call(String url, SupportedFhirVersion version, Supplier<T> work,
                      java.util.function.Function<String, T> degraded) {
        String server = server(url);
        if (!allowlist.current().isAllowed(url, version)) {
            listener.call(server, version, REFUSED, 0);
            TerminologyBudget.markDegraded();
            return degraded.apply(Reasons.NOT_PERMITTED);
        }
        CircuitBreaker breaker = breakers.computeIfAbsent(TerminologySettings.normaliseUrl(url), u -> new CircuitBreaker());
        if (!breaker.allow()) {
            listener.call(server, version, CIRCUIT_OPEN, 0);
            TerminologyBudget.markDegraded();
            return degraded.apply(Reasons.UNAVAILABLE);
        }
        if (TerminologyBudget.exhausted()) {
            listener.call(server, version, BUDGET_EXHAUSTED, 0);
            TerminologyBudget.markBudgetExhausted();
            return degraded.apply(Reasons.BUDGET);
        }
        long started = System.nanoTime();
        try {
            T result = work.get();
            breaker.onSuccess();
            listener.call(server, version, SUCCESS, System.nanoTime() - started);
            return result;
        } catch (RuntimeException e) {
            breaker.onFailure();
            boolean timeout = hasCause(e, SocketTimeoutException.class) || hasCause(e, org.apache.http.conn.ConnectTimeoutException.class);
            listener.call(server, version, timeout ? TIMEOUT : FAILURE, System.nanoTime() - started);
            if (logger.isDebugEnabled()) {
                logger.debug("Terminology call to {} failed: {}", server, e.toString());
            }
            TerminologyBudget.markDegraded();
            return degraded.apply(TerminologyBudget.exhausted() ? Reasons.BUDGET : Reasons.UNAVAILABLE);
        } finally {
            TerminologyBudget.charge(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /** Whether the circuit of {@code url} is open (tests, status). */
    public boolean circuitOpen(String url) {
        CircuitBreaker b = breakers.get(TerminologySettings.normaliseUrl(url));
        return b != null && b.isOpen();
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    /** A metrics label for a server: its host, never a path or query (FR-026). */
    static String server(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    /** The degraded answer for a code check (research R7): a warning with a fixed text, never an error. */
    public static IValidationSupport.CodeValidationResult notChecked(String reason, String system, String code) {
        return new IValidationSupport.CodeValidationResult()
                .setSeverity(IValidationSupport.IssueSeverity.WARNING)
                .setMessage(Reasons.text(reason, system, code))
                .setSourceDetails("fhir-validation-service terminology");
    }

    /** The fixed reason texts of a "not checked" answer (research R7). */
    public static final class Reasons {
        public static final String UNAVAILABLE = "unavailable";
        public static final String BUDGET = "budget";
        public static final String NOT_PERMITTED = "not-permitted";

        private Reasons() {
        }

        public static String text(String reason, String system, String code) {
            String sys = system == null ? "(no system)" : system;
            return switch (reason) {
                case BUDGET -> "Terminology time budget for this request was used up; code " + sys + "#" + code + " was not checked";
                case NOT_PERMITTED -> "No permitted terminology server for " + sys + "; code was not checked";
                default -> "Terminology server unavailable; code " + sys + "#" + code + " was not checked";
            };
        }
    }
}
