package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.core.npm.SafePackageFetcher;
import nzi.fhir.validator.core.npm.PackageOwnership;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.AccessChange;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;
import org.hl7.fhir.utilities.npm.NpmPackage;


/**
 * Storage and inspection of IG packages.
 *
 * @author Md Nazrul Islam
 */
public class IgPackageApi {
    private final IgPackageService igPackageService;
    private final SafePackageFetcher fetcher;

    /** Spec 009 FR-034: sharing forced while access control is disabled. */
    private final PublicModeSharing sharing;

    public IgPackageApi(IgPackageService igPackageService, SafePackageFetcher fetcher,
                        nzi.fhir.validator.core.security.AclMode aclMode) {
        this.igPackageService = igPackageService;
        this.fetcher = fetcher;
        this.sharing = new PublicModeSharing(aclMode);
    }

    /**
     * Configures the routes for IG package endpoints.
     *
     * @param routerBuilder The Vert.x router to configure
     */
    public void includeRoutes(RouterBuilder routerBuilder) {
        // Method: GET Path: "/igs/:name/:version/dependencies"
        routerBuilder.operation("igPackageApiGetDependenciesGraph")
                .handler(ctx -> ErrorResponder.respond(ctx,
                        igPackageService.getDependencyGraph(ApiKeyAuthHandler.caller(ctx), ctx.pathParam("name"),
                                ctx.pathParam("version"))));
        // Method: GET, Path: "/igs/:name/:version/conformance" (deprecated: a file listing)
        routerBuilder.operation("igPackageApiGenerateConformanceReport")
                .handler(ctx -> ErrorResponder.respond(ctx,
                        igPackageService.listPackageFiles(ApiKeyAuthHandler.caller(ctx), ctx.pathParam("name"),
                                ctx.pathParam("version"))));
        // Method: POST, Path: "/igs/upload"
        routerBuilder.operation("igPackageApiUploadIg")
                .handler(this::handleUploadAndRegisterIg);
        // Method: POST, Path: "/igs/register"
        routerBuilder.operation("igPackageApiRegisterIg")
                .handler(this::handleRegisterIg);
        // Method: PATCH, Path: "/igs/:name/:version" (spec 005: sharing and ownership)
        routerBuilder.operation("igPackageApiChangeAccess")
                .handler(this::handleChangeAccess);
    }

    private static JsonObject registered(NpmPackage npmPackage, boolean downloaded, java.util.List<String> warnings,
                                         Access access) {
        return new JsonObject()
                .put("status", "success")
                .put("name", npmPackage.name())
                .put("version", npmPackage.version())
                .put("downloaded", downloaded)
                .put("owner", access == null ? null : access.ownerShortName())
                .put("shareable", access == null ? null : access.shareable())
                .put("warnings", new io.vertx.core.json.JsonArray(new java.util.ArrayList<>(warnings)));
    }

    /** {@code registered(...)} with the stored package's owner and sharing (spec 005 FR-020). */
    private Future<JsonObject> registered(NpmPackage pkg, boolean downloaded, java.util.List<String> warnings) {
        return igPackageService.access(pkg.name(), pkg.version())
                .map(access -> registered(pkg, downloaded, warnings, access.orElse(null)));
    }

    private static PackageOwnership ownership(RoutingContext ctx, boolean shareable) {
        try {
            return PackageOwnership.of(ApiKeyAuthHandler.caller(ctx), shareable);
        } catch (IllegalArgumentException e) {
            throw new ProblemException(403, "forbidden", "This API key does not allow this operation");
        }
    }

    /** {@code PATCH /igs/{name}/{version}} (spec 005 FR-019, contracts §5). */
    private void handleChangeAccess(RoutingContext ctx) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            body = null;
        }
        java.util.List<String> forced = new java.util.ArrayList<>();
        AccessChange change = sharing.apply(AccessChange.parse(body), forced);
        ErrorResponder.respond(ctx, igPackageService.changePackageAccess(ApiKeyAuthHandler.caller(ctx),
                ctx.pathParam("name"), ctx.pathParam("version"), change)
                // Spec 009 FR-034: only when sharing was forced, so the response is unchanged otherwise.
                .map(json -> ValidatorApi.withWarnings(json, forced)));
    }

    private void handleRegisterIg(RoutingContext ctx) {
        JsonObject body;
        try {
            body = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "The body must be a JSON object"));
            return;
        }
        if (body == null) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "The body must be a JSON object"));
            return;
        }
        Object shareableValue = body.getValue("shareable", true);
        if (!(shareableValue instanceof Boolean requestedShareable)) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "shareable must be true or false"));
            return;
        }
        java.util.List<String> forced = new java.util.ArrayList<>();
        boolean shareable = sharing.apply(requestedShareable, forced);
        String downloadUrl = body.getString("downloadUrl", "");
        if (!downloadUrl.isEmpty()) {
            handleDownloadIg(ctx, downloadUrl, shareable, forced);
            return;
        }
        String name = body.getString("name", "");
        String version = body.getString("version", "latest");
        if (name.isEmpty()) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "Either 'name' or 'downloadUrl' is required"));
            return;
        }
        if (!"latest".equals(version) && !PostgresPackageCacheManager.isValidVersionLabel(version)) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "version must be 1-64 characters without whitespace"));
            return;
        }
        Future<JsonObject> result = igPackageService.register(ApiKeyAuthHandler.caller(ctx), name, version,
                        body.getBoolean("includeDependency", true), shareable)
                // Spec 006 FR-010: the concrete version is `version`; `requestedVersion` echoes latest, exact or series.
                .map(r -> ValidatorApi.withWarnings(registered(r.pkg(), r.downloaded(), r.warnings(), r.access()), forced)
                        .put("requestedVersion", version));
        ErrorResponder.respond(ctx, result);
    }

    private void handleUploadAndRegisterIg(RoutingContext ctx) {
        if (ctx.fileUploads().isEmpty()) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "No file uploaded (multipart field 'file')"));
            return;
        }
        FileUpload upload = ctx.fileUploads().get(0);
        String shareable = ctx.request().getFormAttribute("shareable");
        if (shareable != null && !shareable.equals("true") && !shareable.equals("false")) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "shareable must be true or false"));
            return;
        }
        java.util.List<String> forced = new java.util.ArrayList<>();
        PackageOwnership ownership;
        try {
            ownership = ownership(ctx, sharing.apply(shareable == null || Boolean.parseBoolean(shareable), forced));
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        Future<JsonObject> result = igPackageService.registerUploadedFile(upload.uploadedFileName(), ownership)
                .compose(pkg -> registered(pkg, false, forced));
        ErrorResponder.respond(ctx, result);
    }

    /**
     * Downloads through {@link SafePackageFetcher}: only approved https sources, public addresses, capped size.
     */
    private void handleDownloadIg(RoutingContext ctx, String downloadUrl, boolean shareable, java.util.List<String> forced) {
        PackageOwnership ownership;
        try {
            ownership = ownership(ctx, shareable);
        } catch (ProblemException e) {
            ErrorResponder.fail(ctx, e);
            return;
        }
        Future<JsonObject> result = fetcher.fetch(downloadUrl)
                .compose(body -> igPackageService.registerIg(body.getBytes(), false, "URL", ownership))
                .compose(pkg -> registered(pkg, true, forced));
        ErrorResponder.respond(ctx, result);
    }
}
