package nzi.fhir.validator.core.terminology;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Asks the official registry which server handles a code system, and chooses one (spec 007 FR-016, FR-017, research
 * R9). The answer of {@code GET {registry}/resolve?fhirVersion=&url=&usage=validation} is remembered per (FHIR version,
 * system): a success for 24 hours, a failure for at most 5 minutes, so a short outage isn't kept for a day. The choice
 * is made per call, against the current allowlist and the context's own server:
 * <ol>
 *     <li>the first allowlisted {@code authoritative} server;</li>
 *     <li>among allowlisted {@code complete} candidates: the context's own server, else the default server
 *     (tx.fhir.org), else the first in registry order;</li>
 *     <li>the same among {@code fragment} candidates ({@code not-present}, {@code example} and {@code supplement} are
 *     ignored);</li>
 *     <li>the context's own server, or the default server, when allowlisted;</li>
 *     <li>none.</li>
 * </ol>
 * When the context has its own server it replaces every mention of the default server. Entries for another FHIR
 * version, or needing credentials, are skipped, and every URL is checked against the allowlist, also when the registry
 * named it (FR-010 c). Lookups are synchronous (they happen inside HAPI validation on a worker thread), go through the
 * guarded HTTP client, and are charged to the request's terminology budget.
 */
public class RegistryResolver {
    private static final Logger logger = LogManager.getLogger(RegistryResolver.class);
    static final Duration SUCCESS_TTL = Duration.ofHours(24);
    static final Duration FAILURE_TTL = Duration.ofMinutes(5);
    static final int MAX_ENTRIES = 10_000;

    /** No registry: every lookup is treated as "no routing answer", so only the fallback (step 4) applies. */
    public static final RegistryResolver DISABLED = new RegistryResolver();

    /** Lookup results, for metrics (FR-026). */
    public interface Listener {
        Listener NONE = new Listener() { };

        default void lookup(SupportedFhirVersion version, String result) { }
    }

    public static final String HIT = "hit";
    public static final String RESOLVED = "resolved";
    public static final String FAILED = "failed";

    /** A server the registry named. */
    record Candidate(String url, String content) {
    }

    /** A parsed {@code /resolve} answer; {@code failed} when the lookup failed or the answer wasn't understood. */
    record Answer(List<Candidate> authoritative, List<Candidate> candidates, boolean failed) {
        static final Answer FAILURE = new Answer(List.of(), List.of(), true);
    }

    private record Key(SupportedFhirVersion version, String system) {
    }

    private final String base;
    private final HttpClient http;
    private final Cache<Key, Answer> cache;
    private volatile Listener listener = Listener.NONE;

    private RegistryResolver() {
        this.base = null;
        this.http = null;
        this.cache = null;
    }

    /** Tests: a stub registry at {@code base}, an injectable clock. */
    RegistryResolver(String base, NetworkGuard guard, long timeoutMs, Ticker ticker) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.http = GuardedHttp.client(guard, timeoutMs, 4, 4);
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .expireAfter(new Expiry<Key, Answer>() {
                    @Override
                    public long expireAfterCreate(Key key, Answer value, long currentTime) {
                        return (value.failed() ? FAILURE_TTL : SUCCESS_TTL).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(Key key, Answer value, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(Key key, Answer value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /** The production resolver: the official registry ({@link TxRegistry#OFFICIAL_BASE}), public addresses only. */
    public static RegistryResolver official(long timeoutMs) {
        // The test JVM never contacts the real registry (research R14); this only removes lookups, never adds hosts.
        if (Boolean.getBoolean("fv.tx.registry.offline")) {
            return DISABLED;
        }
        return new RegistryResolver(TxRegistry.OFFICIAL_BASE, NetworkGuard.strict(), timeoutMs, Ticker.systemTicker());
    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? Listener.NONE : listener;
    }

    /**
     * The server for codes of {@code system}, or empty when none may be contacted.
     *
     * @param contextServer the context's own server, or {@code null}
     */
    public Optional<String> choose(SupportedFhirVersion version, String system, String contextServer,
                                   TxServerAllowlist allowlist) {
        Answer answer = system == null ? Answer.FAILURE : answer(version, system);
        Optional<String> defaultServer = allowlist.defaultServer(version);
        String replacement = contextServer == null ? null : TerminologySettings.normaliseUrl(contextServer);
        java.util.function.Function<String, String> replace = url -> replacement != null && defaultServer.isPresent()
                && defaultServer.get().equals(url) ? replacement : url;
        for (Candidate c : answer.authoritative()) {
            String url = replace.apply(c.url());
            if (allowlist.isAllowed(url, version)) {
                return Optional.of(url);
            }
        }
        for (String content : List.of("complete", "fragment")) {
            List<String> eligible = answer.candidates().stream().filter(c -> content.equals(c.content()))
                    .map(c -> replace.apply(c.url())).filter(u -> allowlist.isAllowed(u, version)).toList();
            if (replacement != null && eligible.contains(replacement)) {
                return Optional.of(replacement);
            }
            if (defaultServer.isPresent() && eligible.contains(defaultServer.get())) {
                return defaultServer;
            }
            if (!eligible.isEmpty()) {
                return Optional.of(eligible.get(0));
            }
        }
        if (replacement != null && allowlist.isAllowed(replacement, version)) {
            return Optional.of(replacement);
        }
        return defaultServer;
    }

    private Answer answer(SupportedFhirVersion version, String system) {
        if (cache == null) {
            return Answer.FAILURE;
        }
        Key key = new Key(version, system);
        Answer cached = cache.getIfPresent(key);
        if (cached != null) {
            listener.lookup(version, HIT);
            return cached;
        }
        if (TerminologyBudget.exhausted()) {
            return Answer.FAILURE;
        }
        Answer fetched = fetch(version, system);
        listener.lookup(version, fetched.failed() ? FAILED : RESOLVED);
        cache.put(key, fetched);
        return fetched;
    }

    private Answer fetch(SupportedFhirVersion version, String system) {
        String uri = base + "/resolve?fhirVersion=" + TxRegistry.registryCode(version)
                + "&url=" + URLEncoder.encode(system, StandardCharsets.UTF_8) + "&usage=validation";
        HttpGet get = new HttpGet(uri);
        get.setHeader("Accept", "application/json");
        long started = System.nanoTime();
        try {
            HttpResponse response = http.execute(get);
            String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (response.getStatusLine().getStatusCode() != 200) {
                return Answer.FAILURE;
            }
            return parse(new JsonObject(body), version);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Registry lookup for {} failed: {}", system, e.toString());
            }
            return Answer.FAILURE;
        } finally {
            TerminologyBudget.charge(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /** A {@code /resolve} answer; anything but {@code formatVersion "1"} counts as a failure (research R9). */
    static Answer parse(JsonObject body, SupportedFhirVersion version) {
        if (body == null || !"1".equals(body.getValue("formatVersion"))) {
            return Answer.FAILURE;
        }
        return new Answer(candidates(body.getValue("authoritative"), version),
                candidates(body.getValue("candidates"), version), false);
    }

    private static List<Candidate> candidates(Object value, SupportedFhirVersion version) {
        List<Candidate> out = new ArrayList<>();
        if (!(value instanceof JsonArray array)) {
            return out;
        }
        for (Object o : array) {
            if (!(o instanceof JsonObject c) || !(c.getValue("url") instanceof String url)) {
                continue;
            }
            if (!"open".equals(c.getValue("security"))) {
                continue;
            }
            Object fhir = c.getValue("fhirVersion");
            if (!(fhir instanceof String f) || SupportedFhirVersion.fromDeclaredVersion(f).orElse(null) != version) {
                continue;
            }
            try {
                out.add(new Candidate(TerminologySettings.normaliseUrl(url), c.getString("content")));
            } catch (RuntimeException e) {
                // not an http(s) base URL: skipped
            }
        }
        return out;
    }
}
