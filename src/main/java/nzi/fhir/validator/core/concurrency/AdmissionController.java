package nzi.fhir.validator.core.concurrency;

import io.vertx.core.Future;
import nzi.fhir.validator.web.ProblemException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounds work (running plus queued) per instance: validations ({@code FV_VALIDATION_MAX_PENDING}) and, separately,
 * administrative operations ({@code FV_ADMIN_MAX_PENDING}). Beyond the bound, requests are refused at once with 503
 * {@code overloaded} and {@code Retry-After} instead of queueing without limit (FR-010).
 */
public class AdmissionController {
    private final int maxPending;
    private final String refusal;
    private final AtomicInteger pending = new AtomicInteger();

    public AdmissionController(int maxPending) {
        this(maxPending, "Too many validations in progress; retry shortly");
    }

    /**
     * @param refusal curated {@code detail} of the 503 problem
     */
    public AdmissionController(int maxPending, String refusal) {
        this.maxPending = maxPending;
        this.refusal = refusal;
    }

    public <T> Future<T> admit(Supplier<Future<T>> work) {
        if (!tryAcquire()) {
            return Future.failedFuture(overloaded());
        }
        Future<T> future;
        try {
            future = work.get();
        } catch (RuntimeException e) {
            release();
            return Future.failedFuture(e);
        }
        return future.onComplete(ar -> release());
    }

    /** Takes a slot if one is free; every successful call must be paired with one {@link #release()}. */
    public boolean tryAcquire() {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet();
            return false;
        }
        return true;
    }

    public void release() {
        pending.decrementAndGet();
    }

    public ProblemException overloaded() {
        return new ProblemException(503, "overloaded", refusal).withHeader("Retry-After", "1");
    }

    public int pending() {
        return pending.get();
    }

    public int maxPending() {
        return maxPending;
    }
}
