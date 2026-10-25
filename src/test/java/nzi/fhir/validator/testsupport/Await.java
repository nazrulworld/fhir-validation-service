package nzi.fhir.validator.testsupport;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.function.Supplier;

/**
 * Asynchronous polling for eventually-consistent assertions.
 */
public final class Await {

    private Await() {
    }

    /**
     * Re-evaluates {@code condition} every 100 ms until it yields {@code true}; fails after {@code timeoutMs}.
     *
     * @return the elapsed time in milliseconds
     */
    public static Future<Long> until(Vertx vertx, long timeoutMs, String description, Supplier<Future<Boolean>> condition) {
        long start = System.currentTimeMillis();
        return attempt(vertx, start, start + timeoutMs, description, condition);
    }

    private static Future<Long> attempt(Vertx vertx, long start, long deadline, String description, Supplier<Future<Boolean>> condition) {
        return condition.get().recover(err -> Future.succeededFuture(false)).compose(ok -> {
            if (ok) {
                return Future.succeededFuture(System.currentTimeMillis() - start);
            }
            if (System.currentTimeMillis() > deadline) {
                return Future.failedFuture(new AssertionError("Timed out waiting for: " + description));
            }
            return vertx.timer(100).compose(v -> attempt(vertx, start, deadline, description, condition));
        });
    }
}
