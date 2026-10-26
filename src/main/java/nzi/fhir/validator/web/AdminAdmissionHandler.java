package nzi.fhir.validator.web;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;

/**
 * Bounds administrative operations (upload, register, include-ig, register-profile) per instance with their own
 * admission limit, {@code FV_ADMIN_MAX_PENDING}, independent of validation (FR-010). A request holds its slot until
 * its response ends; beyond the limit it is refused at once with 503 {@code overloaded}. Mounted after authorisation,
 * so unauthenticated calls never take a slot. Background configuration syncs don't pass through the router and are
 * never refused.
 */
public class AdminAdmissionHandler implements Handler<RoutingContext> {
    private final AdmissionController admission;

    public AdminAdmissionHandler(AdmissionController admission) {
        this.admission = admission;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (!ApiKeyAuthHandler.isAdministrative(ctx)) {
            ctx.next();
            return;
        }
        if (!admission.tryAcquire()) {
            ErrorResponder.fail(ctx, admission.overloaded());
            return;
        }
        ctx.addEndHandler(v -> admission.release());
        ctx.next();
    }
}
