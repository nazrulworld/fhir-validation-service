package nzi.fhir.validator.web.endpoint;

import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.validation.BodyProcessorException;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.model.MediaTypes;
import nzi.fhir.validator.core.model.MediaTypes.RequestFormat;
import nzi.fhir.validator.core.model.MediaTypes.ResponseFormat;
import nzi.fhir.validator.core.model.ValidationRequestOptions;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;
import nzi.fhir.validator.core.service.FhirValidationService;
import nzi.fhir.validator.core.validator.IgReference;
import nzi.fhir.validator.core.validator.OutcomeRenderer;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * The validation endpoint, {@code POST /{version}/validate}. The validator context endpoints (include, change,
 * exclude) are in {@link ValidatorApi}.
 * <p>
 * Status mapping for validation (research R12): 200 for a completed validation, valid or not, with
 * {@code X-Validation-Context} naming the IG package used (or {@code base}); 400 {@code invalid-resource} for a body
 * that is not FHIR; 400 {@code invalid-request} for a malformed {@code ig} or a {@code profile} list over the limits;
 * 404 {@code package-not-found} for an {@code ig} package that is not stored; 424 {@code dependency-not-found} when a
 * dependency of it is missing; 400 {@code unknown-profile} for a requested profile the chosen context doesn't define
 * (specs 003, 004); 406/415 for unacceptable media types; 413 for a context that can never fit; 503 when not ready,
 * overloaded or out of capacity; 500 otherwise. Every non-200 answer is a problem document, whatever {@code Accept}
 * asked for.
 *
 * @author Md Nazrul Islam
 */
public class ValidationApi {
    private static final Logger logger = LogManager.getLogger(ValidationApi.class);
    private final FhirValidationService validationService;
    private volatile nzi.fhir.validator.core.usage.UsageWriter usage = nzi.fhir.validator.core.usage.NoopUsageWriter.INSTANCE;

    public ValidationApi(FhirValidationService validationService) {
        this.validationService = validationService;
    }

    /** Where usage records go (spec 008 FR-014); the default keeps nothing but metrics are still measured. */
    public void setUsageWriter(nzi.fhir.validator.core.usage.UsageWriter usage) {
        this.usage = usage;
    }

    /**
     * Spec 008 (research R10): a meter for this request, finished when the response ends. Full mode (a record) only when
     * a sink is enabled and the caller has a key; metrics-only otherwise.
     */
    private nzi.fhir.validator.core.usage.UsageMeter newMeter(RoutingContext ctx, String version) {
        nzi.fhir.validator.core.security.Caller caller = ApiKeyAuthHandler.caller(ctx);
        nzi.fhir.validator.core.usage.UsageWriter writer = usage;
        java.util.function.Consumer<nzi.fhir.validator.core.usage.UsageRecord> sink =
                writer.enabled() && !caller.anonymous() && caller.orgId() != null ? writer::write : null;
        String clientIp = null;
        if (sink != null) {
            // Only needed for the record (code review: skip the formatting on the default sink "none").
            java.net.InetAddress client = nzi.fhir.validator.core.security.ClientAddress.of(ctx);
            clientIp = client == null ? null : client.getHostAddress();
        }
        return nzi.fhir.validator.core.usage.UsageMeter.start(nzi.fhir.validator.web.RequestIdHandler.requestId(ctx), caller,
                clientIp, version, ctx.body() == null ? 0 : Math.max(0, ctx.body().length()), sink);
    }

    private static final java.util.regex.Pattern VALIDATE_PATH = java.util.regex.Pattern.compile("^/([^/]+)/validate/?$");

    /**
     * Spec 008 (FR-011, code review): a root handler, mounted right after authorisation, that starts the request's one
     * usage meter for every authorised {@code POST /{version}/validate}. Starting it here gives the record the real start
     * time, and covers requests the router refuses before any operation handler runs (406, 415).
     */
    public io.vertx.core.Handler<RoutingContext> usageFallbackHandler() {
        return ctx -> {
            java.util.regex.Matcher m = VALIDATE_PATH.matcher(ctx.normalizedPath());
            if (ctx.request().method() == io.vertx.core.http.HttpMethod.POST && m.matches()) {
                meterFor(ctx, m.group(1));
            }
            ctx.next();
        };
    }

    /**
     * The request's usage meter, created on first use and stored in the context (one per request). When the response
     * ends, the meter records it, and also closes the work side unless the validation service was started (whose future
     * signals it): so a request answered without validating (a router refusal, a timeout, an early 4xx) is recorded
     * exactly once, whoever answered it.
     */
    private nzi.fhir.validator.core.usage.UsageMeter meterFor(RoutingContext ctx, String version) {
        nzi.fhir.validator.core.usage.UsageMeter existing = ctx.get(METER_KEY);
        if (existing != null) {
            return existing;
        }
        nzi.fhir.validator.core.usage.UsageMeter meter = newMeter(ctx, version);
        ctx.put(METER_KEY, meter);
        if (ctx.response().ended()) {
            // End handlers have already run (e.g. the request timeout answered during the key lookup): record it now.
            endMeter(ctx, meter, false);
        } else {
            ctx.addEndHandler(ar -> endMeter(ctx, meter, ar.failed()));
        }
        return meter;
    }

    private static void endMeter(RoutingContext ctx, nzi.fhir.validator.core.usage.UsageMeter meter, boolean reset) {
        meter.responseEnded(ctx.response().getStatusCode(), reset, nzi.fhir.validator.web.RequestTimeoutHandler.timedOut(ctx));
        if (!Boolean.TRUE.equals(ctx.get(WORK_STARTED_KEY))) {
            meter.workNeverStarted();
        }
    }

    /** Set just before the validation service is called: its future then signals the meter's work side. */
    private static final String WORK_STARTED_KEY = "usageWorkStarted";

    private nzi.fhir.validator.core.usage.UsageMeter startMeter(RoutingContext ctx) {
        return meterFor(ctx, ctx.pathParam("version"));
    }

    private static final String METER_KEY = "usageMeter";

    /**
     * Configures the routes for validation API endpoints.
     *
     * @param routerBuilder The Vert.x router to configure
     */
    public void includeRoutes(RouterBuilder routerBuilder) {
        // Method: POST, Path: "/:version/validate"
        routerBuilder.operation("validationApiValidate")
                .handler(this::handleDoValidation)
                .failureHandler(this::handleValidateFailure);
    }

    private static SupportedFhirVersion version(RoutingContext ctx) {
        String param = ctx.pathParam("version");
        return SupportedFhirVersion.parseEnabled(param).orElseThrow(() -> ProblemException.unsupportedVersion(param));
    }

    private void handleDoValidation(RoutingContext ctx) {
        nzi.fhir.validator.core.usage.UsageMeter meter = startMeter(ctx);
        if (ctx.response().ended()) {
            // Code review: the request timeout already answered (a slow upload or key lookup). Don't spend a worker on
            // a validation nobody will receive; the meter has already recorded the end (outcome timeout).
            meter.workNeverStarted();
            return;
        }
        boolean started = false;
        try {
            started = doValidation(ctx, meter);
        } finally {
            // Every path that didn't call the service (early 4xx answers, or an unexpected exception) closes the meter
            // here, once; the service's future signals it otherwise (code review).
            if (!started) {
                meter.workNeverStarted();
            }
        }
    }

    /** @return whether the validation service was called (its future then signals the meter) */
    private boolean doValidation(RoutingContext ctx, nzi.fhir.validator.core.usage.UsageMeter meter) {
        SupportedFhirVersion version;
        try {
            version = version(ctx);
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return false;
        }
        RequestFormat requestFormat = MediaTypes.requestFormat(ctx.request().getHeader("Content-Type"));
        if (requestFormat == RequestFormat.UNSUPPORTED) {
            ErrorResponder.problem(ctx, 415, "unsupported-media-type", "Send FHIR resources as JSON or XML");
            return false;
        }
        meter.contentType(requestFormat == RequestFormat.XML ? "XML" : "JSON");
        ResponseFormat responseFormat = MediaTypes.responseFormat(ctx.request().getHeader("Accept"));
        if (responseFormat == ResponseFormat.NONE) {
            ErrorResponder.problem(ctx, 406, "not-acceptable",
                    "Acceptable response types: application/json, application/fhir+json, application/fhir+xml, application/xml");
            return false;
        }
        List<String> profiles;
        try {
            profiles = ValidationRequestOptions.fromRequest(ctx.request()).getProfilesToValidate();
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return false;
        }
        meter.profiles(profiles == null ? 0 : profiles.size());
        IgReference ig;
        try {
            ig = igParameter(ctx);
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return false;
        }
        meter.ig(ctx.queryParams().get("ig"));
        if (ctx.body() == null || ctx.body().isEmpty()) {
            ErrorResponder.problem(ctx, 400, "invalid-request", "The request body is empty");
            return false;
        }
        SupportedContentType contentType = requestFormat == RequestFormat.XML ? SupportedContentType.XML : SupportedContentType.JSON;
        ctx.put(WORK_STARTED_KEY, true);
        Future<Rendered> rendered = validationService
                .validate(ApiKeyAuthHandler.caller(ctx), version, ig, ctx.body().asString(), contentType, profiles, meter,
                        outcome -> new Rendered(OutcomeRenderer.render(outcome, responseFormat), outcome.contextLabel(),
                                outcome.contextAccess()))
                .recover(err -> {
                    if (err instanceof DataFormatException) {
                        return Future.failedFuture(invalidResource(err.getMessage()));
                    }
                    if (err instanceof ProblemException p && ProblemException.TYPE_UNKNOWN_PROFILE.equals(p.type())
                            && logger.isDebugEnabled()) {
                        // The request id is added by the log layout (%vcl{requestId}); client input stays at DEBUG.
                        logger.debug("Unresolved profiles for {}: {}", version, p.extensions().getValue("unresolvedProfiles"));
                    }
                    return Future.failedFuture(err);
                });
        ErrorResponder.respond(ctx, rendered, (rc, result) -> {
            rc.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", responseFormat.mimeType())
                    .putHeader(VALIDATION_CONTEXT_HEADER, result.context());
            if (result.access() != null) {
                rc.response()
                        .putHeader(VALIDATION_CONTEXT_OWNER_HEADER, result.access().ownerShortName())
                        .putHeader(VALIDATION_CONTEXT_SHAREABLE_HEADER, String.valueOf(result.access().shareable()));
            }
            rc.response().end(result.body());
        });
        return true;
    }

    /** The short name of the organisation owning the validator context of a 200 result (spec 005 FR-020). */
    public static final String VALIDATION_CONTEXT_OWNER_HEADER = "X-Validation-Context-Owner";
    /** Whether the validator context of a 200 result is shareable, {@code true} or {@code false} (spec 005 FR-020). */
    public static final String VALIDATION_CONTEXT_SHAREABLE_HEADER = "X-Validation-Context-Shareable";

    /** Names the validator context a 200 result came from: the IG package {@code name#version}, or {@code base}. */
    public static final String VALIDATION_CONTEXT_HEADER = "X-Validation-Context";

    private record Rendered(String body, String context, Access access) {
    }

    /**
     * The {@code ig} query parameter: absent is {@code null} (the base context); more than one value, or a malformed
     * one, is 400 {@code invalid-request} (FR-002). Client input is logged at DEBUG only.
     */
    private static IgReference igParameter(RoutingContext ctx) {
        List<String> values = ctx.queryParam("ig");
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new ProblemException(400, "invalid-request", "Name at most one IG package per request (ig parameter)");
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Validation requested against ig={}", values.get(0));
        }
        return IgReference.parse(values.get(0));
    }

    private static ProblemException invalidResource(String parserMessage) {
        return new ProblemException(400, "invalid-resource", "The body is not a parseable FHIR resource")
                .withExtension("issues", OutcomeRenderer.parseFailure(parserMessage));
    }

    /**
     * Contract-level failures of the validate operation: malformed JSON is an invalid resource, an undeclared
     * content type is 415; everything else follows the common mapping.
     */
    private void handleValidateFailure(RoutingContext ctx) {
        if (!Boolean.TRUE.equals(ctx.get(WORK_STARTED_KEY))) {
            // Spec 008 FR-011: refused before validating (e.g. by the contract layer); the response end records it.
            nzi.fhir.validator.core.usage.UsageMeter meter = startMeter(ctx);
            if (ctx.failure() instanceof BodyProcessorException e
                    && e.getErrorType() == BodyProcessorException.BodyProcessorErrorType.PARSING_ERROR) {
                meter.outcome(nzi.fhir.validator.core.metrics.ServiceMetrics.OUTCOME_UNPARSEABLE);
            }
        }
        // FR-009: the ig parameter's shape is checked before the body, also when the contract layer has already
        // refused the body (e.g. an empty or malformed JSON body).
        try {
            igParameter(ctx);
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        if (ctx.failure() instanceof BodyProcessorException e
                && e.getErrorType() == BodyProcessorException.BodyProcessorErrorType.PARSING_ERROR) {
            ErrorResponder.fail(ctx, invalidResource("The body is not well-formed JSON"));
            return;
        }
        ErrorResponder.fail(ctx, ctx.failure(), ctx.statusCode());
    }
}
