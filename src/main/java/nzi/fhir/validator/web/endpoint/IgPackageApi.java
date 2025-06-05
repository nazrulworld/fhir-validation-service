package nzi.fhir.validator.web.endpoint;

import io.vertx.core.*;
import io.vertx.core.json.*;
import io.vertx.ext.web.*;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.client.WebClient;
import nzi.fhir.validator.web.service.IgPackageService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.utilities.npm.PackageServer;

import java.net.URL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author Md Nazrul Islam
 */
public class IgPackageApi {
    private static final Logger logger = LogManager.getLogger(IgPackageApi.class);
    private final IgPackageService igPackageService;
    private final Vertx vertx;
    private final WebClient webClient;
    private static final long MAX_UPLOAD_SIZE = 20_000_000L; // 20MB
    // Add constant for the upload directory name
    private static final String UPLOAD_DIR_NAME = "ig-uploads";

    public IgPackageApi(Vertx vertx, IgPackageService igPackageService) {
        this.igPackageService = igPackageService;
        this.vertx = vertx;
        this.webClient = WebClient.create(vertx);

    }
    /**
     * Configures the routes for validation API endpoints.
     *
     * @param router The Vert.x router to configure
     */
    public void includeRoutes(Router router) {

        router.get("/igs/:name/:version/dependencies")
                .handler(ctx -> igPackageService.getDependencyGraph(
                                ctx.pathParam("name"),
                                ctx.pathParam("version"))
                        .onSuccess(ctx::json)
                        .onFailure(err -> {
                            logger.error("Failed to get dependency graph: {}", err.toString(), err);
                            ctx.response().setStatusCode(404).end(new JsonObject().put("error", err.toString()).encode());
                        }));

        router.get("/igs/:name/:version/conformance")
                .handler(ctx -> igPackageService.generateConformanceReport(
                                ctx.pathParam("name"),
                                ctx.pathParam("version"))
                        .onSuccess(ctx::json)
                        .onFailure(err -> {
                            logger.error("Failed to generate conformance report: {}", err.toString(), err);
                            ctx.response().setStatusCode(500).end(new JsonObject().put("error", err.toString()).encode());
                        }));
        // Then use it in the router setup
        router.post("/igs/upload")
                .handler(BodyHandler.create()
                        .setUploadsDirectory(getUploadDirectory())
                        .setBodyLimit(MAX_UPLOAD_SIZE)
                        .setDeleteUploadedFilesOnEnd(true)
                )
                .handler(this::handleUploadAndRegisterIg);
        router.post("/igs/register").handler(BodyHandler.create()).handler(this::handleRegisterIg);
    }

    // Initialize the upload directory more safely
    private String getUploadDirectory() {
        Path uploadDir = Paths.get(System.getProperty("java.io.tmpdir"), UPLOAD_DIR_NAME);
        try {
            Files.createDirectories(uploadDir);
            return uploadDir.toString();
        } catch (IOException e) {
            logger.error("Failed to create upload directory", e);
            throw new RuntimeException("Could not initialize upload directory", e);
        }
    }

    private void handleRegisterIg(RoutingContext ctx) {
        if (ctx.body().isEmpty()) {
            logger.error("Missing resource in request");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Missing resource").encode());
            return;
        }
        JsonObject body = ctx.body().asJsonObject();

        final String downloadUrl = body.getString("downloadUrl", "");
        if(!downloadUrl.isEmpty()) {
            try {
                URL url = new URL(downloadUrl);
                handleDownloadIg(ctx, url);
                return;
            } catch (Exception e) {
                logger.error("Failed to parse download URL: {}", downloadUrl, e);
                ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Failed to parse download URL: " + downloadUrl).encode());
                return;
            }
        }
        final String name = body.getString("name", "");
        final String version = body.getString("version", "latest");
        if (name.isEmpty()) {
            logger.error("Missing package name/id in request");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Missing name").encode());
            return;
        }
        igPackageService.registerIg(name, version)
                .onSuccess(npmPackage -> {
                    logger.info("Registered IG: {}@{}", npmPackage.name(), npmPackage.version());
                    ctx.json(new JsonObject()
                            .put("status", "success")
                            .put("name", npmPackage.name())
                            .put("version", npmPackage.version()));

                })
                .onFailure(err -> {
                    logger.error("Failed to register IG: {}", err.toString(), err);
                    ctx.response().setStatusCode(500).end(new JsonObject().put("error", err.toString()).encode());
                });


    }

    private void handleUploadAndRegisterIg(RoutingContext ctx) {

        if (ctx.fileUploads().isEmpty()) {
            logger.error("No file uploaded");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "No file uploaded").encode());
            return;
        }

        FileUpload upload = ctx.fileUploads().get(0);
        vertx.fileSystem().readFile(upload.uploadedFileName(), res -> {
            if (res.succeeded()) {
                igPackageService.registerIg(res.result().getBytes())
                        .onSuccess(npmPackage -> {
                            logger.info("Registered IG: {}@{}", npmPackage.name(), npmPackage.version());
                            ctx.json(new JsonObject()
                                    .put("status", "success")
                                    .put("name", npmPackage.name())
                                    .put("version", npmPackage.version()));
                            vertx.fileSystem().delete(upload.uploadedFileName()); // Cleanup
                        })
                        .onFailure(err -> {
                            logger.error("Failed to register IG: {}", err.toString(), err);
                            ctx.response().setStatusCode(500).end(new JsonObject().put("error", err.toString()).encode());
                            vertx.fileSystem().delete(upload.uploadedFileName());
                        });
            } else {
                logger.error("Failed to read uploaded file: {}", res.cause().toString(), res.cause());
                ctx.response().setStatusCode(500).end(new JsonObject().put("error", res.cause().toString()).encode());
                vertx.fileSystem().delete(upload.uploadedFileName());
            }
        });
    }

    private void handleDownloadIg(RoutingContext ctx, URL url) {
        webClient.getAbs(url.toString())
                .send()
                .onSuccess(res -> {
                    if (res.statusCode() == 200) {
                        byte[] igBytes = res.body().getBytes();
                        igPackageService.registerIg(igBytes)
                                .onSuccess(npmPackage -> {
                                    logger.info("Downloaded and registered IG: {}@{}", npmPackage.name(), npmPackage.version());
                                    ctx.json(new JsonObject()
                                            .put("status", "success")
                                            .put("name", npmPackage.name())
                                            .put("version", npmPackage.name()));
                                })
                                .onFailure(err -> {
                                    logger.error("Failed to register downloaded IG: {}", err.toString(), err);
                                    ctx.response().setStatusCode(500).end(new JsonObject().put("error", err.toString()).encode());
                                });
                    } else {
                        logger.error("Failed to download IG: HTTP {}", res.statusCode());
                        ctx.response().setStatusCode(res.statusCode()).end(new JsonObject().put("error", "Failed to download IG").encode());
                    }
                })
                .onFailure(err -> {
                    logger.error("Failed to download IG: {}", err.toString(), err);
                    ctx.response().setStatusCode(500).end(new JsonObject().put("error", err.toString()).encode());
                });
    }
    private void sendErrorResponse(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
            .put("status", "error")
            .put("code", statusCode)
            .put("message", message);
        ctx.response()
            .setStatusCode(statusCode)
            .end(error.encode());
    }
}