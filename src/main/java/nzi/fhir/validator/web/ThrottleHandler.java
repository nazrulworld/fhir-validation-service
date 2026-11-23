package nzi.fhir.validator.web;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.throttle.ThrottleDecision;
import nzi.fhir.validator.core.throttle.ThrottleService;

/**
 * Spec 014 (research R1): per-minute caps on {@code POST /{version}/validate}. Mounted right after authentication
 * (FR-007), so the {@link Caller} is known and a refused request is never parsed, validated or usage-recorded
 * (FR-018a). Mounted only while access control is enabled (FR-002). Keys of the system organisation pass unchecked
 * (FR-006b). Every response to a limited key carries the {@code RateLimit-*} headers (FR-010a); a request over a cap
 * gets 429 {@code rate-limited} with {@code Retry-After} (FR-008, FR-009).
 */
public final class ThrottleHandler implements Handler<RoutingContext> {
    public static final String LIMIT_HEADER = "RateLimit-Limit";
    public static final String REMAINING_HEADER = "RateLimit-Remaining";
    public static final String RESET_HEADER = "RateLimit-Reset";

    private static final java.util.regex.Pattern VALIDATE_PATH = java.util.regex.Pattern.compile("^/[^/]+/validate/?$");

    private final ThrottleService service;

    public ThrottleHandler(ThrottleService service) {
        this.service = service;
    }

    @Override
    public void handle(RoutingContext ctx) {
        if (ctx.request().method() != HttpMethod.POST || !VALIDATE_PATH.matcher(ctx.normalizedPath()).matches()) {
            ctx.next();
            return;
        }
        Caller caller = ApiKeyAuthHandler.caller(ctx);
        // FR-006b: the system organisation is never limited. An anonymous caller never gets here (auth refused it).
        if (caller.anonymous() || caller.system()) {
            ctx.next();
            return;
        }
        if (!service.ready()) {
            ErrorResponder.fail(ctx, ProblemException.notReady("The service is still starting; retry shortly"));
            return;
        }
        ThrottleDecision d = service.check(caller);
        if (d.limited()) {
            ctx.response()
                    .putHeader(LIMIT_HEADER, String.valueOf(d.limit()))
                    .putHeader(REMAINING_HEADER, String.valueOf(d.remaining()))
                    .putHeader(RESET_HEADER, String.valueOf(d.resetSeconds()));
        }
        if (d.admitted()) {
            ctx.next();
            return;
        }
        refused(ctx, caller, d);
        ErrorResponder.fail(ctx, ProblemException.rateLimited(d));
    }

    /** Hook for the refusal metric and the bounded refusal log (FR-017, FR-018). */
    private void refused(RoutingContext ctx, Caller caller, ThrottleDecision d) {
        service.refused(caller, d, RequestIdHandler.requestId(ctx));
    }
}
