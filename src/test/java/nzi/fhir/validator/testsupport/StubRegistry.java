package nzi.fhir.validator.testsupport;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process NPM-style package registry for tests: {@code GET /{name}} answers a listing ({@code versions} plus
 * {@code dist-tags.latest}), {@code GET /{name}/{version}} the archive. It can be switched to answer 503 or to drop
 * connections, and counts every request and every archive download.
 */
public final class StubRegistry {
    public enum Mode { NORMAL, UNAVAILABLE, DROP }

    private final Map<String, Map<String, byte[]>> packages = new LinkedHashMap<>();
    private final Map<String, String> latestTags = new LinkedHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger archiveDownloads = new AtomicInteger();
    private final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.NORMAL;
    private HttpServer server;

    public synchronized StubRegistry add(String name, String version, byte[] archive) {
        packages.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(version, archive);
        return this;
    }

    /** Overrides {@code dist-tags.latest} (by default the last version added). */
    public synchronized StubRegistry latestTag(String name, String version) {
        latestTags.put(name, version);
        return this;
    }

    public StubRegistry mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Future<StubRegistry> start(Vertx vertx) {
        server = vertx.createHttpServer().requestHandler(req -> {
            requests.incrementAndGet();
            paths.add(req.path());
            if (mode == Mode.DROP) {
                req.connection().close();
                return;
            }
            if (mode == Mode.UNAVAILABLE) {
                req.response().setStatusCode(503).end();
                return;
            }
            String[] parts = req.path().replaceFirst("^/", "").split("/");
            synchronized (this) {
                Map<String, byte[]> versions = packages.get(parts[0]);
                if (versions == null) {
                    req.response().setStatusCode(404).end();
                } else if (parts.length == 1) {
                    JsonObject listing = new JsonObject();
                    versions.keySet().forEach(v -> listing.put(v, new JsonObject()));
                    String latest = latestTags.getOrDefault(parts[0], versions.keySet().stream().reduce((a, b) -> b).orElse(null));
                    req.response().putHeader("Content-Type", "application/json")
                            .end(new JsonObject().put("name", parts[0]).put("versions", listing)
                                    .put("dist-tags", new JsonObject().put("latest", latest)).encode());
                } else if (versions.containsKey(parts[1])) {
                    archiveDownloads.incrementAndGet();
                    req.response().putHeader("Content-Type", "application/gzip").end(Buffer.buffer(versions.get(parts[1])));
                } else {
                    req.response().setStatusCode(404).end();
                }
            }
        });
        return server.listen(0, "127.0.0.1").map(s -> this);
    }

    /** Base URL to use in {@code packages.sources}. */
    public String url() {
        return "http://127.0.0.1:" + server.actualPort();
    }

    public int requests() {
        return requests.get();
    }

    public int archiveDownloads() {
        return archiveDownloads.get();
    }

    /** Every request path received, in arrival order (for example {@code /dep/1.0.3}). */
    public java.util.List<String> paths() {
        return java.util.List.copyOf(paths);
    }

    public Future<Void> stop() {
        return server == null ? Future.succeededFuture() : server.close();
    }
}
