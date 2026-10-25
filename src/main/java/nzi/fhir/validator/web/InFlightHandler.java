package nzi.fhir.validator.web;

import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts requests in progress so a draining instance can wait for them before closing, and refuses new requests
 * once the drain has passed its pre-drain window (FR-029): they get 503 {@code not-ready} with
 * {@code Connection: close}, so clients and load balancers retry on another instance.
 */
public class InFlightHandler implements PlatformHandler {
    private final AtomicInteger inFlight;
    private final AtomicBoolean refusing;

    public InFlightHandler(AtomicInteger inFlight, AtomicBoolean refusing) {
        this.inFlight = inFlight;
        this.refusing = refusing;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (refusing.get()) {
            ctx.response().putHeader("Connection", "close");
            ErrorResponder.fail(ctx, new ProblemException(503, "not-ready", "The instance is shutting down; retry on another instance"));
            return;
        }
        inFlight.incrementAndGet();
        ctx.addEndHandler(v -> inFlight.decrementAndGet());
        ctx.next();
    }
}
