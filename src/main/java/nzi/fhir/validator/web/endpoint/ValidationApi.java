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
import nzi.fhir.validator.core.service.FhirValidationService;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.validator.OutcomeRenderer;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;

/**
 * Validation and IG inclusion endpoints.
 * <p>
 * Status mapping for validation (research R12): 200 for a completed validation, valid or not; 400
 * {@code invalid-resource} for a body that is not FHIR; 406/415 for unacceptable media types; 503 when not ready or
 * overloaded; 500 otherwise. Every non-200 answer is a problem document.
 *
 * @author Md Nazrul Islam
 */
public class ValidationApi {
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
        if (ctx.body() == null || ctx.body().isEmpty()) {
            ErrorResponder.problem(ctx, 400, "invalid-request", "The request body is empty");
            return;
        }
        SupportedContentType contentType = requestFormat == RequestFormat.XML ? SupportedContentType.XML : SupportedContentType.JSON;
        Future<String> rendered = validationService
                .validate(version, ctx.body().asString(), contentType,
                        ValidationRequestOptions.fromRequest(ctx.request()).getProfilesToValidate())
                .map(outcome -> OutcomeRenderer.render(outcome, responseFormat))
                .recover(err -> err instanceof DataFormatException
                        ? Future.failedFuture(invalidResource(err.getMessage()))
                        : Future.failedFuture(err));
        ErrorResponder.respond(ctx, rendered, (rc, body) -> rc.response()
                .setStatusCode(200)
                .putHeader("Content-Type", responseFormat.mimeType())
                .end(body));
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
        ErrorResponder.respond(ctx, igPackageService.include(version, body.getString("igPackageId"),
                body.getString("igPackageVersion", "latest")));
    }
}
