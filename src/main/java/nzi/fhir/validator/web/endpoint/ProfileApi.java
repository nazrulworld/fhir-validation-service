package nzi.fhir.validator.web.endpoint;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import nzi.fhir.validator.web.service.ProfileService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.HashMap;

/**
 * @author Md Nazrul Islam
 */
public class ProfileApi {
    private static final Logger logger = LogManager.getLogger(ProfileApi.class);
    private final HashMap<String, ProfileService> profileServices;

    /**
     * Constructor for production use that initializes profile services internally.
     * 
     * @param vertx The Vert.x instance
     * @param pgPool The PostgresSQL connection pool
     */
    public ProfileApi(Vertx vertx, Pool pgPool) {
        // Initialize profile services for all supported FHIR versions
        this.profileServices = new HashMap<>();

        // Create FhirContext instances for different FHIR versions
        FhirContext fhirContextR4 = FhirContext.forR4();
        FhirContext fhirContextR4B = FhirContext.forR4B();
        FhirContext fhirContextR5 = FhirContext.forR5();

        // Create profile services for different FHIR versions
        this.profileServices.put("R4",  ProfileService.create(vertx, fhirContextR4, pgPool));
        this.profileServices.put("R4B", ProfileService.create(vertx, fhirContextR4B, pgPool));
        this.profileServices.put("R5",  ProfileService.create(vertx, fhirContextR5, pgPool));
    }

    /**
     * Constructor for testing purposes that allows injection of profile services.
     * 
     * @param vertx The Vert.x instance
     * @param profileServices The pre-initialized profile services
     */
    public ProfileApi(Vertx vertx, HashMap<String, ProfileService> profileServices) {
        this.profileServices = profileServices;
    }


    /**
     * Configures the routes for profile API endpoints.
     * 
     * @param router The Vert.x router to configure
     */
    public void includeRoutes(Router router) {
        router.post("/:version/register-profile")
                .handler(BodyHandler.create())
                .handler(this::handleRegistration);
    }

    private void handleRegistration(RoutingContext ctx) {
        JsonObject profile = ctx.body().asJsonObject();

        // Extract a version from a path parameter
        String versionParam = ctx.pathParam("version").toUpperCase();

        // Validate the version parameter
        if (!SupportedFhirVersion.isValid(versionParam)) {
            logger.error("Invalid FHIR version: {}", versionParam);
            ctx.response().setStatusCode(400).end(new JsonObject()
                .put("error", "Invalid FHIR version: " + versionParam + ". Supported versions are: " + 
                     java.util.Arrays.toString(SupportedFhirVersion.values())).encode());
            return;
        }

        // Convert to enum for type safety
        SupportedFhirVersion versionEnum = SupportedFhirVersion.valueOf(versionParam);
        String version = versionEnum.name();

        // Get the appropriate profile service based on the version
        ProfileService service = profileServices.getOrDefault(version, profileServices.get(SupportedFhirVersion.getDefault().name()));
        if (service == null) {
            logger.error("No profile service available for version: {}", version);
            ctx.response().setStatusCode(400).end(new JsonObject()
                .put("error", "Unsupported FHIR version: " + version).encode());
            return;
        }

        service.registerProfile(profile)
                .onSuccess(res -> {
                    logger.info("Profile registered: using version: {}", version);
                    ctx.response()
                       .setStatusCode(200)
                       .end(new JsonObject()
                               .put("status", "success")
                               .put("profileUrl", profile.getString("url"))
                           .encode());
                })
                .onFailure(err -> {
                    logger.error("Failed to register profile: {}", err.getMessage(), err);
                    ctx.response()
                       .setStatusCode(400)
                       .end(new JsonObject()
                           .put("status", "error")
                           .put("error", err.getMessage())
                           .encode());
        });
    }
}