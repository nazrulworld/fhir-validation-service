package nzi.fhir.validator.web.service;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.web.config.RedisConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class IgService {
    private static final Logger logger = LogManager.getLogger(IgService.class);
    private final Pool pgPool;
    private final RedisAPI redis;
    private final WebClient webClient;

    public IgService(Vertx vertx, Pool pgPool, RedisAPI redis) {
        this.pgPool = pgPool;
        this.redis = redis;
        this.webClient = WebClient.create(vertx);
        initializeDatabase();
    }

    private void initializeDatabase() {
        pgPool.query(
                "CREATE TABLE IF NOT EXISTS fhir_implementation_guides (" +
                        "    id SERIAL PRIMARY KEY," +
                        "    name TEXT NOT NULL," +
                        "    version TEXT NOT NULL," +
                        "    package_id TEXT NOT NULL," +
                        "    ig_bytea BYTEA NOT NULL," +
                        "    dependencies JSONB," +
                        "    created_at TIMESTAMPTZ DEFAULT NOW()," +
                        "    UNIQUE(name, version)" +
                        ")"
        ).execute().onFailure(e -> logger.error("Failed to initialize database: {}", e.getMessage(), e));
    }

    public Future<Void> registerIg(String name, String version, String packageId, byte[] igBytes, List<String> dependencies) {
        if (name == null || version == null || packageId == null || igBytes == null) {
            logger.error("Invalid IG parameters");
            return Future.failedFuture("IG parameters cannot be null");
        }
        try {
            NpmPackage.fromPackage(new ByteArrayInputStream(igBytes)); // Validate IG
        } catch (Exception e) {
            logger.error("Invalid IG package: {}", e.getMessage(), e);
            return Future.failedFuture("Invalid IG package: " + e.getMessage());
        }

        return pgPool.preparedQuery(
                        "INSERT INTO fhir_implementation_guides" +
                                " (name, version, package_id, ig_bytea, dependencies)" +
                                " VALUES ($1, $2, $3, $4, $5)" +
                                " ON CONFLICT (name, version) DO UPDATE SET" +
                                "     package_id = $3, ig_bytea = $4, dependencies = $5"
                )
                .execute(Tuple.of(name, version, packageId, igBytes, new JsonArray(dependencies).toString()))
                .compose(res -> invalidateCache(name, version))
                .map(v -> (Void) null)
                .onSuccess(v -> logger.info("Registered IG: {}@{}", name, version))
                .onFailure(e -> logger.error("Failed to register IG: {}", e.getMessage(), e));
    }

    public Future<NpmPackage> loadIg(String name, String version) {
        if (name == null || version == null) {
            logger.error("Invalid IG name or version");
            return Future.failedFuture("IG name and version cannot be null");
        }
        String cacheKey = String.format("ig:%s:%s", name, version);

        return redis.get(cacheKey)
                .compose(cached -> {
                    if (cached != null) {
                        logger.debug("Cache hit for IG: {}@{}", name, version);
                        try {
                            return Future.succeededFuture(
                                    NpmPackage.fromPackage(new ByteArrayInputStream(cached.toBuffer().getBytes())));
                        } catch (Exception e) {
                            logger.error("Failed to parse cached IG: {}", e.getMessage(), e);
                            return Future.failedFuture(e);
                        }
                    }
                    return fetchFromDatabase(name, version)
                            .compose(ig -> cacheIg(cacheKey, ig));
                });
    }

    public Future<List<NpmPackage>> loadMultipleIgs(List<String> igReferences) {
        if (igReferences == null) {
            logger.error("IG references cannot be null");
            return Future.failedFuture("IG references cannot be null");
        }
        List<Future<NpmPackage>> futures = igReferences.stream()
                .map(ref -> {
                    String[] parts = ref.split("@");
                    return loadIg(parts[0], parts.length > 1 ? parts[1] : "latest");
                })
                .collect(Collectors.toList());

        return Future.all(futures)
                .map(results -> futures.stream()
                        .map(Future::result)
                        .collect(Collectors.toList()));
    }

    public Future<JsonObject> getDependencyGraph(String name, String version) {
        return loadIg(name, version)
                .compose(ig -> {
                    // Create a simplified dependency graph since getDependencies() is not available
                    JsonObject graph = new JsonObject()
                            .put("name", name)
                            .put("version", version)
                            .put("dependencies", new JsonArray());
                    return Future.succeededFuture(graph);
                })
                .onFailure(e -> logger.error("Failed to get dependency graph: {}", e.getMessage(), e));
    }

    public Future<JsonObject> generateConformanceReport(String name, String version) {
        return loadIg(name, version)
                .compose(ig -> {
                    try {
                        JsonObject report = new JsonObject()
                                .put("name", name)
                                .put("version", version)
                                .put("resources", new JsonArray(ig.listResources().stream()
                                        .map(res -> new JsonObject().put("resource", res))
                                        .collect(Collectors.toList())))
                                .put("conformance", "basic"); // Simplified; extend as needed
                        return Future.succeededFuture(report);
                    } catch (IOException e) {
                        return Future.failedFuture(e);
                    }
                })
                .onFailure(e -> logger.error("Failed to generate conformance report: {}", e.getMessage(), e));
    }

    private Future<NpmPackage> fetchFromDatabase(String name, String version) {
        return pgPool.preparedQuery(
                        "SELECT ig_bytea FROM fhir_implementation_guides" +
                                " WHERE name = $1 AND version = $2"
                )
                .execute(Tuple.of(name, version))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        logger.warn("IG not found: {}@{}", name, version);
                        return Future.failedFuture("IG not found: " + name + "@" + version);
                    }
                    try {
                        return Future.succeededFuture(
                                NpmPackage.fromPackage(
                                        new ByteArrayInputStream(
                                                rows.iterator().next().getBuffer(0).getBytes())));
                    } catch (Exception e) {
                        logger.error("Failed to parse IG from database: {}", e.getMessage(), e);
                        return Future.failedFuture(e);
                    }
                });
    }

    private Future<NpmPackage> cacheIg(String cacheKey, NpmPackage npm) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            npm.save(outputStream);
            byte[] packageBytes = outputStream.toByteArray();
            return redis.setex(cacheKey, "3600", Buffer.buffer(packageBytes).toString())
                    .map(v -> npm)
                    .onSuccess(v -> logger.debug("Cached IG: {}", cacheKey))
                    .onFailure(e -> logger.error("Failed to cache IG: {}", e.getMessage(), e));
        } catch (Exception e) {
            logger.error("Failed to serialize IG: {}", e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }

    private Future<Void> invalidateCache(String name, String version) {
        return redis.del(List.of(
                        "ig:" + name + ":" + version,
                        "ig:" + name + ":latest"
                ))
                .map(v -> (Void) null)
                .onSuccess(v -> logger.debug("Invalidated cache for IG: {}@{}", name, version))
                .onFailure(e -> logger.error("Failed to invalidate cache: {}", e.getMessage(), e));
    }
}
