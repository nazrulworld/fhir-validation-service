package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * IG package storage in PostgreSQL plus retrieval from package registries.
 * <ul>
 *     <li>Archives are checked ({@link PackageArchiveGuard}), decompressed and parsed on a worker pool, never on the
 *     event loop (P2).</li>
 *     <li>"latest" is the highest version by {@link PackageVersionComparator}, not the most recent upload (B7).</li>
 *     <li>Registry downloads go through {@link SafePackageFetcher} (F1). With several registries, a package is fetched
 *     if any registry has it: the first non-empty answer wins and it is stored once (B9, P6). Unreachable registries
 *     are ignored when looking up the latest version (B19).</li>
 * </ul>
 */
public class PostgresPackageCacheManager {
    private static final Logger logger = LogManager.getLogger(PostgresPackageCacheManager.class);
    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".fhir_implementation_guides";
    private static final int MAX_VERSION_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = 127;

    private final Pool pgPool;
    private final WorkerExecutor parser;
    private final PackageArchiveGuard guard;
    private final SafePackageFetcher fetcher;
    private final List<String> registryUrls;
    private final boolean registryEnabled;

    /**
     * Storage only (no registry access), default archive limits, a private parser pool.
     */
    public PostgresPackageCacheManager(Vertx vertx, Pool pgPool) {
        this(pgPool, vertx.createSharedWorkerExecutor(Executors.PACKAGES_POOL, 2, 30, TimeUnit.MINUTES),
                PackageArchiveGuard.defaults(), null, List.of(), false);
    }

    /**
     * @param parser          worker pool for checking, decompressing and parsing archives
     * @param guard           limits every archive is checked against before it is parsed or stored
     * @param fetcher         SSRF-safe downloader used for registry fetches
     * @param registryUrls    registry base URLs, queried in parallel
     * @param registryEnabled {@code false} = offline, stored packages only
     */
    public PostgresPackageCacheManager(Pool pgPool, WorkerExecutor parser, PackageArchiveGuard guard,
                                       SafePackageFetcher fetcher, List<String> registryUrls, boolean registryEnabled) {
        this.pgPool = pgPool;
        this.parser = parser;
        this.guard = guard;
        this.fetcher = fetcher;
        this.registryUrls = List.copyOf(registryUrls);
        this.registryEnabled = registryEnabled && fetcher != null;
    }

    // --- storage ------------------------------------------------------------------------------------------------

    /**
     * Decompresses and parses a package archive on the package worker pool.
     */
    public Future<NpmPackage> parse(byte[] bytes) {
        return parser.executeBlocking(() -> {
            try {
                return NpmPackage.fromPackage(new ByteArrayInputStream(bytes));
            } catch (Exception e) {
                throw new ProblemException(400, "invalid-request", "The content is not a valid FHIR NPM package (.tgz)");
            }
        }, false);
    }

    /**
     * Checks, parses (off the event loop) and stores a package archive, replacing a stored package with the same
     * name and version.
     *
     * @param source {@code UPLOAD}, {@code REGISTRY} or {@code URL}
     */
    public Future<NpmPackage> store(byte[] bytes, String source) {
        return parser.<Void>executeBlocking(() -> {
            guard.check(bytes);
            return null;
        }, false).compose(v -> storeChecked(bytes, source));
    }

    /**
     * Stores an archive file (an upload): size and archive checks run on the file before it is read into memory.
     */
    public Future<NpmPackage> storeFile(String path, String source) {
        return parser.<byte[]>executeBlocking(() -> {
            Path file = Path.of(path);
            long size = Files.size(file);
            if (size > guard.maxCompressedBytes()) {
                throw new ProblemException(413, "package-too-large", "The archive exceeds "
                        + guard.maxCompressedBytes() / (1024 * 1024) + " MB");
            }
            try (InputStream in = Files.newInputStream(file)) {
                guard.check(in, size);
            }
            return Files.readAllBytes(file);
        }, false).compose(bytes -> storeChecked(bytes, source));
    }

    private Future<NpmPackage> storeChecked(byte[] bytes, String source) {
        return parse(bytes).compose(npmPackage -> {
            if (npmPackage.name() == null || npmPackage.name().isBlank() || npmPackage.name().length() > MAX_NAME_LENGTH) {
                return Future.failedFuture(new ProblemException(400, "invalid-request", "The package has no valid name"));
            }
            if (!isValidVersionLabel(npmPackage.version())) {
                return Future.failedFuture(new ProblemException(400, "invalid-request",
                        "The package version label must be 1-" + MAX_VERSION_LENGTH + " characters without whitespace"));
            }
            byte[] sha256;
            try {
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (NoSuchAlgorithmException e) {
                return Future.failedFuture(e);
            }
            String[] dependencies = npmPackage.dependencies() == null ? new String[0] : npmPackage.dependencies().toArray(String[]::new);
            String sql = """
                INSERT INTO %s
                    (ig_package_id, ig_package_version, ig_package_meta, content_raw, dependencies, content_sha256, source, updated_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, now())
                ON CONFLICT (ig_package_id, ig_package_version)
                DO UPDATE SET ig_package_meta = $3, content_raw = $4, dependencies = $5, content_sha256 = $6,
                              source = $7, updated_at = now()
                """.formatted(TABLE);
            return pgPool.preparedQuery(sql)
                    .execute(Tuple.of(npmPackage.name(), npmPackage.version(), createNpmPackageMeta(npmPackage),
                            Buffer.buffer(bytes), dependencies, Buffer.buffer(sha256), source))
                    .map(rows -> {
                        logger.info("Stored package {}#{} ({} bytes, source {})", npmPackage.name(), npmPackage.version(), bytes.length, source);
                        return npmPackage;
                    });
        });
    }

    public Future<NpmPackage> addPackageToCache(InputStream inputStream) {
        if (inputStream == null) {
            return Future.failedFuture(new IllegalArgumentException("Input stream cannot be null"));
        }
        try (inputStream) {
            return store(inputStream.readAllBytes(), "UPLOAD");
        } catch (IOException e) {
            return Future.failedFuture(e);
        }
    }

    public static boolean isValidVersionLabel(String version) {
        return version != null && !version.isEmpty() && version.length() <= MAX_VERSION_LENGTH && !version.matches(".*\\s.*");
    }

    /**
     * The stored package archive, or {@code null} when it is not stored. Performs no parsing.
     */
    public Future<Buffer> loadRawPackage(String name, String version) {
        return pgPool.preparedQuery("SELECT content_raw FROM " + TABLE + " WHERE ig_package_id = $1 AND ig_package_version = $2")
                .execute(Tuple.of(name, version))
                .map(rows -> rows.size() == 0 ? null : rows.iterator().next().getBuffer(0));
    }

    public Future<Boolean> isPackageExists(String name, String version) {
        return pgPool.preparedQuery("SELECT EXISTS(SELECT 1 FROM " + TABLE + " WHERE ig_package_id = $1 AND ig_package_version = $2)")
                .execute(Tuple.of(name, version))
                .map(rows -> rows.iterator().next().getBoolean(0));
    }

    public Future<Void> removePackage(String name, String version) {
        return pgPool.preparedQuery("DELETE FROM " + TABLE + " WHERE ig_package_id = $1 AND ig_package_version = $2")
                .execute(Tuple.of(name, version))
                .mapEmpty();
    }

    public Future<Void> clear() {
        return pgPool.query("DELETE FROM " + TABLE).execute().mapEmpty();
    }

    /**
     * The highest stored version of a package by {@link PackageVersionComparator} (releases before pre-releases),
     * or {@code null}.
     */
    public static Future<String> getLatestVersionFromCache(Pool pgPool, String packageId) {
        return pgPool.preparedQuery("SELECT ig_package_version FROM " + TABLE + " WHERE ig_package_id = $1")
                .execute(Tuple.of(packageId))
                .map(rows -> {
                    List<String> versions = new ArrayList<>();
                    for (Row row : rows) {
                        versions.add(row.getString(0));
                    }
                    return PackageVersionComparator.max(versions).orElse(null);
                });
    }

    // --- retrieval ----------------------------------------------------------------------------------------------

    /**
     * A stored package (no registry access); {@code null} if not stored.
     */
    public Future<NpmPackage> loadPackage(String idAndVersion) {
        return loadPackage(idAndVersion, true, false);
    }

    public Future<NpmPackage> loadPackage(String idAndVersion, boolean cacheOnly) {
        return loadPackage(idAndVersion, cacheOnly, false);
    }

    /**
     * A package from storage, or (unless {@code cacheOnly}) from the registries, then stored. {@code latest} or no
     * version resolves to the highest known version. Completes with {@code null} if no source has the package.
     *
     * @param loadDependencies also fetch missing (non-core) dependencies, recursively
     */
    public Future<NpmPackage> loadPackage(String idAndVersion, boolean cacheOnly, boolean loadDependencies) {
        return loadPackage(idAndVersion, cacheOnly, loadDependencies, new HashSet<>());
    }

    private Future<NpmPackage> loadPackage(String idAndVersion, boolean cacheOnly, boolean loadDependencies, Set<String> visited) {
        String name = idAndVersion.contains("#") ? idAndVersion.substring(0, idAndVersion.indexOf('#')) : idAndVersion;
        String requested = idAndVersion.contains("#") ? idAndVersion.substring(idAndVersion.indexOf('#') + 1) : null;
        return resolveVersion(name, requested, cacheOnly).compose(version -> {
            if (version == null || !visited.add(name + "#" + version)) {
                return Future.succeededFuture(null);
            }
            return loadRawPackage(name, version)
                    .compose(raw -> {
                        if (raw != null) {
                            return parse(raw.getBytes());
                        }
                        return cacheOnly || !registryEnabled ? Future.succeededFuture(null) : fetchFromRegistries(name, version);
                    })
                    .compose(pkg -> pkg == null || !loadDependencies || pkg.dependencies() == null
                            ? Future.succeededFuture(pkg)
                            : loadDependencies(pkg, visited).map(pkg));
        });
    }

    private Future<Void> loadDependencies(NpmPackage pkg, Set<String> visited) {
        List<Future<?>> deps = new ArrayList<>();
        for (String dependency : pkg.dependencies()) {
            String depName = dependency.contains("#") ? dependency.substring(0, dependency.indexOf('#')) : dependency;
            if (DependencyResolver.isCore(depName)) {
                continue;
            }
            deps.add(loadPackage(dependency, false, true, visited)
                    .recover(err -> {
                        logger.warn("Dependency {} of {}#{} could not be loaded: {}", dependency, pkg.name(), pkg.version(), err.getMessage());
                        return Future.succeededFuture();
                    }));
        }
        return Future.join(deps).mapEmpty();
    }

    /**
     * Resolves {@code null}/{@code latest}/{@code current} to the highest stored version, else (unless cacheOnly)
     * the highest version any registry lists. Exact versions are returned as given.
     */
    public Future<String> resolveVersion(String name, String requested, boolean cacheOnly) {
        if (requested != null && !requested.isBlank() && !requested.equals("latest") && !requested.equals("current")) {
            return Future.succeededFuture(requested);
        }
        return getLatestVersionFromCache(pgPool, name).compose(stored -> {
            if (stored != null || cacheOnly || !registryEnabled) {
                return Future.succeededFuture(stored);
            }
            return latestFromRegistries(name);
        });
    }

    private Future<NpmPackage> fetchFromRegistries(String name, String version) {
        if (registryUrls.isEmpty()) {
            return Future.succeededFuture(null);
        }
        Promise<Buffer> first = Promise.promise();
        // Completed on the first success: the requests still waiting on other registries are reset.
        Promise<Void> cancelOthers = Promise.promise();
        AtomicInteger pending = new AtomicInteger(registryUrls.size());
        for (String registry : registryUrls) {
            fetcher.fetch(registry + "/" + name + "/" + version, cancelOthers.future()).onComplete(ar -> {
                if (ar.succeeded() && ar.result() != null && ar.result().length() > 0) {
                    if (first.tryComplete(ar.result())) {
                        cancelOthers.tryComplete();
                    }
                } else if (ar.failed() && !(ar.cause() instanceof ProblemException p && p.status() == 404)) {
                    logger.info("Registry {} could not provide {}#{}: {}", registry, name, version, ar.cause().getMessage());
                }
                if (pending.decrementAndGet() == 0) {
                    first.tryComplete(null);
                    cancelOthers.tryComplete();
                }
            });
        }
        return first.future().compose(bytes -> bytes == null ? Future.succeededFuture(null) : store(bytes.getBytes(), "REGISTRY"));
    }

    private Future<String> latestFromRegistries(String name) {
        if (registryUrls.isEmpty()) {
            return Future.succeededFuture(null);
        }
        List<Future<Optional<String>>> answers = new ArrayList<>();
        for (String registry : registryUrls) {
            answers.add(fetcher.fetch(registry + "/" + name)
                    .map(body -> latestFromListing(body.toJsonObject()))
                    .recover(err -> Future.succeededFuture(Optional.empty())));
        }
        return Future.join(answers).map(cf -> {
            List<String> versions = new ArrayList<>();
            answers.forEach(a -> a.result().ifPresent(versions::add));
            return PackageVersionComparator.max(versions).orElse(null);
        });
    }

    private static Optional<String> latestFromListing(JsonObject listing) {
        JsonObject tags = listing.getJsonObject("dist-tags");
        if (tags != null && tags.getString("latest") != null) {
            return Optional.of(tags.getString("latest"));
        }
        JsonObject versions = listing.getJsonObject("versions");
        return versions == null ? Optional.empty() : PackageVersionComparator.max(versions.fieldNames());
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
            meta.put("fhirVersions", new JsonArray(fhirVersions.toString()));
        }
        return meta;
    }
}
