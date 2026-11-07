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
import io.vertx.sqlclient.SqlClient;
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
        return store(bytes, source, PackageOwnership.SYSTEM);
    }

    /**
     * Like {@link #store(byte[], String)}, owned as {@code ownership} says (spec 005 research R7).
     *
     * @return fails with 409 {@code conflict} when another organisation stored the same {@code name#version}
     */
    public Future<NpmPackage> store(byte[] bytes, String source, PackageOwnership ownership) {
        return parser.<Void>executeBlocking(() -> {
            guard.check(bytes);
            return null;
        }, false).compose(v -> storeChecked(bytes, source, ownership));
    }

    /**
     * Stores an archive file (an upload): size and archive checks run on the file before it is read into memory.
     */
    public Future<NpmPackage> storeFile(String path, String source) {
        return storeFile(path, source, PackageOwnership.SYSTEM);
    }

    /** Like {@link #storeFile(String, String)}, owned as {@code ownership} says. */
    public Future<NpmPackage> storeFile(String path, String source, PackageOwnership ownership) {
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
        }, false).compose(bytes -> storeChecked(bytes, source, ownership));
    }

    private Future<NpmPackage> storeChecked(byte[] bytes, String source, PackageOwnership ownership) {
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
            // Spec 005 research R7: a replacement keeps the stored owner and sharing, and is allowed only for the owner
            // or the system organisation. The condition sits in the upsert itself, so there is no check-then-write race:
            // no row back means another organisation stored it.
            String sql = """
                INSERT INTO %s AS t
                    (ig_package_id, ig_package_version, ig_package_meta, content_raw, dependencies, content_sha256, source,
                     expanded_bytes, updated_at, owner, is_shareable)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, now(), $9, $10)
                ON CONFLICT (ig_package_id, ig_package_version)
                DO UPDATE SET ig_package_meta = $3, content_raw = $4, dependencies = $5, content_sha256 = $6,
                              source = $7, expanded_bytes = $8, updated_at = now()
                WHERE t.owner = $9 OR $11
                RETURNING owner
                """.formatted(TABLE);
            String key = npmPackage.name() + "#" + npmPackage.version();
            // The upsert, the catalog revision bump and the notification commit together (spec 004, research R4):
            // every instance then refreshes its catalog view and invalidates contexts built from a replaced package.
            return pgPool.withTransaction(tx -> tx.preparedQuery(sql)
                            .execute(Tuple.from(new Object[]{npmPackage.name(), npmPackage.version(), createNpmPackageMeta(npmPackage),
                                    Buffer.buffer(bytes), dependencies, Buffer.buffer(sha256), source, expandedBytes(npmPackage),
                                    ownership.owner(), ownership.shareable(), ownership.callerIsSystem()}))
                            .compose(rows -> rows.size() == 0
                                    // Nothing changed: the catalog revision is not bumped, nobody is notified.
                                    ? Future.failedFuture(ProblemException.conflictOwnedElsewhere(key))
                                    : bumpCatalog(tx, key)))
                    .map(revision -> {
                        logger.info("Stored package {} ({} bytes, source {}, catalog revision {})", key, bytes.length, source, revision);
                        storeListener.accept(key);
                        return npmPackage;
                    });
        });
    }

    /**
     * Expanded size per archive byte, for packages stored before their expanded size was recorded (V5); measured
     * 9 to 13 on published IGs.
     */
    static final int LEGACY_EXPANSION_FACTOR = 10;

    /**
     * Total size of a parsed package's files, which is what a validator build holds in memory (and more).
     */
    public static long expandedBytes(NpmPackage pkg) {
        long total = 0;
        for (NpmPackage.NpmPackageFolder folder : pkg.getFolders().values()) {
            for (byte[] content : folder.getContent().values()) {
                total += content.length;
            }
        }
        return total;
    }

    /**
     * Expanded size of the stored packages {@code idAndVersions} in total, for the heap check before an include is
     * committed. Packages stored before V5 have no recorded size: their archive size times
     * {@link #LEGACY_EXPANSION_FACTOR} stands in. Packages that are not stored count as 0.
     */
    public static Future<Long> expandedBytes(SqlClient client, List<String> idAndVersions) {
        if (idAndVersions.isEmpty()) {
            return Future.succeededFuture(0L);
        }
        return client.preparedQuery("SELECT coalesce(sum(coalesce(expanded_bytes, octet_length(content_raw)::bigint * "
                        + LEGACY_EXPANSION_FACTOR + ")), 0) FROM " + TABLE
                        + " WHERE ig_package_id || '#' || ig_package_version = ANY($1)")
                .execute(Tuple.of(idAndVersions.toArray(String[]::new)))
                .map(rows -> rows.iterator().next().getLong(0));
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
        return pgPool.withTransaction(tx -> tx.preparedQuery("DELETE FROM " + TABLE + " WHERE ig_package_id = $1 AND ig_package_version = $2")
                        .execute(Tuple.of(name, version))
                        .compose(rows -> bumpCatalog(tx, name + "#" + version)))
                .onSuccess(r -> storeListener.accept(name + "#" + version))
                .mapEmpty();
    }

    public Future<Void> clear() {
        return pgPool.withTransaction(tx -> tx.query("DELETE FROM " + TABLE).execute()
                        .compose(rows -> bumpCatalog(tx, "*")))
                .onSuccess(r -> storeListener.accept("*"))
                .mapEmpty();
    }

    /** Notification kind for a package write (spec 004, contracts §7). */
    public static final String KIND_PACKAGE = "PACKAGE";

    private static final String CATALOG_TABLE = DB_POSTGRES_SCHEMA_NAME + ".fhir_package_catalog";

    /**
     * Increments the shared package catalog revision and notifies other instances; call inside the transaction that
     * writes the package, so PostgreSQL delivers the notification only on commit.
     *
     * @param key the changed {@code name#version}, {@code *}, or {@code null} for a refresh only (an organisation
     *            rename changes owner short names, spec 005 research R8)
     * @return the new catalog revision
     */
    public static Future<Long> bumpCatalog(SqlClient tx, String key) {
        return tx.query("UPDATE " + CATALOG_TABLE + " SET revision = revision + 1, updated_at = now() WHERE id = 1 RETURNING revision")
                .execute()
                .compose(rows -> {
                    long revision = rows.size() == 0 ? 0 : rows.iterator().next().getLong(0);
                    String payload = new JsonObject().put("kind", KIND_PACKAGE).put("key", key).put("revision", revision).encode();
                    return tx.preparedQuery("SELECT pg_notify($1, $2)")
                            .execute(Tuple.of(nzi.fhir.validator.core.service.ConfigRevisionRepository.CHANNEL, payload))
                            .map(revision);
                });
    }

    /**
     * Changes the owner and/or sharing of a stored package (spec 005 FR-019, research R8), in one transaction with
     * the row locked: the catalog revision is bumped and {@code PACKAGE} notified, so every instance refreshes its
     * catalog view; no validator context is rebuilt (access is checked per request). Check order: 404 (not stored, or
     * {@code caller} is neither the owner nor the system organisation) → 403 (a transfer by a caller that isn't the
     * system organisation) → 400 (body, unknown or inactive target organisation), so a refusal never reveals a
     * package the caller can't see.
     *
     * @return the package's access after the change
     */
    public Future<nzi.fhir.validator.core.security.Access> changeAccess(String name, String version,
                                                                        nzi.fhir.validator.core.security.Caller caller,
                                                                        nzi.fhir.validator.core.security.AccessChange change) {
        String key = name + "#" + version;
        return pgPool.withTransaction(tx -> tx.preparedQuery("SELECT owner FROM " + TABLE
                                + " WHERE ig_package_id = $1 AND ig_package_version = $2 FOR UPDATE")
                        .execute(Tuple.of(name, version))
                        .compose(rows -> {
                            if (rows.size() == 0 || !(caller.system() || caller.owns(rows.iterator().next().getUUID(0)))) {
                                return Future.failedFuture(ProblemException.packageNotStored(key));
                            }
                            if (change.transfers() && !caller.system()) {
                                return Future.failedFuture(ProblemException.transferForbidden());
                            }
                            if (change.invalid() != null) {
                                return Future.failedFuture(new ProblemException(400, "invalid-request", change.invalid()));
                            }
                            return resolveTargetOrganisation(tx, change);
                        })
                        .compose(newOwner -> tx.preparedQuery("UPDATE " + TABLE + " SET owner = coalesce($3, owner), "
                                        + "is_shareable = coalesce($4, is_shareable), updated_at = now() "
                                        + "WHERE ig_package_id = $1 AND ig_package_version = $2 RETURNING owner, is_shareable")
                                .execute(Tuple.of(name, version, newOwner.orElse(null), change.shareable().orElse(null))))
                        .compose(rows -> {
                            Row row = rows.iterator().next();
                            java.util.UUID owner = row.getUUID(0);
                            boolean shareable = row.getBoolean(1);
                            return bumpCatalog(tx, key)
                                    .compose(rev -> nzi.fhir.validator.core.org.OrganisationRepository.findById(tx, owner))
                                    .map(org -> new nzi.fhir.validator.core.security.Access(owner,
                                            org.map(nzi.fhir.validator.core.org.Organisation::nameShort).orElse(null), shareable));
                        }))
                .onSuccess(a -> {
                    logger.info("Changed access of package {} (owner {}, shareable {}) by {}", key, a.ownerShortName(),
                            a.shareable(), caller.label());
                    storeListener.accept(key);
                });
    }

    /**
     * The id of the organisation a change transfers to: empty when it doesn't transfer; 400 when the organisation
     * doesn't exist or is inactive.
     */
    public static Future<Optional<java.util.UUID>> resolveTargetOrganisation(SqlClient tx,
                                                                             nzi.fhir.validator.core.security.AccessChange change) {
        if (change.owner().isEmpty()) {
            return Future.succeededFuture(Optional.empty());
        }
        return nzi.fhir.validator.core.org.OrganisationRepository.findByShortName(tx, change.owner().get())
                .compose(org -> org.isPresent() && org.get().active()
                        ? Future.succeededFuture(Optional.of(org.get().id()))
                        : Future.failedFuture(new ProblemException(400, "invalid-request",
                        "The target organisation doesn't exist or is not active")));
    }

    private volatile java.util.function.Consumer<String> storeListener = key -> { };

    /** Called on this instance after a package write commits (with its {@code name#version}, or {@code *}). */
    public void setStoreListener(java.util.function.Consumer<String> listener) {
        this.storeListener = listener == null ? key -> { } : listener;
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
        return loadPackage(idAndVersion, cacheOnly, loadDependencies, PackageOwnership.SYSTEM);
    }

    /**
     * Like {@link #loadPackage(String, boolean, boolean)}: every package downloaded and stored by this call, its
     * dependencies included, is owned as {@code ownership} says (spec 005 FR-016). Packages already stored keep theirs.
     */
    public Future<NpmPackage> loadPackage(String idAndVersion, boolean cacheOnly, boolean loadDependencies,
                                          PackageOwnership ownership) {
        return loadPackage(idAndVersion, cacheOnly, loadDependencies, new HashSet<>(), ownership);
    }

    private Future<NpmPackage> loadPackage(String idAndVersion, boolean cacheOnly, boolean loadDependencies, Set<String> visited,
                                           PackageOwnership ownership) {
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
                        return cacheOnly || !registryEnabled ? Future.succeededFuture(null)
                                : fetchFromRegistries(name, version, ownership);
                    })
                    .compose(pkg -> pkg == null || !loadDependencies || pkg.dependencies() == null
                            ? Future.succeededFuture(pkg)
                            : loadDependencies(pkg, visited, ownership).map(pkg));
        });
    }

    private Future<Void> loadDependencies(NpmPackage pkg, Set<String> visited, PackageOwnership ownership) {
        List<Future<?>> deps = new ArrayList<>();
        for (String dependency : pkg.dependencies()) {
            String depName = dependency.contains("#") ? dependency.substring(0, dependency.indexOf('#')) : dependency;
            if (DependencyResolver.isCore(depName)) {
                continue;
            }
            deps.add(loadPackage(dependency, false, true, visited, ownership)
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

    private Future<NpmPackage> fetchFromRegistries(String name, String version, PackageOwnership ownership) {
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
        return first.future().compose(bytes -> bytes == null ? Future.succeededFuture(null)
                : store(bytes.getBytes(), "REGISTRY", ownership));
    }

    /**
     * Version chosen by {@code POST /igs/register} for {@code latest} (spec 004 FR-027 to FR-029).
     *
     * @param fromRegistry the registries' highest release, rather than a fallback to the highest stored version
     * @param warning      curated text when the registries could not decide, else {@code null}
     */
    public record RegistrationVersion(String version, boolean fromRegistry, String warning) {
    }

    public static final String NO_REGISTRY_WARNING = "No package registry could be consulted; using the highest stored version";
    public static final String NOT_LISTED_WARNING = "No package registry lists this package; using the highest stored version";

    private enum ListingOutcome { LISTED, NOT_LISTED, FAILED }

    private record Listing(ListingOutcome outcome, String latest) {
    }

    /**
     * The registries' highest released version of {@code name} (the {@link #latestFromListing} rule). When every
     * registry fails or registries are disabled, or when none lists the package, falls back to the highest stored
     * version with a warning saying which; completes with {@code null} when nothing is stored either. Unlike
     * {@link #resolveVersion}, a stored version never hides a newer release.
     */
    public Future<RegistrationVersion> resolveForRegistration(String name) {
        if (!registryEnabled || registryUrls.isEmpty()) {
            return storedFallback(name, NO_REGISTRY_WARNING);
        }
        List<Future<Listing>> answers = new ArrayList<>();
        for (String registry : registryUrls) {
            answers.add(fetcher.fetch(registry + "/" + name)
                    .map(body -> latestFromListing(body.toJsonObject())
                            .map(v -> new Listing(ListingOutcome.LISTED, v))
                            .orElse(new Listing(ListingOutcome.NOT_LISTED, null)))
                    .recover(err -> {
                        boolean notFound = err instanceof ProblemException p && p.status() == 404;
                        if (!notFound) {
                            logger.info("Registry {} could not list {}: {}", registry, name, err.getMessage());
                        }
                        return Future.succeededFuture(new Listing(notFound ? ListingOutcome.NOT_LISTED : ListingOutcome.FAILED, null));
                    }));
        }
        return Future.join(answers).transform(ar -> {
            List<String> versions = new ArrayList<>();
            boolean anyAnswered = false;
            for (Future<Listing> f : answers) {
                Listing l = f.result();
                if (l.outcome() == ListingOutcome.LISTED) {
                    versions.add(l.latest());
                }
                anyAnswered |= l.outcome() != ListingOutcome.FAILED;
            }
            Optional<String> latest = PackageVersionComparator.max(versions);
            if (latest.isPresent()) {
                return Future.succeededFuture(new RegistrationVersion(latest.get(), true, null));
            }
            return storedFallback(name, anyAnswered ? NOT_LISTED_WARNING : NO_REGISTRY_WARNING);
        });
    }

    private Future<RegistrationVersion> storedFallback(String name, String warning) {
        return getLatestVersionFromCache(pgPool, name)
                .map(stored -> stored == null ? null : new RegistrationVersion(stored, false, warning));
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

    /**
     * The highest released version in a registry listing (FR-025). The registry's {@code dist-tags.latest} follows
     * publication order, so it is only a fallback for listings without a version list.
     */
    private static Optional<String> latestFromListing(JsonObject listing) {
        JsonObject versions = listing.getJsonObject("versions");
        if (versions != null && !versions.isEmpty()) {
            return PackageVersionComparator.max(versions.fieldNames());
        }
        JsonObject tags = listing.getJsonObject("dist-tags");
        return tags == null ? Optional.empty() : Optional.ofNullable(tags.getString("latest"));
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
