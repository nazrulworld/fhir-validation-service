package nzi.fhir.validator.web.endpoint;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.openapi.RouterBuilder;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.ProfileService;
import nzi.fhir.validator.web.ErrorResponder;
import nzi.fhir.validator.web.ProblemException;

import java.util.Map;

/**
 * Registration of custom profiles, one {@link ProfileService} per enabled FHIR version.
 *
 * @author Md Nazrul Islam
 */
public class ProfileApi {
    private final Map<SupportedFhirVersion, ProfileService> profileServices;

    public ProfileApi(Map<SupportedFhirVersion, ProfileService> profileServices) {
        this.profileServices = Map.copyOf(profileServices);
    }

    /**
     * Configures the routes for profile API endpoints.
     *
     * @param routerBuilder The Vert.x router to configure
     */
    public void includeRoutes(RouterBuilder routerBuilder) {
        // Method: POST, Path: "/:version/register-profile"
        routerBuilder.operation("profileApiRegisterProfile")
                .handler(this::handleRegistration);
    }

    private void handleRegistration(RoutingContext ctx) {
        String versionParam = ctx.pathParam("version");
        ProfileService service = SupportedFhirVersion.parseEnabled(versionParam).map(profileServices::get).orElse(null);
        if (service == null) {
            ErrorResponder.fail(ctx, ProblemException.unsupportedVersion(versionParam));
            return;
        }
        JsonObject profile;
        try {
            profile = ctx.body().asJsonObject();
        } catch (RuntimeException e) {
            ErrorResponder.fail(ctx, new ProblemException(400, "invalid-request", "The body must be a JSON object"));
            return;
        }
        Future<JsonObject> result = service.registerProfile(profile)
                .map(revision -> new JsonObject()
                        .put("status", "success")
                        .put("profileUrl", profile.getString("url"))
                        .put("revision", revision));
        ErrorResponder.respond(ctx, result);
    }
}
