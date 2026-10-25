package nzi.fhir.validator.core.npm;

import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1 regression: package downloads cannot be pointed at arbitrary or internal destinations.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
class SafePackageFetcherTest {
    private static final long ONE_MB = 1024 * 1024;

    private WorkerExecutor pool;
    private HttpServer server;
    private final AtomicInteger resolutions = new AtomicInteger();

    @BeforeEach
    void start(Vertx vertx, VertxTestContext ctx) {
        pool = vertx.createSharedWorkerExecutor("fetcher-test", 2);
        server = vertx.createHttpServer().requestHandler(req -> {
            switch (req.path()) {
                case "/ok" -> req.response().end("package-bytes");
                case "/redirect-evil" -> req.response().setStatusCode(302).putHeader("Location", "http://evil.example/x").end();
                case "/redirect-ok" -> req.response().setStatusCode(302).putHeader("Location", "/ok").end();
                case "/big" -> req.response().end(Buffer.buffer(new byte[(int) (2 * ONE_MB)]));
                case "/big-chunked" -> {
                    req.response().setChunked(true);
                    for (int i = 0; i < 4; i++) {
                        req.response().write(Buffer.buffer(new byte[(int) (ONE_MB / 2 + 1)]));
                    }
                    req.response().end();
                }
                default -> req.response().setStatusCode(404).end();
            }
        });
        server.listen(0).onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void stop(VertxTestContext ctx) {
        server.close().onComplete(ar -> ctx.completeNow());
    }

    private SafePackageFetcher secure(Vertx vertx, String... resolvesTo) {
        return new SafePackageFetcher(vertx, pool, List.of("packages.fhir.org"), 5_000, ONE_MB, false, host -> {
            resolutions.incrementAndGet();
            return java.util.Arrays.stream(resolvesTo).map(ip -> {
                try {
                    return InetAddress.getByName(ip);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        });
    }

    private SafePackageFetcher localInsecure(Vertx vertx) {
        return new SafePackageFetcher(vertx, pool, List.of("localhost"), 5_000, ONE_MB, true,
                host -> List.of(InetAddress.getLoopbackAddress()));
    }

    private static void assertProblem(Throwable err, int status, String type) {
        assertInstanceOf(ProblemException.class, err, String.valueOf(err));
        assertEquals(status, ((ProblemException) err).status());
        assertEquals(type, ((ProblemException) err).type());
    }

    @Test
    void plainHttpIsRefusedWithoutResolving(Vertx vertx, VertxTestContext ctx) {
        secure(vertx, "93.184.216.34").fetch("http://packages.fhir.org/x").onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertProblem(err, 400, "package-source-not-allowed");
            assertEquals(0, resolutions.get());
            ctx.completeNow();
        })));
    }

    @Test
    void hostOffTheAllowlistIsRefusedWithoutResolving(Vertx vertx, VertxTestContext ctx) {
        secure(vertx, "93.184.216.34").fetch("https://evil.example/x").onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertProblem(err, 400, "package-source-not-allowed");
            assertEquals(0, resolutions.get());
            ctx.completeNow();
        })));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "192.168.1.10", "169.254.169.254", "::1", "fc00::1", "100.64.0.1", "0.0.0.0"})
    void allowedHostResolvingToNonPublicAddressIsRefused(String address, Vertx vertx, VertxTestContext ctx) {
        secure(vertx, "93.184.216.34", address).fetch("https://packages.fhir.org/x").onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertProblem(err, 400, "package-source-not-allowed");
            ctx.completeNow();
        })));
    }

    @Test
    void redirectToDisallowedHostIsRefused(Vertx vertx, VertxTestContext ctx) {
        localInsecure(vertx).fetch("http://localhost:" + server.actualPort() + "/redirect-evil")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 400, "package-source-not-allowed");
                    ctx.completeNow();
                })));
    }

    @Test
    void redirectWithinAllowedHostIsFollowed(Vertx vertx, VertxTestContext ctx) {
        localInsecure(vertx).fetch("http://localhost:" + server.actualPort() + "/redirect-ok")
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertEquals("package-bytes", body.toString());
                    ctx.completeNow();
                })));
    }

    @Test
    void oversizedResponseIsRejected(Vertx vertx, VertxTestContext ctx) {
        localInsecure(vertx).fetch("http://localhost:" + server.actualPort() + "/big")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 413, "package-too-large");
                    ctx.completeNow();
                })));
    }

    @Test
    void oversizedChunkedResponseIsAbortedWhileStreaming(Vertx vertx, VertxTestContext ctx) {
        localInsecure(vertx).fetch("http://localhost:" + server.actualPort() + "/big-chunked")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 413, "package-too-large");
                    ctx.completeNow();
                })));
    }

    @Test
    void missingFileIs404(Vertx vertx, VertxTestContext ctx) {
        localInsecure(vertx).fetch("http://localhost:" + server.actualPort() + "/nope")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 404, "package-not-found");
                    ctx.completeNow();
                })));
    }
}
