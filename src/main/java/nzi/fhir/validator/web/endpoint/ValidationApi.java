package nzi.fhir.validator.web.endpoint;

import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
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
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.validator.IgReference;
import nzi.fhir.validator.core.validator.OutcomeRenderer;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Validation and IG inclusion endpoints.
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
    private final IgPackageService igPackageService;

    public ValidationApi(FhirValidationService validationService, IgPackageService igPackageService) {
        this.validationService = validationService;
        this.igPackageService = igPackageService;
    }

    /**
     * Configures the routes for validation API endpoints.
     *
     * @param routerBuilder The Vert.x router to configure
     */
    public void includeRoutes(RouterBuilder routerBuilder) {
        // Method: POST, Path: "/:version/validate"
        routerBuilder.operation("validationApiValidate")
                .handler(this::handleDoValidation)
                .failureHandler(ValidationApi::handleValidateFailure);
        // Method: POST, Path: "/:version/include-ig"
        routerBuilder.operation("validationApiIncludeIg")
                .handler(this::handleIncludeImplementationGuide);
        // Method: DELETE, Path: "/:version/include-ig?ig=name#version"
        routerBuilder.operation("validationApiExcludeIg")
                .handler(this::handleExcludeImplementationGuide);
        // Method: PATCH, Path: "/:version/include-ig?ig=name#version" (spec 005: sharing and ownership)
        routerBuilder.operation("validationApiChangeIncludedIgAccess")
                .handler(this::handleChangeIncludedIgAccess);
    }

    private static SupportedFhirVersion version(RoutingContext ctx) {
        String param = ctx.pathParam("version");
        return SupportedFhirVersion.parseEnabled(param).orElseThrow(() -> ProblemException.unsupportedVersion(param));
    }

    private void handleDoValidation(RoutingContext ctx) {
        SupportedFhirVersion version;
        try {
            version = version(ctx);
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        RequestFormat requestFormat = MediaTypes.requestFormat(ctx.request().getHeader("Content-Type"));
        if (requestFormat == RequestFormat.UNSUPPORTED) {
            ErrorResponder.problem(ctx, 415, "unsupported-media-type", "Send FHIR resources as JSON or XML");
            return;
        }
        ResponseFormat responseFormat = MediaTypes.responseFormat(ctx.request().getHeader("Accept"));
        if (responseFormat == ResponseFormat.NONE) {
            ErrorResponder.problem(ctx, 406, "not-acceptable",
                    "Acceptable response types: application/json, application/fhir+json, application/fhir+xml, application/xml");
            return;
        }
        List<String> profiles;
        try {
            profiles = ValidationRequestOptions.fromRequest(ctx.request()).getProfilesToValidate();
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        IgReference ig;
        try {
            ig = igParameter(ctx);
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        if (ctx.body() == null || ctx.body().isEmpty()) {
            ErrorResponder.problem(ctx, 400, "invalid-request", "The request body is empty");
            return;
        }
        SupportedContentType contentType = requestFormat == RequestFormat.XML ? SupportedContentType.XML : SupportedContentType.JSON;
        Future<Rendered> rendered = validationService
                .validate(ApiKeyAuthHandler.caller(ctx), version, ig, ctx.body().asString(), contentType, profiles,
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
    private static void handleValidateFailure(RoutingContext ctx) {
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

    private void handleIncludeImplementationGuide(RoutingContext ctx) {
        SupportedFhirVersion version;
        JsonObject body;
        try {
            version = version(ctx);
            body = ctx.body().asJsonObject();
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        } catch (RuntimeException e) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "The body must be a JSON object"));
            return;
        }
        if (body == null || body.getString("igPackageId") == null) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "igPackageId is required"));
            return;
        }
        Object shareable = body.getValue("shareable");
        if (shareable != null && !(shareable instanceof Boolean)) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "shareable must be true or false"));
            return;
        }
        ErrorResponder.respond(ctx, igPackageService.include(ApiKeyAuthHandler.caller(ctx), version,
                body.getString("igPackageId"), body.getString("igPackageVersion", "latest"),
                java.util.Optional.ofNullable((Boolean) shareable)));
    }

    /** {@code PATCH /{version}/include-ig?ig=name#version} (spec 005 FR-019, contracts §4). */
    private void handleChangeIncludedIgAccess(RoutingContext ctx) {
        SupportedFhirVersion version;
        List<String> values = ctx.queryParam("ig");
        try {
            version = version(ctx);
            if (values == null || values.size() != 1) {
                throw new ProblemException(400, "invalid-request", "Exactly one ig=name#version is required");
            }
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        ErrorResponder.respond(ctx, igPackageService.changeSnapshotAccess(ApiKeyAuthHandler.caller(ctx), version,
                values.get(0), nzi.fhir.validator.core.security.AccessChange.parse(body)));
    }

    /** Stops pre-building an included IG version (spec 004 FR-024): {@code DELETE /{version}/include-ig?ig=name#version}. */
    private void handleExcludeImplementationGuide(RoutingContext ctx) {
        SupportedFhirVersion version;
        List<String> values = ctx.queryParam("ig");
        try {
            version = version(ctx);
            if (values == null || values.size() != 1) {
                throw new ProblemException(400, "invalid-request", "Exactly one ig=name#version is required");
            }
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        ErrorResponder.respond(ctx, igPackageService.exclude(ApiKeyAuthHandler.caller(ctx), version, values.get(0)));
    }
}
