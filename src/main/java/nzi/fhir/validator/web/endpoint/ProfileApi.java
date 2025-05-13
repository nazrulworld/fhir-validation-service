package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import nzi.fhir.validator.web.service.ProfileService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ProfileApi {
    private static final Logger logger = LogManager.getLogger(ProfileApi.class);
    private final ProfileService profileService;

    public ProfileApi(Router router, Vertx vertx, ProfileService profileService) {
        this.profileService = profileService;

        router.post("/register-profile")
                .handler(BodyHandler.create())
                .handler(ctx -> {
                    JsonObject request = ctx.body().asJsonObject();
                    if (request == null) {
                        logger.error("Request body is null");
                        ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Request body is null").encode());
                        return;
                    }
                    String profileUrl = request.getString("url");
                    JsonObject profile = request.getJsonObject("profile");

                    if (profileUrl == null || profile == null) {
                        logger.error("Missing url or profile in request");
                        ctx.response().setStatusCode(400).end(new JsonObject().put("error", "Missing url or profile").encode());
                        return;
                    }

                    profileService.registerProfile(profileUrl, profile)
                            .onSuccess(res -> {
                                logger.info("Profile registered: {}", profileUrl);
                                ctx.json(new JsonObject().put("status", "success").put("profileUrl", profileUrl));
                            })
                            .onFailure(err -> {
                                logger.error("Failed to register profile: {}", err.getMessage(), err);
                                ctx.response().setStatusCode(500).end(new JsonObject().put("error", err.getMessage()).encode());
                            });
                });
    }
}