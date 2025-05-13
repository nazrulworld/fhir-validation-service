package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import nzi.fhir.validator.web.service.FhirValidationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ValidationApi {
    private static final Logger logger = LogManager.getLogger(ValidationApi.class);
    private final FhirValidationService validationService;

    public ValidationApi(Router router, Vertx vertx, FhirValidationService validationService) {
        this.validationService = validationService;

        router.post("/validate")
                .handler(BodyHandler.create())
                .handler(ctx -> {
                    JsonObject request = ctx.body().asJsonObject();
                    if (request == null) {
                        logger.error("Request body is null");
                        ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Request body is null").encode());
                        return;
                    }
                    JsonObject resource = request.getJsonObject("resource");
                    JsonObject options = request.getJsonObject("options", new JsonObject());
                    String version = ctx.queryParams().get("version") != null ? ctx.queryParams().get("version").toUpperCase() : "R4";

                    if (resource == null) {
                        logger.error("Missing resource in request");
                        ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Missing resource").encode());
                        return;
                    }

                    validationService.validate(resource, options)
                            .onSuccess(result -> {
                                logger.info("Validation completed for resource");
                                ctx.json(result);
                            })
                            .onFailure(err -> {
                                logger.error("Validation failed: {}", err.getMessage(), err);
                                ctx.response().setStatusCode(400).end(new JsonObject().put("error", err.getMessage()).encode());
                            });
                });
    }
}