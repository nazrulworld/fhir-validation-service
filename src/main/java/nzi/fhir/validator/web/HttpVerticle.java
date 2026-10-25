package nzi.fhir.validator.web;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.openapi.RouterBuilderOptions;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.web.endpoint.HealthApi;
import nzi.fhir.validator.web.endpoint.IgPackageApi;
import nzi.fhir.validator.web.endpoint.MetricsApi;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * HTTP front end: builds the router from {@code openapi.yaml} and serves it. Deployed once per event loop
 * ({@code FV_HTTP_INSTANCES}); all instances share the services in {@link ServiceRegistry}.
 *
 * @author Md Nazrul Islam
 */
public class HttpVerticle extends AbstractVerticle {
    private static final Logger logger = LogManager.getLogger(HttpVerticle.class);
    private static final String UPLOAD_DIR_NAME = "ig-uploads";
    private static final String UPLOAD_PATH = "/igs/upload";

    private final ServiceRegistry services;
    private HttpServer server;

    public HttpVerticle(ServiceRegistry services) {
        this.services = services;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        RouterBuilder.create(vertx, "openapi.yaml")
                .compose(routerBuilder -> {
                    routerBuilder.setOptions(new RouterBuilderOptions()
                            .setMountResponseContentTypeHandler(true)
                            .setRequireSecurityHandlers(false));
                    routerBuilder.rootHandler(new RequestIdHandler());
                    routerBuilder.rootHandler(new InFlightHandler(services.inFlight(), services.refusingNewRequests()));
                    routerBuilder.rootHandler(new RequestTimeoutHandler(services.config().httpRequestTimeoutMs()));
                    CorsConfig.create(services.config()).ifPresent(routerBuilder::rootHandler);
                    routerBuilder.rootHandler(bodyHandler());
                    routerBuilder.rootHandler(services.auth().rootHandler());

                    services.validationApi().includeRoutes(routerBuilder);
                    services.profileApi().includeRoutes(routerBuilder);
                    new IgPackageApi(services.igPackageService(), services.fetcher()).includeRoutes(routerBuilder);
                    new HealthApi(routerBuilder, vertx, services.healthService());
                    new MetricsApi(routerBuilder, services.metrics());

                    Router router = routerBuilder.createRouter();
                    ErrorResponder.install(router);
                    return vertx.createHttpServer()
                            .requestHandler(router)
                            .listen(services.config().httpPort());
                })
                .onSuccess(s -> {
                    server = s;
                    services.setBoundPort(s.actualPort());
                    logger.info("HTTP server listening on port {}", s.actualPort());
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    public int actualPort() {
        return server == null ? -1 : server.actualPort();
    }

    /**
     * Request bodies are capped at {@code FV_HTTP_MAX_BODY_MB}, except IG uploads, which may be as large as
     * {@code FV_PACKAGE_MAX_COMPRESSED_MB} plus multipart overhead so oversized archives get {@code package-too-large}
     * from the archive check rather than a generic 413 (FR-014).
     */
    private Handler<RoutingContext> bodyHandler() {
        String uploads = uploadDirectory();
        long mib = 1024L * 1024L;
        BodyHandler body = BodyHandler.create().setUploadsDirectory(uploads)
                .setBodyLimit(services.config().httpMaxBodyMb() * mib).setDeleteUploadedFilesOnEnd(true);
        long packageLimit = services.config().packageMaxCompressedMb() * mib;
        long uploadLimit = Math.max(services.config().httpMaxBodyMb() * mib, packageLimit + mib);
        BodyHandler upload = BodyHandler.create().setUploadsDirectory(uploads)
                .setBodyLimit(uploadLimit).setDeleteUploadedFilesOnEnd(true);
        return ctx -> {
            if (!UPLOAD_PATH.equals(ctx.normalizedPath())) {
                body.handle(ctx);
                return;
            }
            // Refuse a declared oversized upload before reading it, with the package-specific problem type.
            String length = ctx.request().getHeader("Content-Length");
            if (length != null && length.matches("\\d{1,18}") && Long.parseLong(length) > uploadLimit) {
                ctx.response().putHeader("Connection", "close");
                ErrorResponder.fail(ctx, new ProblemException(413, "package-too-large",
                        "The archive exceeds " + packageLimit / mib + " MB"));
                return;
            }
            ctx.put(ErrorResponder.TOO_LARGE_TYPE, "package-too-large");
            upload.handle(ctx);
        };
    }

    private static String uploadDirectory() {
        Path uploadDir = Paths.get(System.getProperty("java.io.tmpdir"), UPLOAD_DIR_NAME);
        try {
            Files.createDirectories(uploadDir);
            return uploadDir.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize upload directory " + uploadDir, e);
        }
    }
}
