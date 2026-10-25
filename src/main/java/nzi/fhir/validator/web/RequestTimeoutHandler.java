package nzi.fhir.validator.web;

import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;

/**
 * Answers 503 when a request has not completed within {@code FV_HTTP_REQUEST_TIMEOUT_MS} (FR-020). A late result is
 * discarded: {@link ErrorResponder} never writes a response twice.
 */
public class RequestTimeoutHandler implements PlatformHandler {
    private final long timeoutMs;

    public RequestTimeoutHandler(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void handle(RoutingContext ctx) {
        long timer = ctx.vertx().setTimer(timeoutMs, id -> {
            if (!ctx.response().ended() && !ctx.response().closed()) {
                ErrorResponder.fail(ctx, new ProblemException(503, "not-ready",
                        "The request did not complete within " + timeoutMs + " ms; retry later").withHeader("Retry-After", "5"));
            }
        });
        ctx.addEndHandler(v -> ctx.vertx().cancelTimer(timer));
        ctx.next();
    }
}
