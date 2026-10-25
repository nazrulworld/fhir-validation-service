package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.core.npm.SafePackageFetcher;
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

    public IgPackageApi(IgPackageService igPackageService, SafePackageFetcher fetcher) {
        this.igPackageService = igPackageService;
        this.fetcher = fetcher;
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
                        igPackageService.getDependencyGraph(ctx.pathParam("name"), ctx.pathParam("version"))));
        // Method: GET, Path: "/igs/:name/:version/conformance" (deprecated: a file listing)
        routerBuilder.operation("igPackageApiGenerateConformanceReport")
                .handler(ctx -> ErrorResponder.respond(ctx,
                        igPackageService.listPackageFiles(ctx.pathParam("name"), ctx.pathParam("version"))));
        // Method: POST, Path: "/igs/upload"
        routerBuilder.operation("igPackageApiUploadIg")
                .handler(this::handleUploadAndRegisterIg);
        // Method: POST, Path: "/igs/register"
        routerBuilder.operation("igPackageApiRegisterIg")
                .handler(this::handleRegisterIg);
    }

    private static JsonObject registered(NpmPackage npmPackage) {
        return new JsonObject()
                .put("status", "success")
                .put("name", npmPackage.name())
                .put("version", npmPackage.version());
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
        String downloadUrl = body.getString("downloadUrl", "");
        if (!downloadUrl.isEmpty()) {
            handleDownloadIg(ctx, downloadUrl);
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
        Future<JsonObject> result = igPackageService.registerIg(name, version, body.getBoolean("includeDependency", true))
                .compose(npmPackage -> npmPackage == null
                        ? Future.failedFuture(new ProblemException(404, "package-not-found",
                        "IG package " + name + "#" + version + " was not found in the configured package registries"))
                        : Future.succeededFuture(registered(npmPackage)));
        ErrorResponder.respond(ctx, result);
    }

    private void handleUploadAndRegisterIg(RoutingContext ctx) {
        if (ctx.fileUploads().isEmpty()) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "No file uploaded (multipart field 'file')"));
            return;
        }
        FileUpload upload = ctx.fileUploads().get(0);
        Future<JsonObject> result = igPackageService.registerUploadedFile(upload.uploadedFileName())
                .map(IgPackageApi::registered);
        ErrorResponder.respond(ctx, result);
    }

    /**
     * Downloads through {@link SafePackageFetcher}: only approved https sources, public addresses, capped size.
     */
    private void handleDownloadIg(RoutingContext ctx, String downloadUrl) {
        Future<JsonObject> result = fetcher.fetch(downloadUrl)
                .compose(body -> igPackageService.registerIg(body.getBytes(), false, "URL"))
                .map(IgPackageApi::registered);
        ErrorResponder.respond(ctx, result);
    }
}
