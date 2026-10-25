package nzi.fhir.validator.web;

import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts requests in progress so a draining instance can wait for them before closing (FR-029).
 */
public class InFlightHandler implements PlatformHandler {
    private final AtomicInteger inFlight;

    public InFlightHandler(AtomicInteger inFlight) {
        this.inFlight = inFlight;
    }

    @Override
    public void handle(RoutingContext ctx) {
        inFlight.incrementAndGet();
        ctx.addEndHandler(v -> inFlight.decrementAndGet());
        ctx.next();
    }
}
