package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Bounded wait for this instance to apply a committed configuration change. An administrative answer must not be
 * held by a long validator rebuild until the request times out (FR-020): after {@code waitMs} it succeeds anyway and
 * the caller reports the change as committed but still being applied.
 */
final class LocalApplyWait {
    private LocalApplyWait() {
    }

    /**
     * @param onTimeout runs (before the returned future completes) when {@code waitMs} passes first
     * @return {@code applied}'s outcome, or success once {@code waitMs} has passed
     */
    static Future<Void> await(Vertx vertx, Future<Void> applied, long waitMs, Runnable onTimeout) {
        if (applied.isComplete()) {
            return applied;
        }
        Promise<Void> answer = Promise.promise();
        long timer = vertx.setTimer(waitMs, id -> {
            if (!answer.future().isComplete()) {
                // Run the callback first: completing the promise renders the response synchronously.
                onTimeout.run();
                answer.tryComplete();
            }
        });
        applied.onComplete(ar -> {
            vertx.cancelTimer(timer);
            if (ar.succeeded()) {
                answer.tryComplete();
            } else {
                answer.tryFail(ar.cause());
            }
        });
        return answer.future();
    }
}
