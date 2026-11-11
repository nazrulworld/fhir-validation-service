package nzi.fhir.validator;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.StartupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review regression (spec 008 research R14): a fatal warm-up error exits with status 1 from a non-Vert.x thread, so the
 * exit can't hang on closing Vert.x from one of its own callbacks.
 */
@ExtendWith(VertxExtension.class)
class BootstrapFatalExitTest {

    @Test
    void aStartupExceptionExitsWithOneOffTheEventLoop(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger status = new AtomicInteger(-1);
        AtomicBoolean onVertxThread = new AtomicBoolean(true);
        vertx.runOnContext(v -> {
            Thread t = Bootstrap.exitOnFatal(new StartupException("clash"), code -> {
                status.set(code);
                onVertxThread.set(Context.isOnVertxThread());
            });
            ctx.verify(() -> {
                assertNotNull(t);
                t.join(5_000);
                assertEquals(1, status.get());
                assertFalse(onVertxThread.get(), "the exit runs on its own thread");
                assertTrue(Bootstrap.FATAL.get(), "code review: the shutdown hook skips the graceful drain");
                Bootstrap.FATAL.set(false);
                assertNull(Bootstrap.exitOnFatal(new IllegalStateException("not fatal"), code -> fail("no exit")));
            });
            ctx.completeNow();
        });
    }

    /** The start-failure path of {@code main} exits the same way: off the event loop, with status 1. */
    @Test
    void exitFromOwnThreadNeverRunsOnTheEventLoop(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger status = new AtomicInteger(-1);
        AtomicBoolean onVertxThread = new AtomicBoolean(true);
        vertx.runOnContext(v -> {
            Thread t = Bootstrap.exitFromOwnThread(code -> {
                status.set(code);
                onVertxThread.set(Context.isOnVertxThread());
            }, 1);
            ctx.verify(() -> {
                t.join(5_000);
                assertEquals(1, status.get());
                assertFalse(onVertxThread.get());
            });
            ctx.completeNow();
        });
    }
}
