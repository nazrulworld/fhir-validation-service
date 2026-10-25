package nzi.fhir.validator.web;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
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
                    routerBuilder.rootHandler(new InFlightHandler(services.inFlight()));
                    CorsConfig.create(services.config()).ifPresent(routerBuilder::rootHandler);
                    routerBuilder.rootHandler(BodyHandler.create()
                            .setUploadsDirectory(uploadDirectory())
                            .setBodyLimit(services.config().httpMaxBodyMb() * 1024L * 1024L)
                            .setDeleteUploadedFilesOnEnd(true));
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
