package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Custom profiles of one FHIR version. Registration stores the profile and bumps the configuration revision in one
 * transaction (other instances are notified on commit), then applies the change locally before answering, so the
 * very next validation on this instance uses it (B1, B2). The local apply can queue behind a validator rebuild, so
 * the answer waits for it at most {@code localApplyWaitMs} and then reports the change as still being applied (FR-020).
 */
public class ProfileService {
    private static final Logger logger = LogManager.getLogger(ProfileService.class);
    static final String NOT_APPLIED_WARNING = "Committed; this instance has not applied it yet, see /health";
    static final String STILL_APPLYING_WARNING = "Committed; this instance is still applying it, see /health";

    /**
     * Outcome of a registration: the configuration revision that includes it, plus curated warnings.
     */
    public record Registration(long revision, List<String> warnings) {
    }

    private final Vertx vertx;
    private final Pool pgPool;
    private final Executors executors;
    private final ProfileStore profileStore;
    private volatile Function<SupportedFhirVersion, Future<Void>> applyLocally;
    private volatile long localApplyWaitMs;

    /**
     * @param applyLocally     brings this instance up to the latest committed configuration of a version
     * @param localApplyWaitMs how long a registration waits for {@code applyLocally} before answering anyway
     */
    public ProfileService(Vertx vertx, Pool pgPool, Executors executors, ProfileStore profileStore,
                          Function<SupportedFhirVersion, Future<Void>> applyLocally, long localApplyWaitMs) {
        this.vertx = vertx;
        this.pgPool = pgPool;
        this.executors = executors;
        this.profileStore = profileStore;
        this.applyLocally = applyLocally;
        this.localApplyWaitMs = localApplyWaitMs;
    }

    public void setApplyLocally(Function<SupportedFhirVersion, Future<Void>> applyLocally) {
        this.applyLocally = applyLocally;
    }

    public void setLocalApplyWaitMs(long waitMs) {
        this.localApplyWaitMs = waitMs;
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
     * @return the configuration revision that includes the change, with warnings if it is not applied locally yet
     */
    public Future<Registration> registerProfile(JsonObject profile) {
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
                    List<String> warnings = new ArrayList<>();
                    return LocalApplyWait.await(vertx, applyLocally.apply(version()), localApplyWaitMs,
                                    () -> warnings.add(STILL_APPLYING_WARNING))
                            .recover(err -> {
                                // The cause goes to the log; clients get curated text (Principle IV).
                                logger.warn("Profile {} stored but not yet applied locally", url, err);
                                warnings.add(NOT_APPLIED_WARNING);
                                return Future.succeededFuture();
                            })
                            .map(v -> new Registration(revision, List.copyOf(warnings)));
                });
    }
}
