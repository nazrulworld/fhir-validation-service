package nzi.fhir.validator.web.endpoint;

import io.vertx.core.*;
import io.vertx.core.json.*;
import io.vertx.ext.web.*;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.client.WebClient;
import nzi.fhir.validator.web.service.IgService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class IgApi {
    private static final Logger logger = LogManager.getLogger(IgApi.class);
    private final IgService igService;
    private final Vertx vertx;
    private final WebClient webClient;

    public IgApi(Router router, Vertx vertx, IgService igService, WebClient webClient) {
        this.igService = igService;
        this.vertx = vertx;
        this.webClient = webClient;

        router.post("/igs")
                .handler(BodyHandler.create()
                        .setUploadsDirectory("uploads")
                        .setMergeFormAttributes(true))
                .handler(this::handleRegisterIg);

        router.get("/igs/:name/:version/dependencies")
                .handler(ctx -> igService.getDependencyGraph(
                                ctx.pathParam("name"),
                                ctx.pathParam("version"))
                        .onSuccess(ctx::json)
                        .onFailure(err -> {
                            logger.error("Failed to get dependency graph: {}", err.toString(), err);
                            ctx.response().setStatusCode(404).end(new JsonObject().put("error", err.toString()).encode());
                        }));

        router.get("/igs/:name/:version/conformance")
                .handler(ctx -> igService.generateConformanceReport(
                                ctx.pathParam("name"),
                                ctx.pathParam("version"))
                        .onSuccess(ctx::json)
                        .onFailure(err -> {
                            logger.error("Failed to generate conformance report: {}", err.toString(), err);
                            ctx.response().setStatusCode(500).end(new JsonObject().put("error", err.toString()).encode());
                        }));

        router.post("/igs/download")
                .handler(BodyHandler.create())
                .handler(this::handleDownloadIg);
    }

    private void handleRegisterIg(RoutingContext ctx) {
        final String name = ctx.request().getFormAttribute("name");
        final String version = ctx.request().getFormAttribute("version") != null ? 
                               ctx.request().getFormAttribute("version") : "latest";
        final String packageId = ctx.request().getFormAttribute("packageId");
        final String dependenciesStr = ctx.request().getFormAttribute("dependencies") != null ? 
                                      ctx.request().getFormAttribute("dependencies") : "[]";
        final JsonArray dependencies = new JsonArray(dependenciesStr);

        if (name == null || packageId == null) {
            logger.error("Missing name or packageId in form");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Missing name or packageId").encode());
            return;
        }

        if (ctx.fileUploads().isEmpty()) {
            logger.error("No file uploaded");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "No file uploaded").encode());
            return;
        }

        FileUpload upload = ctx.fileUploads().get(0);
        vertx.fileSystem().readFile(upload.uploadedFileName(), res -> {
            if (res.succeeded()) {
                igService.registerIg(name, version, packageId, res.result().getBytes(), dependencies.getList())
                        .onSuccess(v -> {
                            logger.info("Registered IG: {}@{}", name, version);
                            ctx.json(new JsonObject()
                                    .put("status", "success")
                                    .put("name", name)
                                    .put("version", version));
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

    private void handleDownloadIg(RoutingContext ctx) {
        JsonObject request = ctx.body().asJsonObject();
        if (request == null) {
            logger.error("Request body is null");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Request body is null").encode());
            return;
        }
        String packageId = request.getString("packageId");
        String version = request.getString("version", "latest");

        if (packageId == null) {
            logger.error("Missing packageId in request");
            ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Missing packageId").encode());
            return;
        }

        webClient.getAbs("https://packages.fhir.org/" + packageId + "/" + version)
                .send()
                .onSuccess(res -> {
                    if (res.statusCode() == 200) {
                        byte[] igBytes = res.body().getBytes();
                        igService.registerIg(packageId, version, packageId, igBytes, List.of())
                                .onSuccess(v -> {
                                    logger.info("Downloaded and registered IG: {}@{}", packageId, version);
                                    ctx.json(new JsonObject()
                                            .put("status", "success")
                                            .put("name", packageId)
                                            .put("version", version));
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
}
