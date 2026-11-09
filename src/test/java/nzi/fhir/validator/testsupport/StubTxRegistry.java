package nzi.fhir.validator.testsupport;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process copy of the HL7 terminology registry for tests (spec 007, research R14): {@code GET /tx-reg/?fhirVersion=}
 * answers a discovery fixture and {@code GET /tx-reg/resolve?fhirVersion=&url=} a resolve fixture, both from
 * {@code src/test/resources/tx-reg/}. Fixture text is rewritten before it is served: {@code {{BASE}}} and
 * {@code {{BASE2}}} become the configured stub server bases, and every {@link #replace} rule is applied (for example
 * {@code http://tx.fhir.org/r4} → a stub terminology server), so recorded answers can point at local servers.
 */
public final class StubTxRegistry {
    public enum Mode { NORMAL, UNAVAILABLE, HANG }

    private final Map<String, String> discovery = new ConcurrentHashMap<>();
    private final Map<String, String> resolve = new ConcurrentHashMap<>();
    private final Map<String, String> replacements = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final List<String> accepts = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.NORMAL;
    private HttpServer server;

    public StubTxRegistry() {
        for (String v : List.of("R3", "R4", "R4B", "R5")) {
            discovery.put(v, "discovery-" + v + ".json");
        }
    }

    /** Serves {@code fixture} (a file name under {@code tx-reg/}) as the discovery answer for {@code fhirVersion}. */
    public StubTxRegistry discovery(String fhirVersion, String fixture) {
        discovery.put(fhirVersion, fixture);
        return this;
    }

    /** Serves {@code fixture} as the resolve answer for {@code fhirVersion} and code system {@code system}. */
    public StubTxRegistry resolve(String fhirVersion, String system, String fixture) {
        resolve.put(fhirVersion + "|" + system, fixture);
        return this;
    }

    /** Rewrites {@code from} to {@code to} in every served fixture ({@code {{BASE}}} placeholders included). */
    public StubTxRegistry replace(String from, String to) {
        replacements.put(from, to);
        return this;
    }

    public StubTxRegistry mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Future<StubTxRegistry> start(Vertx vertx) {
        server = vertx.createHttpServer().requestHandler(this::handle);
        return server.listen(0, "127.0.0.1").map(s -> this);
    }

    private void handle(HttpServerRequest req) {
        String path = req.path();
        hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        accepts.add(String.valueOf(req.getHeader("Accept")));
        queries.add(String.valueOf(req.query()));
        if (mode == Mode.HANG) {
            return;
        }
        if (mode == Mode.UNAVAILABLE) {
            req.response().setStatusCode(503).end();
            return;
        }
        String accept = req.getHeader("Accept");
        if (accept == null || !accept.contains("application/json")) {
            req.response().putHeader("Content-Type", "text/html").end("<html><body>registry</body></html>");
            return;
        }
        String fhirVersion = req.getParam("fhirVersion");
        String fixture;
        if (path.equals("/tx-reg/") || path.equals("/tx-reg")) {
            fixture = discovery.get(fhirVersion);
        } else if (path.equals("/tx-reg/resolve")) {
            fixture = resolve.get(fhirVersion + "|" + req.getParam("url"));
            if (fixture == null) {
                req.response().putHeader("Content-Type", "application/json")
                        .end("{\"formatVersion\":\"1\",\"registry-url\":\"https://example.org/tx-servers.json\",\"candidates\":[]}");
                return;
            }
        } else {
            req.response().setStatusCode(404).end();
            return;
        }
        if (fixture == null) {
            req.response().setStatusCode(400).end();
            return;
        }
        req.response().putHeader("Content-Type", "application/json").end(render(fixture));
    }

    private String render(String fixture) {
        String text = read(fixture);
        for (Map.Entry<String, String> r : replacements.entrySet()) {
            text = text.replace(r.getKey(), r.getValue());
        }
        return text;
    }

    /** The fixture text, unchanged. */
    public static String read(String fixture) {
        try (InputStream in = StubTxRegistry.class.getResourceAsStream("/tx-reg/" + fixture)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture tx-reg/" + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The registry base to give the service, e.g. {@code http://127.0.0.1:1234/tx-reg}. */
    public String base() {
        return "http://127.0.0.1:" + server.actualPort() + "/tx-reg";
    }

    public int discoveryHits() {
        return hits.getOrDefault("/tx-reg/", new AtomicInteger()).get() + hits.getOrDefault("/tx-reg", new AtomicInteger()).get();
    }

    public int resolveHits() {
        return hits.getOrDefault("/tx-reg/resolve", new AtomicInteger()).get();
    }

    /** {@code Accept} headers received, in arrival order. */
    public List<String> accepts() {
        return List.copyOf(accepts);
    }

    /** Raw query strings received, in arrival order. */
    public List<String> queries() {
        return List.copyOf(queries);
    }

    public Future<Void> stop() {
        return server == null ? Future.succeededFuture() : server.close();
    }
}
