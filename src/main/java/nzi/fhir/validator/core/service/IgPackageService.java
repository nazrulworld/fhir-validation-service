package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import io.vertx.sqlclient.SqlClient;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.DependencyResolver;
import nzi.fhir.validator.core.npm.PackageVersionComparator;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Service for managing FHIR Implementation Guide packages.
 */
public class IgPackageService {
    private static final Logger logger = LogManager.getLogger(IgPackageService.class);
    static final long DEFAULT_LOCAL_APPLY_WAIT_MS = 10_000;

    private final Vertx vertx;
    private final Pool pgPool;
    private final PostgresPackageCacheManager cacheManager;

    private Function<SupportedFhirVersion, Future<Void>> applyLocally = v -> Future.succeededFuture();
    private volatile long localApplyWaitMs = DEFAULT_LOCAL_APPLY_WAIT_MS;

    private IgPackageService(Vertx vertx, Pool pgPool, PostgresPackageCacheManager cacheManager) {
        this.vertx = vertx;
        this.pgPool = pgPool;
        this.cacheManager = cacheManager;
    }

    /**
     * How long {@link #include} waits for this instance to apply a committed change before answering anyway, so a
     * long validator rebuild never turns a committed include into a request timeout (FR-020).
     */
    public void setLocalApplyWaitMs(long waitMs) {
        this.localApplyWaitMs = waitMs;
    }

    /**
     * @param applyLocally brings this instance up to the latest committed configuration of a version
     */
    public void setApplyLocally(Function<SupportedFhirVersion, Future<Void>> applyLocally) {
        this.applyLocally = applyLocally;
    }

    public PostgresPackageCacheManager cacheManager() {
        return cacheManager;
    }

    /**
     * Includes a stored IG package in the (global) validator configuration of {@code fhirVersion}, replacing any
     * other version of the same package. Resolution and the revision bump run in one transaction with the
     * configuration row locked, so concurrent includes serialise and every instance converges on one result.
     * Completes after the change is applied on this instance, or after {@code localApplyWaitMs} with a warning while
     * the rebuild continues in the background; other instances follow via notification/poll.
     *
     * @param requestedVersion an exact version, or {@code latest}/empty for the highest stored release
     * @return {@code {fhirVersion, revision, included, resolved, warnings}}
     */
    static final String NOT_APPLIED_WARNING = "Committed; this instance has not applied it yet, see /health";
    static final String STILL_APPLYING_WARNING = "Committed; this instance is still rebuilding its validator, see /health";

    public Future<JsonObject> include(SupportedFhirVersion fhirVersion, String name, String requestedVersion) {
        if (name == null || name.isBlank()) {
            return Future.failedFuture(new ProblemException(400, "invalid-request", "igPackageId is required"));
        }
        if (requestedVersion != null && !requestedVersion.isBlank() && !"latest".equalsIgnoreCase(requestedVersion)
                && !PostgresPackageCacheManager.isValidVersionLabel(requestedVersion)) {
            return Future.failedFuture(new ProblemException(400, "invalid-request",
                    "igPackageVersion must be 1-64 characters without whitespace"));
        }
        List<String> warnings = new ArrayList<>();
        return pgPool.withTransaction(tx -> loadCatalog(tx).compose(catalog -> {
                    Optional<String> concrete = requestedVersion == null || requestedVersion.isBlank()
                            || "latest".equalsIgnoreCase(requestedVersion)
                            ? PackageVersionComparator.max(catalog.versions(name))
                            : catalog.versions(name).contains(requestedVersion) ? Optional.of(requestedVersion) : Optional.empty();
                    if (concrete.isEmpty()) {
                        String label = requestedVersion == null || requestedVersion.isBlank() ? name : name + "#" + requestedVersion;
                        return Future.failedFuture(new ProblemException(404, "package-not-found",
                                "IG package " + label + " is not stored. Register it first via /igs/upload or /igs/register"));
                    }
                    String key = name + "#" + concrete.get();
                    return ConfigRevisionRepository.lockForUpdate(tx, fhirVersion).compose(row -> {
                        Set<String> included = new TreeSet<>();
                        row.included().stream().filter(p -> !p.startsWith(name + "#")).forEach(included::add);
                        included.add(key);
                        DependencyResolver.Resolution resolution = DependencyResolver.resolve(included, catalog);
                        warnings.addAll(resolution.warnings());
                        List<String> includedList = List.copyOf(included);
                        return ConfigRevisionRepository.updateIgSet(tx, fhirVersion, includedList, resolution.resolved(), key)
                                .map(revision -> new JsonObject()
                                        .put("status", "success")
                                        .put("fhirVersion", fhirVersion.name())
                                        .put("revision", revision)
                                        .put("included", new JsonArray(includedList))
                                        .put("resolved", new JsonArray(resolution.resolved())));
                    });
                }))
                .compose(result -> awaitLocal(applyLocally.apply(fhirVersion), warnings)
                        .recover(err -> {
                            // The cause goes to the log (with the request id); clients get curated text (Principle IV).
                            logger.warn("Included IG {}#{} in {} is committed but not applied locally yet", name,
                                    requestedVersion, fhirVersion, err);
                            warnings.add(NOT_APPLIED_WARNING);
                            return Future.succeededFuture();
                        })
                        .map(v -> result.put("warnings", new JsonArray(warnings))))
                .onSuccess(r -> logger.info("Included IG {}#{} in {} (revision {})", name, requestedVersion, fhirVersion, r.getLong("revision")));
    }

    /**
     * {@code applied}, or success with a "still applying" warning once {@code localApplyWaitMs} has passed.
     */
    private Future<Void> awaitLocal(Future<Void> applied, List<String> warnings) {
        if (applied.isComplete()) {
            return applied;
        }
        Promise<Void> answer = Promise.promise();
        long timer = vertx.setTimer(localApplyWaitMs, id -> {
            if (!answer.future().isComplete()) {
                // Add the warning first: completing the promise renders the response synchronously.
                warnings.add(STILL_APPLYING_WARNING);
                answer.tryComplete();
            }
        });
        applied.onComplete(ar -> {
            vertx.cancelTimer(timer);
            if (ar.succeeded()) {
                answer.tryComplete();
            } else {
                answer.tryFail(ar.cause());
            }
        });
        return answer.future();
    }

    /**
     * Name, version and declared dependencies of every stored package (metadata only, no archives).
     */
    public static Future<DependencyResolver.Catalog> loadCatalog(SqlClient client) {
        return client.query("SELECT ig_package_id, ig_package_version, dependencies FROM %s.fhir_implementation_guides"
                        .formatted(DB_POSTGRES_SCHEMA_NAME))
                .execute()
                .map(rows -> {
                    Map<String, List<String>> packages = new HashMap<>();
                    rows.forEach(row -> {
                        String[] deps = row.getArrayOfStrings(2);
                        packages.put(row.getString(0) + "#" + row.getString(1), deps == null ? List.of() : List.of(deps));
                    });
                    return DependencyResolver.Catalog.of(packages);
                });
    }

    public static IgPackageService create(Vertx vertx, Pool pgPool) {
        PostgresPackageCacheManager cacheManager = new PostgresPackageCacheManager(vertx, pgPool);
        return new IgPackageService(vertx, pgPool, cacheManager);
    }

    public static IgPackageService create(Vertx vertx, Pool pgPool, PostgresPackageCacheManager cacheManager) {
        return new IgPackageService(vertx, pgPool, cacheManager);
    }

    public Future<NpmPackage> registerIg(String name, String version) {
        return cacheManager.loadPackage(name +"#"+version, false, false)
                .onSuccess(npmPackage -> logger.info("Successfully registered IG: {}#{}", name, version))
                .onFailure(e -> logger.error("Failed to register IG: {}#{} - {}", name, version, e.getMessage()));
    }

    public Future<NpmPackage> registerIg(String name, String version, boolean loadDependencies) {
        return cacheManager.loadPackage(name +"#"+version, false, loadDependencies)
                .onSuccess(npmPackage -> logger.info("Successfully registered IG: {}#{}", name, version))
                .onFailure(e -> logger.error("Failed to register IG: {}#{} - {}", name, version, e.getMessage()));
    }

    public Future<NpmPackage> registerIg(byte[] igPackageBytes) {
        return registerIg(igPackageBytes, false);
    }

    public Future<NpmPackage> registerIg(byte[] igPackageBytes, boolean loadDependencies) {
        return registerIg(igPackageBytes, loadDependencies, "UPLOAD");
    }

    /**
     * Stores a package archive (parsed off the event loop) and optionally fetches its dependencies from the
     * configured registries.
     *
     * @param source {@code UPLOAD} or {@code URL}
     */
    public Future<NpmPackage> registerIg(byte[] igPackageBytes, boolean loadDependencies, String source) {
        if (igPackageBytes == null) {
            return Future.failedFuture(new ProblemException(400, "invalid-request", "No package content"));
        }
        return cacheManager.store(igPackageBytes, source).compose(npmPackage -> {
            if (!loadDependencies || npmPackage.dependencies() == null || npmPackage.dependencies().isEmpty()) {
                return Future.succeededFuture(npmPackage);
            }
            List<Future<?>> dependencyFutures = npmPackage.dependencies().stream()
                    .filter(dependency -> !DependencyResolver.isCore(dependency.contains("#") ? dependency.substring(0, dependency.indexOf('#')) : dependency))
                    .<Future<?>>map(dependency -> cacheManager.loadPackage(dependency, false, true))
                    .collect(Collectors.toList());
            return Future.join(dependencyFutures)
                    .transform(ar -> {
                        if (ar.failed()) {
                            logger.warn("Some dependencies of {}#{} could not be fetched: {}", npmPackage.name(), npmPackage.version(), ar.cause().getMessage());
                        }
                        return Future.succeededFuture(npmPackage);
                    });
        });
    }

    /**
     * Stores an uploaded archive file; it is checked on disk before being read into memory (FR-014).
     */
    public Future<NpmPackage> registerUploadedFile(String path) {
        return cacheManager.storeFile(path, "UPLOAD");
    }

    public Future<NpmPackage> loadIgPackage(String name, String version) {
        if (name == null || version == null) {
            return Future.failedFuture("IG name and version cannot be null");
        }
        return cacheManager.loadPackage(name +"#"+version, true)
                .onFailure(e -> logger.error("Failed to load IG package {}#{}: {}", 
                    name, version, e.getMessage()));
    }

    /**
     * Declared dependencies of a stored package; 404 problem if it is not stored.
     */
    public Future<JsonObject> getDependencyGraph(String name, String version) {
        return pgPool.preparedQuery("SELECT dependencies FROM %s.fhir_implementation_guides ".formatted(DB_POSTGRES_SCHEMA_NAME)
                        + "WHERE ig_package_id = $1 AND ig_package_version = $2")
                .execute(Tuple.of(name, version))
                .compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.failedFuture(notStored(name, version));
                    }
                    String[] deps = rows.iterator().next().getArrayOfStrings(0);
                    return Future.succeededFuture(new JsonObject()
                            .put("name", name)
                            .put("version", version)
                            .put("dependencies", new JsonArray(deps == null ? List.of() : List.of(deps))));
                });
    }

    /**
     * File listing of a stored package (the deprecated "conformance" operation); 404 problem if it is not stored.
     */
    public Future<JsonObject> listPackageFiles(String name, String version) {
        return cacheManager.loadRawPackage(name, version)
                .compose(raw -> raw == null ? Future.failedFuture(notStored(name, version)) : cacheManager.parse(raw.getBytes()))
                .compose(pkg -> {
                    try {
                        return Future.succeededFuture(new JsonObject()
                                .put("name", name)
                                .put("version", version)
                                .put("files", new JsonArray(pkg.listResources())));
                    } catch (IOException e) {
                        return Future.failedFuture(e);
                    }
                });
    }

    private static ProblemException notStored(String name, String version) {
        return new ProblemException(404, "package-not-found", "IG package " + name + "#" + version + " is not stored");
    }

}