package nzi.fhir.validator.web.service;

import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.*;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.exceptions.FHIRFormatError;
import org.hl7.fhir.instance.model.api.IBaseResource;

public class ProfileService {
    private static final String PROFILE_CACHE_PREFIX = "profile:";
    private static final Logger logger = LogManager.getLogger(ProfileService.class);

    private final Vertx vertx;
    private final FhirContext fhirContext;
    private final IParser jsonParser;
    private final Pool pgPool;
    private static final CachingService cachingService;

    static {
        cachingService = CachingService.create();
    }

    private ProfileService(Vertx vertx, FhirContext fhirContext, Pool pgPool) {
        this.vertx = vertx;
        this.fhirContext = fhirContext;
        this.jsonParser = fhirContext.newJsonParser();
        this.pgPool = pgPool;
    }

    public static ProfileService create(Vertx vertx, FhirContext fhirContext, Pool pgPool){
        return new ProfileService(vertx, fhirContext, pgPool);
    }

    public Future<IBaseResource> getProfile(String profileUrl) {
        if (profileUrl == null || profileUrl.isEmpty()) {
            logger.error("Profile URL is null or empty");
            return Future.failedFuture("Profile URL cannot be null or empty");
        }

        String cacheKey = getCacheKey(profileUrl) ;
        logger.debug("Fetching profile: {}", profileUrl);
        IBaseResource profile = (IBaseResource) cachingService.get(cacheKey);
        if (profile != null) {
            logger.debug("Cache hit for profile: {}", profileUrl);
            return Future.succeededFuture(profile);
        }
        return loadFromDatabase(profileUrl)
                .compose(profileRaw -> {
                    cachingService.put(cacheKey, profileRaw);
                    return Future.succeededFuture(profileRaw);
                });
    }

    public Future<Void> registerProfile(JsonObject profile) {
        try {
            validateProfile(profile);
        } catch (DataFormatException e){
            logger.error("Failed to parse profile JSON: {}", e.getMessage(), e);
            return Future.failedFuture("Failed to parse profile: " + e.getMessage());
        }
        catch (FHIRException e) {
            logger.error("Failed to validate profile JSON: {}", e.getMessage(), e);
            return Future.failedFuture("Failed to validate profile: " + e.getMessage());
        }

        String profileUrl = profile.getString("url");
        String cacheKey = getCacheKey(profileUrl);
        
        return pgPool.withTransaction(client -> {
            String profileJson = profile.encode();
            String fhirVersion = fhirContext.getVersion().getVersion().getFhirVersionString();

            return client.preparedQuery(
                    "INSERT INTO fhir_profiles (url, profile_json, fhir_version)" +
                            " VALUES ($1, $2, $3)" +
                            " ON CONFLICT (url, fhir_version) DO UPDATE SET " +
                            " profile_json = $2, modified_at = NOW()"
            )
            .execute(Tuple.of(profileUrl, profileJson, fhirVersion))
            .onSuccess(v -> {
                logger.info("Registered profile in transaction: {}", profileUrl);
                cachingService.remove(cacheKey);
            })
            .mapEmpty(); // Convert RowSet to Void since we don't need the result
        });
    }

    public Future<Void> registerProfiles(JsonObject[] profiles) {
        return pgPool.withTransaction(client -> {
            Future<Void> compositeFuture = Future.succeededFuture();
            
            for (JsonObject profile : profiles) {
                try {
                    validateProfile(profile);
                } catch (FHIRException e) {
                    logger.error("Failed to validate profile JSON: {}", e.getMessage(), e);
                    return Future.failedFuture("Failed to validate profile: " + e.getMessage());
                }
                String profileUrl = profile.getString("url");

                compositeFuture = compositeFuture.compose(v -> {
                    String profileJson = profile.encode();
                    String fhirVersion = fhirContext.getVersion().getVersion().getFhirVersionString();

                    return client.preparedQuery(
                            "INSERT INTO fhir_profiles (url, profile_json, fhir_version)" +
                                    " VALUES ($1, $2, $3)" +
                                    " ON CONFLICT (url, fhir_version) DO UPDATE SET" +
                                    " profile_json = $2, modified_at = NOW()"
                    )
                    .execute(Tuple.of(profileUrl, profileJson, fhirVersion))
                    .map(result -> {
                        logger.info("Registered profile in batch: {}", profileUrl);
                        cachingService.remove(getCacheKey(profileUrl));
                        return null;
                    });
                });
            }
            
            return compositeFuture;
        });
    }

    private Future<IBaseResource> loadFromDatabase(String profileUrl) {
        return pgPool.withTransaction(client -> 
            client.preparedQuery(
                "SELECT profile_json FROM fhir_profiles WHERE url = $1 AND fhir_version = $2"
            )
            .execute(Tuple.of(profileUrl, fhirContext.getVersion().getVersion().getFhirVersionString()))
            .compose(rows -> {
                if (rows.size() == 0) {
                    logger.warn("Profile not found in database for URL: {} and FHIR version: {}",
                            profileUrl, fhirContext.getVersion().getVersion().getFhirVersionString());
                    return Future.failedFuture("Profile not found: " + profileUrl);
                }
                String profileJson = rows.iterator().next().getString(0);
                try {
                    return Future.succeededFuture(jsonParser.parseResource(profileJson));
                } catch (Exception e) {
                    logger.error("Failed to parse profile JSON: {}", e.getMessage(), e);
                    return Future.failedFuture("Failed to parse profile: " + e.getMessage());
                }
            })
        );
    }

    private String getCacheKey(String profileUrl) {
        return PROFILE_CACHE_PREFIX + fhirContext.getVersion().getVersion().getFhirVersionString() + "_" +profileUrl.replaceAll("[^a-zA-Z0-9:]", "_");
    }
    private void validateProfile(JsonObject profileJson) throws FHIRException {
        if (profileJson == null || profileJson.isEmpty()) {
            throw new FHIRFormatError("Profile JSON cannot be null or empty");
        }
        String profileUrl = profileJson.getString("url");
        if (profileUrl == null || profileUrl.isEmpty()) {
            logger.error("Profile URL must be existing in JSON: {}", profileJson.encode());
            throw new FHIRFormatError("Profile URL cannot be null or empty");
        }
        IBaseResource profileResource = fhirContext.newJsonParser().parseResource(profileJson.encode());
        if (!profileResource.fhirType().equals("StructureDefinition")){
            throw new FHIRFormatError("Profile JSON must be a valid FHIR StructureDefinition");
        }
    }
}