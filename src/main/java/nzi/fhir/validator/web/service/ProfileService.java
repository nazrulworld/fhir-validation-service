package nzi.fhir.validator.web.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.Tuple;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.instance.model.api.IBaseResource;

public class ProfileService {
    private static final String PROFILE_CACHE_PREFIX = "profile:";
    private static final long CACHE_TTL_SECONDS = 3600;
    private static final Logger logger = LogManager.getLogger(ProfileService.class);

    private final Vertx vertx;
    private final FhirContext fhirContext;
    private final IParser jsonParser;
    private final RedisAPI redis;
    private final Pool pgPool;

    public ProfileService(Vertx vertx, String fhirVersion, RedisAPI redis, Pool pgPool) {
        this.vertx = vertx;
        this.fhirContext = "R5".equalsIgnoreCase(fhirVersion) ? FhirContext.forR5() : FhirContext.forR4();
        this.jsonParser = fhirContext.newJsonParser();
        this.redis = redis;
        this.pgPool = pgPool;
        initializeDatabase();
    }

    private void initializeDatabase() {
        pgPool.query(
                "CREATE TABLE IF NOT EXISTS fhir_profiles (" +
                        "    id SERIAL PRIMARY KEY," +
                        "    url TEXT NOT NULL UNIQUE," +
                        "    profile_json JSONB NOT NULL," +
                        "    fhir_version TEXT NOT NULL," +
                        "    created_at TIMESTAMPTZ DEFAULT NOW()" +
                        ")"
        ).execute().onFailure(e -> logger.error("Failed to initialize database: {}", e.getMessage(), e));
    }

    public Future<IBaseResource> getProfile(String profileUrl) {
        if (profileUrl == null || profileUrl.isEmpty()) {
            logger.error("Profile URL is null or empty");
            return Future.failedFuture("Profile URL cannot be null or empty");
        }

        String cacheKey = PROFILE_CACHE_PREFIX + profileUrl.replaceAll("[^a-zA-Z0-9:]", "_");
        logger.debug("Fetching profile: {}", profileUrl);

        return redis.get(cacheKey)
                .compose(redisRes -> {
                    if (redisRes != null) {
                        logger.debug("Cache hit for profile: {}", profileUrl);
                        return Future.succeededFuture(jsonParser.parseResource(redisRes.toString()));
                    }
                    logger.debug("Cache miss for profile: {}", profileUrl);
                    return loadFromDatabase(profileUrl)
                            .compose(profile -> {
                                String profileJson = jsonParser.encodeResourceToString(profile);
                                return redis.setex(cacheKey, String.valueOf(CACHE_TTL_SECONDS), profileJson)
                                        .map(profile);
                            });
                });
    }

    public Future<Void> registerProfile(String profileUrl, JsonObject profile) {
        if (profileUrl == null || profileUrl.isEmpty() || profile == null || profile.isEmpty()) {
            logger.error("Invalid profile URL or profile JSON");
            return Future.failedFuture("Profile URL and profile JSON cannot be null or empty");
        }

        String cacheKey = PROFILE_CACHE_PREFIX + profileUrl.replaceAll("[^a-zA-Z0-9:]", "_");
        String profileJson = profile.encode();
        String fhirVersion = fhirContext.getVersion().getVersion().name();

        return pgPool.preparedQuery(
                        "INSERT INTO fhir_profiles (url, profile_json, fhir_version)" +
                                " VALUES ($1, $2, $3)" +
                                " ON CONFLICT (url) DO UPDATE SET" +
                                "     profile_json = $2, fhir_version = $3"
                )
                .execute(Tuple.of(profileUrl, profileJson, fhirVersion))
                .compose(res -> redis.setex(cacheKey, String.valueOf(CACHE_TTL_SECONDS), profileJson)
                        .map(v -> (Void) null))
                .onSuccess(v -> logger.info("Registered profile: {}", profileUrl))
                .onFailure(e -> logger.error("Failed to register profile: {}", e.getMessage(), e));
    }

    private Future<IBaseResource> loadFromDatabase(String profileUrl) {
        return pgPool.preparedQuery(
                        "SELECT profile_json FROM fhir_profiles WHERE url = $1"
                )
                .execute(Tuple.of(profileUrl))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        logger.warn("Profile not found in database: {}", profileUrl);
                        return Future.failedFuture("Profile not found: " + profileUrl);
                    }
                    String profileJson = rows.iterator().next().getJsonObject(0).encode();
                    return Future.succeededFuture(jsonParser.parseResource(profileJson));
                });
    }
}
