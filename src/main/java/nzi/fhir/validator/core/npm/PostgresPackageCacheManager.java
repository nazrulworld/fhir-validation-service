package nzi.fhir.validator.core.npm;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.model.IgPackageName;
import nzi.fhir.validator.core.enums.FhirCoreIgPackageType;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.PackageServer;
import io.vertx.core.buffer.Buffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * PostgresSQL-based package cache manager for FHIR IG packages.
 */
public class PostgresPackageCacheManager extends AsyncBasePackageCacheManager {
    private static final Logger logger = LogManager.getLogger(PostgresPackageCacheManager.class);
    private final Pool pgPool;

    public PostgresPackageCacheManager(Vertx vertx, Pool pgPool) {
        super(PackageServer.defaultServers(), WebClient.create(vertx));
        this.pgPool = pgPool;
    }
    public Future<NpmPackage> loadPackage(String idAndVer) {
        return loadPackage(idAndVer, true);
    }
    public Future<NpmPackage> loadPackage(String idAndVer, boolean cacheOnly) {
        return loadPackage(idAndVer, cacheOnly, false);
    }
    public Future<NpmPackage> loadPackage(String idAndVer, boolean cacheOnly, boolean loadDependencies) {
        return resolveIgPackageName(idAndVer, cacheOnly)
            .compose(igPackageName -> {
                if (igPackageName == null) {
                    logger.debug("No package name resolved for {}", idAndVer);
                    return Future.succeededFuture(null);
                }
                return loadPackage(igPackageName, cacheOnly, loadDependencies);
            });
    }
    public Future<NpmPackage> loadPackage(IgPackageName igPackageName, boolean cacheOnly) {
        return loadPackage(igPackageName, cacheOnly, false);
    }
    public Future<NpmPackage> loadPackage(IgPackageName igPackageName, boolean cacheOnly, boolean loadDependencies) {
        return loadPackageFromCache(igPackageName)
            .compose(npmPackage -> {
                if (npmPackage != null) {
                    logger.debug("Package {}#{} has been loaded from cache",
                        igPackageName.getName(), igPackageName.getVersion());
                    return Future.succeededFuture(npmPackage);
                }

                if (!cacheOnly) {
                    logger.debug("Package {}#{} not found in cache, fetching from servers",
                            igPackageName.getName(), igPackageName.getVersion());
                    return fetchFromPackageServers(igPackageName, loadDependencies);
                }

                logger.info("Package {}#{} not found in cache and cache-only mode is enabled",
                    igPackageName.getName(), igPackageName.getVersion());
                return Future.succeededFuture(null);
            });
    }


    protected Future<NpmPackage> loadPackageFromCache(IgPackageName igPackageName) {

        String query = "SELECT content_raw FROM %s.fhir_implementation_guides WHERE ig_package_id = $1 AND ig_package_version = $2".formatted(DB_POSTGRES_SCHEMA_NAME);
        return pgPool.preparedQuery(query)
                .execute(Tuple.of(igPackageName.getName(), igPackageName.getVersion()))
                .map(rows -> {
                    if (rows == null || rows.size() == 0) {
                        logger.debug("Package {}#{} not found in database", igPackageName.getName(), igPackageName.getVersion());
                        return null;
                    }
                    try {
                        byte[] igBytes = rows.iterator().next().getBuffer(0).getBytes();
                        return NpmPackage.fromPackage(new ByteArrayInputStream(igBytes));
                    } catch (Exception e) {
                        logger.error("Failed to parse package {}#{}: {}", igPackageName.getName(), igPackageName.getVersion(), e.getMessage(), e);
                        throw new RuntimeException("Failed to load package " + igPackageName.getName() + "#" + igPackageName.getVersion() + " from cache", e);
                    }
                });
    }

    public Future<NpmPackage> addPackageToCache(InputStream inputStream) {
    if (inputStream == null) {
        return Future.failedFuture(new IllegalArgumentException("Input stream cannot be null"));
    }

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        inputStream.transferTo(baos);  // Java 9+ method, more efficient
        return addPackageToCache(new ByteArrayInputStream(baos.toByteArray()));
    } catch (IOException e) {
        return Future.failedFuture(e);
    } finally {
        try {
            inputStream.close();
        } catch (IOException e) {
            logger.warn("Failed to close input stream: {}", e.getMessage());
        }
    }
}

protected Future<NpmPackage> addPackageToCache(ByteArrayInputStream stream) {
    if (stream == null) {
        return Future.failedFuture(new IllegalArgumentException("Stream cannot be null"));
    }

    try {
        // Read the stream only once
        byte[] packageBytes = stream.readAllBytes();  // Java 9+ method
        
        NpmPackage npmPackage = NpmPackage.fromPackage(new ByteArrayInputStream(packageBytes));
        JsonObject packageMeta = createNpmPackageMeta(npmPackage);
        String[] dependenciesArray = npmPackage.dependencies().toArray(String[]::new);

        String sql = """
            INSERT INTO %s.fhir_implementation_guides 
                (ig_package_id, ig_package_version, ig_package_meta, content_raw, dependencies)
            VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (ig_package_id, ig_package_version) 
            DO UPDATE SET
                ig_package_meta = $3,
                content_raw = $4,
                dependencies = $5
            """.formatted(DB_POSTGRES_SCHEMA_NAME);

        return pgPool.withTransaction(client -> 
            client.preparedQuery(sql)
                .execute(Tuple.of(
                    npmPackage.name(),
                    npmPackage.version(),
                    packageMeta.encode(),
                    packageBytes,
                    dependenciesArray
                ))
                .map(v -> {
                    logger.info("Cached package {}#{}", npmPackage.name(), npmPackage.version());
                    return npmPackage;
                })
                .onFailure(e -> 
                    logger.error("Failed to cache package {}#{}: {}", 
                        npmPackage.name(), 
                        npmPackage.version(), 
                        e.getMessage(), 
                        e)
                )
        );
    } catch (Exception e) {
        return Future.failedFuture(e);
    }
}

    private Future<NpmPackage> fetchFromPackageServers(IgPackageName igPackageName ) {
        return fetchFromPackageServers(igPackageName, false);
    }
    private Future<NpmPackage> fetchFromPackageServers(IgPackageName igPackageName, boolean shouldResolveDependencies) {
        return fetchPackage(igPackageName).compose(npmPackage -> {
            if (npmPackage == null) {
                return Future.succeededFuture(null);  // Changed from return null to proper Future
            }

            if (!shouldResolveDependencies) {
                return Future.succeededFuture(npmPackage);
            }

            List<Future> dependencyFutures = new ArrayList<>();
            List<Future<Boolean>> existChecks = new ArrayList<>();

            for (String dependency : npmPackage.dependencies()) {
                IgPackageName dependencyIgPackageName = IgPackageName.fromIdAndVersion(dependency);
                if (FhirCoreIgPackageType.getNameList().contains(dependencyIgPackageName.getName())) {
                    continue;
                }

                Future<Boolean> existCheck = isPackageExists(dependencyIgPackageName)
                    .compose(exists -> {
                        if (!exists) {
                            Future<NpmPackage> fetchFuture = fetchFromPackageServers(dependencyIgPackageName, true);
                            dependencyFutures.add(fetchFuture);
                        }
                        return Future.succeededFuture(exists);
                    });
                existChecks.add(existCheck);
            }

            // First, wait for all exist checks to complete
            return CompositeFuture.all(new ArrayList<>(existChecks))
                .compose(existResults -> {
                    if (dependencyFutures.isEmpty()) {
                        return Future.succeededFuture(npmPackage);
                    }
                    // Then wait for all dependency fetches to complete
                    return CompositeFuture.all(dependencyFutures)
                        .map(v -> npmPackage)
                        .otherwise(err -> {
                            logger.error("Failed to fetch dependencies for {}: {}",
                                igPackageName.getName(),
                                err.getMessage());
                            return npmPackage;
                        });
                });
        });
    }

    private Future<NpmPackage> fetchPackage(IgPackageName igPackageName) {
        // Add tracking of successful fetch
        AtomicBoolean hasSucceeded = new AtomicBoolean(false);
        
        List<Future<NpmPackage>> futures = getPackageServers().stream()
            .map(server -> {
                String packageUrl = Utilities.pathURL(server.getUrl(),
                    igPackageName.getName(),
                    igPackageName.getVersion());

                return webClient.getAbs(packageUrl)
                    .send()
                    .compose(response -> {
                        if (response.statusCode() != 200) {
                            // Only log as error if we haven't succeeded yet
                            if (!hasSucceeded.get()) {
                                logger.warn("Failed to fetch from {}: HTTP {}", 
                                    server.getUrl(), response.statusCode());
                            }
                            return Future.succeededFuture(null);
                        }
                        hasSucceeded.set(true);

                        try {
                            Buffer body = response.body();
                            if (body == null || body.length() == 0) {
                                logger.warn("Empty response body from {}", packageUrl);
                                return Future.succeededFuture(null);
                            }
                            logger.debug("Fetched package from {}: {}", packageUrl, body.length());
                            ByteArrayInputStream inputStream = new ByteArrayInputStream(body.getBytes());
                            return addPackageToCache(inputStream)
                                .onFailure(err -> {
                                    logger.error("Failed to cache package from {}: {}",
                                        packageUrl,
                                        err.getMessage());
                                });
                        } catch (Exception e) {
                            logger.error("Error processing response from {}: {}",
                                packageUrl,
                                e.getMessage());
                            return Future.failedFuture(e);
                        }
                    })
                    .onFailure(err -> {
                        // Only log as an error if we haven't succeeded yet
                        if (!hasSucceeded.get()) {
                            logger.error("Failed to fetch package: {}", err.getMessage());
                        } else {
                            logger.debug("Additional fetch attempt failed (already succeeded from another server): {}",
                                err.getMessage());
                        }
                    });
            })
            .collect(Collectors.toList());

        if (futures.isEmpty()) {
            logger.warn("No package servers configured for {}#{}",
                igPackageName.getName(),
                igPackageName.getVersion());
            return Future.succeededFuture(null);
        }

        @SuppressWarnings("rawtypes")
        List<Future> rawFutures = new ArrayList<>(futures);
        return CompositeFuture.any(rawFutures)
            .map(composite -> {
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        NpmPackage npm = composite.resultAt(i);
                        if (npm != null) {
                            logger.debug("Successfully fetched package {}#{} from server #{}", 
                                igPackageName.getName(), 
                                igPackageName.getVersion(), 
                                i + 1);
                            return npm;
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to get result from server #{}: {}", 
                            i + 1, 
                            e.getMessage());
                    }
                }
                logger.warn("Failed to fetch package {}#{} from any server", 
                    igPackageName.getName(), 
                    igPackageName.getVersion());
                return null;
            });
    }

    public static Future<String> getLatestVersionFromCache(Pool pgPool, String lgPackageId) {
        Validate.notNull(lgPackageId, "Package id cannot be null");
        String query = "SELECT ig_package_version FROM %s.fhir_implementation_guides WHERE ig_package_id = $1 ORDER BY created_at DESC LIMIT 1".formatted(DB_POSTGRES_SCHEMA_NAME);
        return pgPool.preparedQuery(query)
                .execute(Tuple.of(lgPackageId))
                .map(rows -> {
                    if (rows == null || rows.size() == 0) {
                        return null;
                    }
                    return rows.iterator().next().getString(0);
                });
    }

    public Future<Void> clear() {
        return pgPool.preparedQuery("DELETE FROM %s.fhir_implementation_guides".formatted(DB_POSTGRES_SCHEMA_NAME))
                .execute()
                .mapEmpty();
    }
    public Future<Boolean> isPackageExists(IgPackageName igPackageName) {
        return pgPool.preparedQuery(
            "SELECT EXISTS(SELECT 1 FROM %s.fhir_implementation_guides WHERE ig_package_id = $1 AND ig_package_version = $2)".formatted(DB_POSTGRES_SCHEMA_NAME))
        .execute(Tuple.of(igPackageName.getName(), igPackageName.getVersion()))
        .map(rows -> rows.iterator().next().getBoolean(0));

    }

    public Future<Void> removePackage(String id, String version) {
        return pgPool.preparedQuery("DELETE FROM %s.fhir_implementation_guides WHERE ig_package_id = $1 AND ig_package_version = $2".formatted(DB_POSTGRES_SCHEMA_NAME))
                .execute(Tuple.of(id, version))
                .mapEmpty();
    }

    public static JsonObject createNpmPackageMeta(NpmPackage npmPackage) {
        JsonObject meta = new JsonObject()
                .put("version", npmPackage.version())
                .put("canonical", npmPackage.canonical())
                .put("name", npmPackage.name())
                .put("url", npmPackage.url())
                .put("fhirVersion", npmPackage.fhirVersion());

        org.hl7.fhir.utilities.json.model.JsonArray fhirVersions = npmPackage.getNpm().getJsonArray("fhirVersions");
        if (fhirVersions == null) {
            fhirVersions = npmPackage.getNpm().getJsonArray("fhir-version-list");
        }
        if (fhirVersions != null) {
            JsonArray fhirVersionsArray = new JsonArray(fhirVersions.toString());
            meta.put("fhirVersions", fhirVersionsArray);
        }
        return meta;
    }

    public Future<List<NpmPackage>> loadPackageWithDependencies(String idAndVersion) {
        List<NpmPackage> loadedPackages = new ArrayList<>();

        return loadPackage(idAndVersion, true)
                .compose(mainPackage -> {
                    if (mainPackage == null) {
                        return Future.succeededFuture(loadedPackages);
                    }

                    loadedPackages.add(mainPackage);
                    List<String> dependencies = mainPackage.dependencies();

                    if (dependencies == null || dependencies.isEmpty()) {
                        return Future.succeededFuture(loadedPackages);
                    }

                    List<Future<NpmPackage>> dependencyFutures = dependencies.stream()
                            .map(IgPackageName::fromIdAndVersion)
                            .filter(depPackage -> !isCorePackage(depPackage))
                            .map(depPackage -> loadPackage(depPackage, true))
                            .collect(Collectors.toList());

                    return Future.all(dependencyFutures)
                            .map(compositeFuture -> {
                                compositeFuture.list().stream()
                                        .filter(Objects::nonNull)
                                        .forEach(pkg -> loadedPackages.add((NpmPackage) pkg));
                                return loadedPackages;
                            });
                })
                .otherwiseEmpty();
    }

    private boolean isCorePackage(IgPackageName packageName) {
        return FhirCoreIgPackageType.getNameList().contains(packageName.getName());
    }

    public Future<IgPackageName> resolveIgPackageName(String idAndVersion) {
        return resolveIgPackageName(idAndVersion, true);
    }
    public Future<IgPackageName> resolveIgPackageName(String idAndVersion, boolean cacheOnly) {
        String id = idAndVersion.contains("#") ? idAndVersion.substring(0, idAndVersion.indexOf("#")) : idAndVersion;
        String version = idAndVersion.contains("#") ? idAndVersion.substring(idAndVersion.indexOf("#") + 1) : null;

        // If a specific version is provided, and it's not "latest"
        if (version != null && !version.equals("latest")) {
            logger.debug("Package {}#{} has been resolved directly", id, version);
            return Future.succeededFuture(new IgPackageName(id, version));
        }

        // Try to get the latest version from the cache first
        return getLatestVersionFromCache(pgPool, id)
            .compose(latestVersion -> {
                if (latestVersion != null) {
                    logger.debug("Package {}#{} has been resolved from cache", id, latestVersion);
                    return Future.succeededFuture(new IgPackageName(id, latestVersion));
                }

                // If cache-only mode is disabled, try to get from server
                if (!cacheOnly) {
                    return getLatestVersion(id)
                        .map(latestVersionFromServer -> {
                            if (latestVersionFromServer != null) {
                                logger.debug("Package {}#{} has been resolved from server", id, latestVersionFromServer);
                                return new IgPackageName(id, latestVersionFromServer);
                            }
                            logger.warn("No version found for package {}", id);
                            return null;
                        });
                }

                // Cache-only mode and nothing found in cache
                logger.info("Package {} not found in cache and cache-only mode is enabled", id);
                return Future.succeededFuture(null);
            });
    }

}