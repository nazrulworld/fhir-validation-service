package nzi.fhir.validator.core.concurrency;

import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Supplier;

/**
 * Runs at most {@code permits} asynchronous tasks at a time; the rest wait in FIFO order. Unlike
 * {@link AdmissionController} nothing is refused: it is for background work that must eventually run, such as
 * validator builds, which may only take part of a worker pool.
 */
public final class AsyncLimiter {
    private final int permits;
    private final Queue<Runnable> waiting = new ArrayDeque<>();
    private int running;

    public AsyncLimiter(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.permits = permits;
    }

    public <T> Future<T> run(Supplier<Future<T>> task) {
        Promise<T> result = Promise.promise();
        Runnable start = () -> {
            Future<T> future;
            try {
                future = task.get();
            } catch (RuntimeException e) {
                future = Future.failedFuture(e);
            }
            future.onComplete(ar -> {
                release();
                result.handle(ar);
            });
        };
        boolean now;
        synchronized (this) {
            now = running < permits;
            if (now) {
                running++;
            } else {
                waiting.add(start);
            }
        }
        if (now) {
            start.run();
        }
        return result.future();
    }

    private void release() {
        Runnable next;
        synchronized (this) {
            next = waiting.poll();
            if (next == null) {
                running--;
            }
        }
        if (next != null) {
            next.run();
        }
    }

    public int permits() {
        return permits;
    }
}
