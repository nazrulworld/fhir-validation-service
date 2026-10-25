package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.ProfileStore;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.function.Function;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Custom profiles of one FHIR version. Registration stores the profile and bumps the configuration revision in one
 * transaction (other instances are notified on commit), then applies the change locally before answering, so the
 * very next validation on this instance uses it (B1, B2).
 */
public class ProfileService {
    private static final Logger logger = LogManager.getLogger(ProfileService.class);

    private final Pool pgPool;
    private final Executors executors;
    private final ProfileStore profileStore;
    private final Function<SupportedFhirVersion, Future<Void>> applyLocally;

    /**
     * @param applyLocally brings this instance up to the latest committed configuration of a version
     */
    public ProfileService(Pool pgPool, Executors executors, ProfileStore profileStore,
                          Function<SupportedFhirVersion, Future<Void>> applyLocally) {
        this.pgPool = pgPool;
        this.executors = executors;
        this.profileStore = profileStore;
        this.applyLocally = applyLocally;
    }

    public SupportedFhirVersion version() {
        return profileStore.version();
    }

    /**
     * @return the profile with exactly this canonical URL, or {@code null}
     */
    public IBaseResource getProfile(String profileUrl) {
        return profileStore.get(profileUrl);
    }

    /**
     * Validates and stores a StructureDefinition.
     *
     * @return the configuration revision that includes the change
     */
    public Future<Long> registerProfile(JsonObject profile) {
        if (profile == null || profile.isEmpty()) {
            return Future.failedFuture(new ProblemException(400, "invalid-request", "The profile body is empty"));
        }
        String url = profile.getString("url");
        if (url == null || url.isBlank()) {
            return Future.failedFuture(new ProblemException(400, "invalid-request", "The profile has no 'url'"));
        }
        String declaredVersion = profile.getString("fhirVersion");
        if (declaredVersion != null && !declaredVersion.isBlank()) {
            SupportedFhirVersion declared;
            try {
                declared = SupportedFhirVersion.fromVersionNumber(declaredVersion);
            } catch (IllegalArgumentException e) {
                return Future.failedFuture(new ProblemException(400, "invalid-request",
                        "The profile declares an unsupported fhirVersion '" + declaredVersion + "'"));
            }
            if (declared != version()) {
                return Future.failedFuture(new ProblemException(400, "invalid-request",
                        "The profile declares fhirVersion " + declaredVersion + " but was posted for " + version()));
            }
        }
        String json = profile.encode();
        FhirContext ctx = FhirContextLoader.getInstance().getContext(version());
        return executors.packages().executeBlocking(() -> {
                    IBaseResource parsed;
                    try {
                        parsed = ctx.newJsonParser().parseResource(json);
                    } catch (DataFormatException e) {
                        throw new ProblemException(400, "invalid-request", "Failed to parse profile: not a valid FHIR "
                                + version() + " resource");
                    }
                    if (!"StructureDefinition".equals(parsed.fhirType())) {
                        throw new ProblemException(400, "invalid-request", "Profile JSON must be a FHIR StructureDefinition");
                    }
                    return parsed;
                }, false)
                .compose(parsed -> pgPool.withTransaction(tx -> tx.preparedQuery(
                                "INSERT INTO %s.fhir_profiles (url, profile_json, fhir_version, modified_at) ".formatted(DB_POSTGRES_SCHEMA_NAME)
                                        + "VALUES ($1, $2, $3, now()) "
                                        + "ON CONFLICT (url, fhir_version) DO UPDATE SET profile_json = EXCLUDED.profile_json, modified_at = now()")
                        // Bind a JsonObject: a String bound to a jsonb parameter would be stored as a JSON string value.
                        .execute(Tuple.of(url, profile, profileStore.storageVersion()))
                        .compose(r -> ConfigRevisionRepository.bump(tx, version(), ConfigRevisionRepository.KIND_PROFILE, url))))
                .compose(revision -> {
                    logger.info("Registered profile {} for {} (revision {})", url, version(), revision);
                    return applyLocally.apply(version())
                            .recover(err -> {
                                logger.warn("Profile {} stored but not yet applied locally: {}", url, err.getMessage());
                                return Future.succeededFuture();
                            })
                            .map(revision);
                });
    }
}
