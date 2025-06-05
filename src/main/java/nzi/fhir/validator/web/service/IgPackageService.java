package nzi.fhir.validator.web.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.npm.PostgresPackageCacheManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Service for managing FHIR Implementation Guide packages.
 */
public class IgPackageService {
    private static final Logger logger = LogManager.getLogger(IgPackageService.class);
    private final Pool pgPool;
    private final PostgresPackageCacheManager cacheManager;

    private IgPackageService(Vertx vertx, Pool pgPool, PostgresPackageCacheManager cacheManager) {
        this.pgPool = pgPool;
        this.cacheManager = cacheManager;
    }

    public static IgPackageService create(Vertx vertx, Pool pgPool) {
        PostgresPackageCacheManager cacheManager = new PostgresPackageCacheManager(vertx, pgPool);
        return new IgPackageService(vertx, pgPool, cacheManager);
    }

    public static IgPackageService create(Vertx vertx, Pool pgPool, PostgresPackageCacheManager cacheManager) {
        return new IgPackageService(vertx, pgPool, cacheManager);
    }

    public Future<NpmPackage> registerIg(String name, String version) {
        return cacheManager.loadPackage(name +"#"+version, false)
                .onSuccess(npmPackage -> logger.info("Successfully registered IG: {}#{}", name, version))
                .onFailure(e -> logger.error("Failed to register IG: {}#{} - {}", name, version, e.getMessage()));
    }

    public Future<NpmPackage> registerIg(byte[] igPackageBytes) {
        NpmPackage npmPackage;
        try {
            npmPackage = NpmPackage.fromPackage(new ByteArrayInputStream(igPackageBytes));
        } catch (Exception e) {
            logger.error("Invalid IG package: {}", e.getMessage(), e);
            return Future.failedFuture("Invalid IG package: " + e.getMessage());
        }

        JsonObject packageMeta = PostgresPackageCacheManager.createNpmPackageMeta(npmPackage);
        String[] dependenciesArray = npmPackage.dependencies().toArray(String[]::new);

        return pgPool.withTransaction(client -> 
            client.preparedQuery(
                "INSERT INTO fhir_implementation_guides " +
                "(ig_package_id, ig_package_version, ig_package_meta, content_raw, dependencies) " +
                "VALUES ($1, $2, $3, $4, $5) " +
                "ON CONFLICT (ig_package_id, ig_package_version) DO UPDATE SET " +
                "ig_package_meta = $3, content_raw = $4, dependencies = $5"
            )
            .execute(Tuple.of(
                npmPackage.name(),
                npmPackage.version(),
                packageMeta.encode(),
                igPackageBytes,
                dependenciesArray
            ))
            .map(rowSet -> npmPackage)
        )
        .onSuccess(v -> logger.info("Registered IG: {}#{}", npmPackage.name(), npmPackage.version()))
        .onFailure(e -> logger.error("Failed to register IG: {}#{} - {}", 
            npmPackage.name(), npmPackage.version(), e.getMessage()));
    }

    public Future<NpmPackage> loadIgPackage(String name, String version) {
        if (name == null || version == null) {
            return Future.failedFuture("IG name and version cannot be null");
        }
        return cacheManager.loadPackage(name +"#"+version, true)
                .onFailure(e -> logger.error("Failed to load IG package {}#{}: {}", 
                    name, version, e.getMessage()));
    }

    public Future<JsonObject> getDependencyGraph(String name, String version) {
        if (name == null || version == null) {
            return Future.failedFuture("IG name and version cannot be null");
        }

        return pgPool.preparedQuery(
            "SELECT dependencies FROM fhir_implementation_guides " +
            "WHERE ig_package_id = $1 AND ig_package_version = $2"
        )
        .execute(Tuple.of(name, version))
        .map(rows -> {
            JsonArray deps = new JsonArray();
            if (rows.size() > 0) {
                Object[] dependencies = (Object[]) rows.iterator().next().getValue("dependencies");
                if (dependencies != null) {
                    for (Object dep : dependencies) {
                        if (dep instanceof String) {
                            deps.add((String) dep);
                        }
                    }
                }
            }
            return new JsonObject()
                    .put("name", name)
                    .put("version", version)
                    .put("dependencies", deps);
        })
        .onFailure(e -> logger.error("Failed to get dependency graph for {}#{}: {}", 
            name, version, e.getMessage()));
    }

    public Future<JsonObject> generateConformanceReport(String name, String version) {
        if (name == null || version == null) {
            return Future.failedFuture("IG name and version cannot be null");
        }

        return loadIgPackage(name, version)
            .compose(ig -> {
                try {
                    JsonArray resources = new JsonArray(ig.listResources().stream()
                        .map(res -> new JsonObject().put("resource", res))
                        .collect(Collectors.toList()));
                
                return Future.succeededFuture(new JsonObject()
                    .put("name", name)
                    .put("version", version)
                    .put("resources", resources)
                    .put("conformance", "basic"));
            } catch (IOException e) {
                logger.error("Failed to list resources for {}#{}: {}", 
                    name, version, e.getMessage());
                return Future.failedFuture(e);
            }
        })
        .onFailure(e -> logger.error("Failed to generate conformance report for {}#{}: {}", 
            name, version, e.getMessage()));
    }
}