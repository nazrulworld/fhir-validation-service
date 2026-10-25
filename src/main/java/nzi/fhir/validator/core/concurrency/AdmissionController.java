package nzi.fhir.validator.core.concurrency;

import io.vertx.core.Future;
import nzi.fhir.validator.web.ProblemException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounds validation work (running plus queued) per instance ({@code FV_VALIDATION_MAX_PENDING}). Beyond the bound,
 * requests are refused at once with 503 and {@code Retry-After} instead of queueing without limit (FR-010).
 */
public class AdmissionController {
    private final int maxPending;
    private final AtomicInteger pending = new AtomicInteger();

    public AdmissionController(int maxPending) {
        this.maxPending = maxPending;
    }

    public <T> Future<T> admit(Supplier<Future<T>> work) {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet();
            return Future.failedFuture(new ProblemException(503, "overloaded",
                    "Too many validations in progress; retry shortly").withHeader("Retry-After", "1"));
        }
        Future<T> future;
        try {
            future = work.get();
        } catch (RuntimeException e) {
            pending.decrementAndGet();
            return Future.failedFuture(e);
        }
        return future.onComplete(ar -> pending.decrementAndGet());
    }

    public int pending() {
        return pending.get();
    }

    public int maxPending() {
        return maxPending;
    }
}
