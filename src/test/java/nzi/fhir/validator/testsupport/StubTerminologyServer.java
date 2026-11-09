package nzi.fhir.validator.testsupport;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.UriType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process FHIR R4 terminology server for tests (spec 007, research R14). It answers {@code $validate-code} on
 * {@code CodeSystem} and {@code ValueSet}, and {@code CodeSystem/$lookup}, from a table of known codes
 * ({@link #code}); any other code is invalid. Every request is counted and its body kept (parsed as FHIR
 * {@code Parameters}), so tests can check the {@code valueSet} and {@code tx-resource} parameters. It can be switched
 * to delay, drop connections, answer 500, or redirect.
 */
public final class StubTerminologyServer {
    public enum Mode { NORMAL, DELAY, RESET, ERROR, REDIRECT }

    private static final FhirContext R4 = FhirContext.forR4Cached();

    private final String basePath;
    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger metadataHits = new AtomicInteger();
    private final List<Parameters> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> rawBodies = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.NORMAL;
    private volatile long delayMs;
    private volatile String redirectTo;
    private HttpServer server;

    /** @param basePath the path the server lives under, for example {@code /r4} */
    public StubTerminologyServer(String basePath) {
        this.basePath = basePath;
    }

    /** Declares {@code system|code} valid with {@code display}. */
    public StubTerminologyServer code(String system, String code, String display) {
        codes.put(system + "|" + code, display);
        return this;
    }

    public StubTerminologyServer normal() {
        this.mode = Mode.NORMAL;
        return this;
    }

    public StubTerminologyServer delay(long ms) {
        this.mode = Mode.DELAY;
        this.delayMs = ms;
        return this;
    }

    public StubTerminologyServer reset() {
        this.mode = Mode.RESET;
        return this;
    }

    public StubTerminologyServer error() {
        this.mode = Mode.ERROR;
        return this;
    }

    public StubTerminologyServer redirectTo(String location) {
        this.mode = Mode.REDIRECT;
        this.redirectTo = location;
        return this;
    }

    public Future<StubTerminologyServer> start(Vertx vertx) {
        server = vertx.createHttpServer().requestHandler(req -> req.body().onSuccess(body -> {
            if (req.path().endsWith("/metadata")) {
                metadataHits.incrementAndGet();
            }
            hits.incrementAndGet();
            paths.add(req.path());
            rawBodies.add(req.getHeader("Content-Type") + " " + body);
            Parameters in = parse(req, body.toString());
            if (in != null) {
                requests.add(in);
            }
            switch (mode) {
                case RESET -> req.connection().close();
                case ERROR -> req.response().setStatusCode(500).end();
                case REDIRECT -> req.response().setStatusCode(302).putHeader("Location", redirectTo + req.path()
                        .substring(basePath.length())).end();
                case DELAY -> vertx.setTimer(Math.max(1, delayMs), t -> answer(req, in));
                default -> answer(req, in);
            }
        }));
        return server.listen(0, "127.0.0.1").map(s -> this);
    }

    private static Parameters parse(HttpServerRequest req, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String type = String.valueOf(req.getHeader("Content-Type"));
        try {
            return type.contains("xml")
                    ? R4.newXmlParser().parseResource(Parameters.class, body)
                    : R4.newJsonParser().parseResource(Parameters.class, body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void answer(HttpServerRequest req, Parameters in) {
        if (req.response().ended() || req.response().closed()) {
            return;
        }
        String path = req.path();
        if (!path.startsWith(basePath)) {
            req.response().setStatusCode(404).end();
            return;
        }
        String system = null;
        String code = null;
        if (in != null) {
            for (Parameters.ParametersParameterComponent p : in.getParameter()) {
                Type v = p.getValue();
                switch (p.getName()) {
                    case "system" -> {
                        if (v instanceof UriType u) {
                            system = u.getValue();
                        }
                    }
                    case "url" -> {
                        // On CodeSystem operations, url names the code system.
                        if (system == null && path.contains("/CodeSystem/") && v instanceof UriType u) {
                            system = u.getValue();
                        }
                    }
                    case "code" -> code = v == null ? null : v.primitiveValue();
                    case "coding" -> {
                        if (v instanceof Coding c) {
                            system = c.getSystem();
                            code = c.getCode();
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        String display = system == null || code == null ? null : codes.get(system + "|" + code);
        Parameters out = new Parameters();
        if (path.endsWith("$lookup")) {
            if (display == null) {
                req.response().setStatusCode(404).end();
                return;
            }
            out.addParameter().setName("name").setValue(new StringType(system));
            out.addParameter().setName("display").setValue(new StringType(display));
        } else {
            out.addParameter().setName("result").setValue(new BooleanType(display != null));
            if (display != null) {
                out.addParameter().setName("display").setValue(new StringType(display));
            } else {
                out.addParameter().setName("message").setValue(new StringType(
                        "Unknown code '" + code + "' in the CodeSystem '" + system + "'"));
            }
        }
        req.response().putHeader("Content-Type", "application/fhir+json").end(R4.newJsonParser().encodeResourceToString(out));
    }

    /** The server's FHIR base, for example {@code http://127.0.0.1:1234/r4}. */
    public String base() {
        return "http://127.0.0.1:" + server.actualPort() + basePath;
    }

    public int hits() {
        return hits.get();
    }

    public int metadataHits() {
        return metadataHits.get();
    }

    /** Request bodies received, parsed as {@code Parameters}, in arrival order. */
    public List<Parameters> requests() {
        return List.copyOf(requests);
    }

    /** {@code Content-Type} and raw body of every request (diagnostics). */
    public List<String> rawBodies() {
        return List.copyOf(rawBodies);
    }

    public List<String> paths() {
        return List.copyOf(paths);
    }

    public void clear() {
        hits.set(0);
        metadataHits.set(0);
        requests.clear();
        paths.clear();
        rawBodies.clear();
    }

    public Future<Void> stop() {
        return server == null ? Future.succeededFuture() : server.close();
    }
}
