package nzi.fhir.validator.web.endpoint;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.AccessChange;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.terminology.TerminologySettings;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;

import java.util.List;
import java.util.Optional;

/**
 * The validator context endpoints, {@code /{version}/validator} (4.0.0; {@code /{version}/include-ig} before):
 * {@code POST} includes an IG version (its validator context is pre-built and pinned on every instance), {@code PATCH}
 * changes an included context's sharing, owner or terminology setting, and {@code DELETE} excludes it. All are
 * administrative (ADMIN key).
 *
 * @author Md Nazrul Islam
 */
public class ValidatorApi {
    private final IgPackageService igPackageService;
    /** The versions this instance serves (spec 009). */
    private final nzi.fhir.validator.core.enums.ServedVersions served;

    /** Spec 009 FR-034: sharing forced while access control is disabled. */
    private final PublicModeSharing sharing;

    public ValidatorApi(IgPackageService igPackageService) {
        this(igPackageService, nzi.fhir.validator.core.enums.ServedVersions.ALL,
                nzi.fhir.validator.core.security.AclMode.ENABLED);
    }

    public ValidatorApi(IgPackageService igPackageService, nzi.fhir.validator.core.enums.ServedVersions served,
                        nzi.fhir.validator.core.security.AclMode aclMode) {
        this.igPackageService = igPackageService;
        this.served = served;
        this.sharing = new PublicModeSharing(aclMode);
    }

    /** Appends {@code warnings} to the response's {@code warnings} array (created when missing). */
    static JsonObject withWarnings(JsonObject response, List<String> warnings) {
        if (!warnings.isEmpty()) {
            io.vertx.core.json.JsonArray all = response.getJsonArray("warnings");
            if (all == null) {
                all = new io.vertx.core.json.JsonArray();
                response.put("warnings", all);
            }
            warnings.forEach(all::add);
        }
        return response;
    }

    /**
     * Configures the routes of the validator context endpoints.
     *
     * @param routerBuilder The Vert.x router to configure
     */
    public void includeRoutes(RouterBuilder routerBuilder) {
        // Method: POST, Path: "/:version/validator"
        routerBuilder.operation("validatorApiIncludeIg")
                .handler(this::handleCreateValidator);
        // Method: DELETE, Path: "/:version/validator?ig=name#version"
        routerBuilder.operation("validatorApiExcludeIg")
                .handler(this::handleRemoveValidator);
        // Method: PATCH, Path: "/:version/validator?ig=name#version" (spec 005 sharing and ownership, spec 007 terminology)
        routerBuilder.operation("validatorApiChangeIncludedIg")
                .handler(this::handleChangeValidatorAccess);
    }

    /** Changing or removing a stored context: any supported version, also one not served (spec 009 FR-032). */
    private SupportedFhirVersion version(RoutingContext ctx) {
        return VersionParams.supported(ctx.pathParam("version"));
    }

    /** {@code POST /{version}/validator}: includes an IG version (spec 004 FR-019, spec 006, spec 007 FR-003). */
    private void handleCreateValidator(RoutingContext ctx) {
        SupportedFhirVersion version;
        JsonObject body;
        try {
            // Spec 009 FR-008: checked before the body, so a refused request touches nothing.
            version = VersionParams.servedForInclude(ctx.pathParam("version"), served);
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
        // Spec 007 FR-003: the new context's terminology setting (contracts §2).
        Optional<TerminologySettings> terminology;
        try {
            Object raw = body.getValue("terminology");
            if (raw != null && !(raw instanceof JsonObject)) {
                throw new ProblemException(400, "invalid-request", "terminology must be an object with a mode");
            }
            terminology = raw == null ? Optional.empty() : Optional.of(TerminologySettings.parse((JsonObject) raw));
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        String requestedVersion = body.getString("igPackageVersion", "latest");
        List<String> forced = new java.util.ArrayList<>();
        Optional<Boolean> sharingRequested = sharing.apply(Optional.ofNullable((Boolean) shareable), forced);
        // Spec 006 FR-013, research R7: key.igPackage is the concrete version; requestedVersion echoes the request.
        ErrorResponder.respond(ctx, igPackageService.include(ApiKeyAuthHandler.caller(ctx), version,
                body.getString("igPackageId"), requestedVersion, sharingRequested, terminology)
                .map(json -> withWarnings(json.put("requestedVersion", requestedVersion), forced)));
    }

    /** {@code PATCH /{version}/validator?ig=name#version} (spec 005 FR-019, spec 007 FR-003, contracts §3-4). */
    private void handleChangeValidatorAccess(RoutingContext ctx) {
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
        List<String> forced = new java.util.ArrayList<>();
        AccessChange change = sharing.apply(AccessChange.parse(body), forced);
        ErrorResponder.respond(ctx, igPackageService.changeSnapshotAccess(ApiKeyAuthHandler.caller(ctx), version,
                values.get(0), change).map(json -> withWarnings(json, forced)));
    }

    /** Stops pre-building an included IG version (spec 004 FR-024): {@code DELETE /{version}/validator?ig=name#version}. */
    private void handleRemoveValidator(RoutingContext ctx) {
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
