package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
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
import nzi.fhir.validator.core.npm.PackageOwnership;
import nzi.fhir.validator.core.npm.VersionSeries;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.AccessChange;
import nzi.fhir.validator.core.security.AccessPolicy;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
    /** Given (expanded package bytes, additional heap bytes), fails with 413 when the new context can't fit. */
    private java.util.function.BiFunction<Long, Long, Future<Void>> admitBuild = (bytes, extra) -> Future.succeededFuture();
    /** {@code FV_TX_CACHE_SIZE}: a new terminology context's code cache bound is part of its include check (SC-009). */
    private volatile int txCacheSize;
    /** Spec 007: the terminology allowlist settings are checked against when saved (FR-010 a, FR-012a). */
    private volatile nzi.fhir.validator.core.terminology.TxServerAllowlistSource txAllowlist =
            nzi.fhir.validator.core.terminology.TxServerAllowlistSource.EMPTY;
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

    /**
     * @param admitBuild given the expanded size of a new resolved package set, fails (413 {@code package-too-large})
     *                   if this instance could not build a validator from it; checked before the include is
     *                   committed, so a set that would exhaust the heap never reaches the configuration
     */
    public void setAdmitBuild(Function<Long, Future<Void>> admitBuild) {
        this.admitBuild = (bytes, extra) -> admitBuild.apply(bytes);
    }

    /**
     * @param admitBuild given the expanded size of a new resolved package set and the heap the context holds besides
     *                   (its code cache bound, spec 007), fails with 413 {@code package-too-large} if this instance
     *                   could not build it; checked before the include is committed
     */
    public void setAdmitBuild(java.util.function.BiFunction<Long, Long, Future<Void>> admitBuild) {
        this.admitBuild = admitBuild;
    }

    /** {@code FV_TX_CACHE_SIZE}, for the include check of a context with a terminology mode (spec 007 SC-009). */
    public void setTxCacheSize(int txCacheSize) {
        this.txCacheSize = txCacheSize;
    }

    /** The allowlist a terminology setting is checked against when saved (spec 007). */
    public void setTxAllowlist(nzi.fhir.validator.core.terminology.TxServerAllowlistSource txAllowlist) {
        this.txAllowlist = txAllowlist;
    }

    public PostgresPackageCacheManager cacheManager() {
        return cacheManager;
    }

    /**
     * Includes a stored IG package for {@code fhirVersion}: its validator context is pre-built and pinned on every
     * instance (spec 004 FR-019 to FR-023). Other included packages, other versions of the same package included,
     * are left alone. The revision bump runs in one transaction with the configuration row locked, so concurrent
     * includes serialise and every instance converges on one result. Completes after the change is applied on this
     * instance, or after {@code localApplyWaitMs} with a warning while the build continues in the background; other
     * instances follow via notification/poll. Including a package that is already included changes nothing.
     *
     * @param requestedVersion an exact version, or {@code latest}/empty for the highest stored release
     * @return {@code {status, fhirVersion, key {fhirVersion, igPackage}, revision, included, resolved, warnings}}
     */
    static final String NOT_APPLIED_WARNING = "Committed; this instance has not applied it yet, see /health";
    static final String STILL_APPLYING_WARNING = "Committed; this instance is still rebuilding its validator, see /health";

    public Future<JsonObject> include(SupportedFhirVersion fhirVersion, String name, String requestedVersion) {
        return include(Caller.system(Organisations.DEFAULT_SYSTEM_SHORT_NAME), fhirVersion, name, requestedVersion,
                Optional.empty());
    }

    /**
     * Includes an IG package for {@code caller} (spec 005 FR-018, research R6). Only packages (and dependencies) the
     * caller may use can be included; the others are answered like packages that aren't stored. A new context is owned
     * by the caller's organisation, shareable unless {@code shareable} is false, and only the owner of the IG package
     * (or the system organisation) may make it private. A context already included, possibly by another organisation,
     * is left unchanged: the result reports its owner when the caller may use it, otherwise 404.
     */
    public Future<JsonObject> include(Caller caller, SupportedFhirVersion fhirVersion, String name, String requestedVersion,
                                      Optional<Boolean> shareable) {
        return include(caller, fhirVersion, name, requestedVersion, shareable, Optional.empty());
    }

    /**
     * As {@link #include(Caller, SupportedFhirVersion, String, String, Optional)}, with the new context's terminology
     * setting (spec 007 FR-003). It is checked against the allowlist after the existing include checks and before the
     * heap check and the commit (contracts §2). For a context that is already included it is ignored with a warning.
     */
    public Future<JsonObject> include(Caller caller, SupportedFhirVersion fhirVersion, String name, String requestedVersion,
                                      Optional<Boolean> shareable,
                                      Optional<nzi.fhir.validator.core.terminology.TerminologySettings> terminology) {
        if (name == null || name.isBlank()) {
            return Future.failedFuture(new ProblemException(400, "invalid-request", "igPackageId is required"));
        }
        // Spec 006 FR-001, FR-002: a series is parsed before anything is read; a malformed one is 400.
        VersionSeries series;
        try {
            series = VersionSeries.isSeries(requestedVersion) ? VersionSeries.parse(requestedVersion) : null;
        } catch (ProblemException e) {
            return Future.failedFuture(e);
        }
        if (requestedVersion != null && !requestedVersion.isBlank() && !"latest".equalsIgnoreCase(requestedVersion)
                && !PostgresPackageCacheManager.isValidVersionLabel(requestedVersion)) {
            return Future.failedFuture(new ProblemException(400, "invalid-request",
                    "igPackageVersion must be 1-64 characters without whitespace"));
        }
        List<String> warnings = new ArrayList<>();
        // Visibility is decided from data read in this transaction, never from the in-memory view, which may lag.
        return pgPool.withTransaction(tx -> loadCatalogWithAccess(tx).compose(catalog -> {
                    boolean latest = requestedVersion == null || requestedVersion.isBlank()
                            || "latest".equalsIgnoreCase(requestedVersion);
                    List<String> visible = catalog.catalog().versions(name).stream()
                            .filter(v -> AccessPolicy.canUse(caller, catalog.access().get(name + "#" + v)))
                            .toList();
                    // Spec 006 FR-011: a series picks the highest stored match the caller may use; no registry.
                    Optional<String> concrete = series != null ? series.highest(visible)
                            : latest ? PackageVersionComparator.max(visible)
                            : visible.contains(requestedVersion) ? Optional.of(requestedVersion) : Optional.empty();
                    if (concrete.isEmpty()) {
                        String label = latest ? name : name + "#" + requestedVersion;
                        return Future.failedFuture(new ProblemException(404, "package-not-found",
                                "IG package " + label + " is not stored. Register it first via /igs/upload or /igs/register"));
                    }
                    String key = name + "#" + concrete.get();
                    Access packageAccess = catalog.access().get(key);
                    return checkFhirVersion(tx, fhirVersion, name, concrete.get())
                            .compose(v -> ConfigRevisionRepository.lockForUpdate(tx, fhirVersion)).compose(row -> {
                        // This key's context only: one IG and its dependencies (FR-021), resolved as the context's
                        // owner (spec 006 FR-024), exactly as every instance will build it.
                        UUID contextOwner = row.included().contains(key) ? row.access().get(key).owner() : caller.orgId();
                        DependencyResolver.Resolution resolution = DependencyResolver.resolve(Set.of(key), catalog.catalog(),
                                p -> AccessPolicy.selectableFor(contextOwner, catalog.access().get(p)));
                        warnings.addAll(resolution.warnings());
                        for (String p : resolution.resolved()) {
                            Access a = catalog.access().get(p);
                            if (!p.equals(key) && a != null && !AccessPolicy.canUse(caller, a)) {
                                return Future.failedFuture(ProblemException.dependencyUnavailable());
                            }
                        }
                        if (row.included().contains(key)) {
                            Access existing = row.access().get(key);
                            if (!AccessPolicy.canUse(caller, existing)) {
                                return Future.failedFuture(new ProblemException(404, "package-not-found", "IG package " + key
                                        + " is not stored. Register it first via /igs/upload or /igs/register"));
                            }
                            if (shareable.isPresent() && shareable.get() != existing.shareable() && caller.owns(existing.owner())) {
                                warnings.add(ALREADY_INCLUDED_WARNING);
                            }
                            if (terminology.isPresent() && !terminology.get().equals(row.terminologyOf(key))) {
                                warnings.add(TERMINOLOGY_IGNORED_WARNING);
                            }
                            return Future.succeededFuture(includeResult(fhirVersion, key, row.revision(), row.included(),
                                    resolution.resolved(), existing, row.terminologyOf(key)));
                        }
                        nzi.fhir.validator.core.terminology.TerminologySettings txSettings =
                                terminology.orElse(nzi.fhir.validator.core.terminology.TerminologySettings.NONE);
                        try {
                            txAllowlist.current().require(txSettings, fhirVersion);
                        } catch (ProblemException e) {
                            return Future.failedFuture(e);
                        }
                        boolean share = shareable.orElse(true);
                        if (!share && !caller.system() && !caller.owns(packageAccess == null ? null : packageAccess.owner())) {
                            return Future.failedFuture(new ProblemException(400, "invalid-request",
                                    PRIVATE_CONTEXT_REFUSED));
                        }
                        Set<String> included = new TreeSet<>(row.included());
                        included.add(key);
                        List<String> includedList = List.copyOf(included);
                        Access contextAccess = new Access(caller.orgId(), caller.orgShortName(), share);
                        // The new context must fit in this instance's heap (after evicting on-demand contexts).
                        long cacheBytes = nzi.fhir.validator.core.validator.HeapBudget.terminologyCacheBytes(txSettings, txCacheSize);
                        return PostgresPackageCacheManager.expandedBytes(tx, resolution.resolved())
                                .compose(bytes -> admitBuild.apply(bytes, cacheBytes))
                                .compose(v -> ConfigRevisionRepository.updateIgSet(tx, fhirVersion, includedList, key,
                                        ConfigRevisionRepository.KIND_IG, caller.orgId(), share, txSettings))
                                .map(revision -> includeResult(fhirVersion, key, revision, includedList, resolution.resolved(),
                                        contextAccess, txSettings));
                    });
                }))
                .compose(result -> LocalApplyWait.await(vertx, applyLocally.apply(fhirVersion), localApplyWaitMs,
                                () -> warnings.add(STILL_APPLYING_WARNING))
                        .recover(err -> {
                            // The cause goes to the log (with the request id); clients get curated text (Principle IV).
                            logger.warn("Included IG {}#{} in {} is committed but not applied locally yet", name,
                                    requestedVersion, fhirVersion, err);
                            warnings.add(NOT_APPLIED_WARNING);
                            return Future.succeededFuture();
                        })
                        .map(v -> result.put("warnings", new JsonArray(warnings))))
                .onSuccess(r -> logger.info("Included IG {}#{} in {} (revision {}, owner {}, shareable {}) by {}", name,
                        requestedVersion, fhirVersion, r.getLong("revision"), r.getString("owner"), r.getBoolean("shareable"),
                        caller.label()));
    }

    static final String ALREADY_INCLUDED_WARNING = "Already included; change sharing with PATCH /{version}/include-ig";
    static final String PRIVATE_CONTEXT_REFUSED = "Only the owner of the IG package can make its validator context private";
    static final String TERMINOLOGY_IGNORED_WARNING = "terminology is ignored for an already included context; change it "
            + "with PATCH /{version}/include-ig";

    private static JsonObject includeResult(SupportedFhirVersion fhirVersion, String key, long revision,
                                            List<String> included, List<String> resolved, Access access,
                                            nzi.fhir.validator.core.terminology.TerminologySettings terminology) {
        return new JsonObject()
                .put("terminology", terminology.toJson())
                .put("status", "success")
                .put("fhirVersion", fhirVersion.name())
                .put("key", new JsonObject().put("fhirVersion", fhirVersion.name()).put("igPackage", key))
                .put("revision", revision)
                .put("included", new JsonArray(List.copyOf(included)))
                .put("resolved", new JsonArray(resolved))
                .put("owner", access.ownerShortName())
                .put("shareable", access.shareable());
    }

    /**
     * Stops pre-building an included IG version for {@code fhirVersion} (spec 004 FR-024): removes exactly that
     * {@code name#version} from the included set, commits and propagates like {@link #include}. The package stays
     * stored and can still be named in {@code ig} (it is then built on demand).
     *
     * @param ig an exact {@code name#version}
     * @return {@code {status, fhirVersion, revision, excluded, included, warnings}}
     */
    public Future<JsonObject> exclude(SupportedFhirVersion fhirVersion, String ig) {
        return exclude(Caller.system(Organisations.DEFAULT_SYSTEM_SHORT_NAME), fhirVersion, ig);
    }

    /**
     * Like {@link #exclude(SupportedFhirVersion, String)}, allowed only for the context's owner and the system
     * organisation; anyone else gets the "not included" answer (spec 005 FR-018).
     */
    public Future<JsonObject> exclude(Caller caller, SupportedFhirVersion fhirVersion, String ig) {
        nzi.fhir.validator.core.validator.IgReference ref;
        try {
            ref = nzi.fhir.validator.core.validator.IgReference.parse(ig);
        } catch (ProblemException e) {
            return Future.failedFuture(e);
        }
        if (ref.version() == null) {
            return Future.failedFuture(new ProblemException(400, "invalid-request",
                    "An exact name#version is required to exclude an IG"));
        }
        String key = ref.label();
        List<String> warnings = new ArrayList<>();
        return pgPool.withTransaction(tx -> ConfigRevisionRepository.lockForUpdate(tx, fhirVersion).compose(row -> {
                    if (!row.included().contains(key) || !(caller.system() || caller.owns(row.access().get(key).owner()))) {
                        return Future.failedFuture(notIncluded(key, fhirVersion));
                    }
                    List<String> remaining = row.included().stream().filter(p -> !p.equals(key)).sorted().toList();
                    return ConfigRevisionRepository.updateIgSet(tx, fhirVersion, remaining, key,
                                    ConfigRevisionRepository.KIND_IG_EXCLUDE)
                            .map(revision -> new JsonObject()
                                    .put("status", "success")
                                    .put("fhirVersion", fhirVersion.name())
                                    .put("revision", revision)
                                    .put("excluded", key)
                                    .put("included", new JsonArray(remaining)));
                }))
                .compose(result -> LocalApplyWait.await(vertx, applyLocally.apply(fhirVersion), localApplyWaitMs,
                                () -> warnings.add(STILL_APPLYING_WARNING))
                        .recover(err -> {
                            logger.warn("Excluded IG {} in {} is committed but not applied locally yet", key, fhirVersion, err);
                            warnings.add(NOT_APPLIED_WARNING);
                            return Future.succeededFuture();
                        })
                        .map(v -> result.put("warnings", new JsonArray(warnings))))
                .onSuccess(r -> logger.info("Excluded IG {} in {} (revision {}) by {}", key, fhirVersion, r.getLong("revision"),
                        caller.label()));
    }

    private static ProblemException notIncluded(String key, SupportedFhirVersion fhirVersion) {
        return new ProblemException(404, ProblemException.TYPE_PACKAGE_NOT_FOUND,
                "IG package " + key + " is not included for " + fhirVersion.name());
    }

    /**
     * Changes the sharing of, or transfers, a stored IG package (spec 005 FR-019, contracts §5).
     *
     * @return {@code {status, name, version, owner, shareable}}
     */
    public Future<JsonObject> changePackageAccess(Caller caller, String name, String version, AccessChange change) {
        return cacheManager.changeAccess(name, version, caller, change)
                .map(a -> new JsonObject()
                        .put("status", "success")
                        .put("name", name)
                        .put("version", version)
                        .put("owner", a.ownerShortName())
                        .put("shareable", a.shareable()));
    }

    /**
     * Changes the sharing of, or transfers, an included validator context (spec 005 FR-019, contracts §4), with the
     * version's configuration row locked: the revision is bumped and {@code IG_ACCESS} notified, so every instance swaps
     * the context's access without rebuilding it. Check order: 404 (not included, or the caller is neither the owner nor
     * the system organisation) → 403 (a transfer by a caller that isn't the system organisation) → 400 (body, unknown
     * or inactive target organisation, or a private context of an IG package the caller doesn't own).
     *
     * @param ig an exact {@code name#version}
     * @return {@code {status, fhirVersion, igPackage, owner, shareable, revision, warnings}}
     */
    public Future<JsonObject> changeSnapshotAccess(Caller caller, SupportedFhirVersion fhirVersion, String ig,
                                                   AccessChange change) {
        nzi.fhir.validator.core.validator.IgReference ref;
        try {
            ref = nzi.fhir.validator.core.validator.IgReference.parse(ig);
        } catch (ProblemException e) {
            return Future.failedFuture(e);
        }
        if (ref.version() == null) {
            return Future.failedFuture(new ProblemException(400, "invalid-request",
                    "An exact name#version is required to change an included IG"));
        }
        String key = ref.label();
        List<String> warnings = new ArrayList<>();
        return pgPool.withTransaction(tx -> ConfigRevisionRepository.lockForUpdate(tx, fhirVersion).compose(row -> {
                    Access current = row.included().contains(key) ? row.access().get(key) : null;
                    if (current == null || !(caller.system() || caller.owns(current.owner()))) {
                        return Future.failedFuture(notIncluded(key, fhirVersion));
                    }
                    if (change.transfers() && !caller.system()) {
                        return Future.failedFuture(ProblemException.transferForbidden());
                    }
                    if (change.invalid() != null) {
                        return Future.failedFuture(new ProblemException(400, "invalid-request", change.invalid()));
                    }
                    Future<Void> privateAllowed = change.shareable().orElse(true) || caller.system()
                            ? Future.succeededFuture()
                            : packageAccess(tx, ref.name(), ref.version()).compose(pkg -> pkg.isPresent() && caller.owns(pkg.get().owner())
                            ? Future.succeededFuture()
                            : Future.failedFuture(new ProblemException(400, "invalid-request", PRIVATE_CONTEXT_REFUSED)));
                    // Spec 007 FR-003, FR-010 a, FR-012a: a terminology setting is checked against the allowlist after the
                    // body checks (contracts §3); setting the same value again changes nothing.
                    Optional<nzi.fhir.validator.core.terminology.TerminologySettings> newTerminology = change.terminology()
                            .filter(t -> !t.equals(row.terminologyOf(key)));
                    try {
                        newTerminology.ifPresent(t -> txAllowlist.current().require(t, fhirVersion));
                    } catch (ProblemException e) {
                        return Future.failedFuture(e);
                    }
                    Future<Long> accessChanged = !change.changesAccess() ? Future.succeededFuture(row.revision())
                            : privateAllowed
                            .compose(v -> PostgresPackageCacheManager.resolveTargetOrganisation(tx, change))
                            .compose(newOwner -> ConfigRevisionRepository.changeSnapshotAccess(tx, fhirVersion, key, newOwner,
                                    change.shareable()));
                    return accessChanged
                            .compose(revision -> newTerminology.isEmpty() ? Future.succeededFuture(revision)
                                    : ConfigRevisionRepository.changeTerminology(tx, fhirVersion, key, newTerminology.get()))
                            .compose(revision -> ConfigRevisionRepository.lockForUpdate(tx, fhirVersion)
                                    .map(after -> new JsonObject()
                                            .put("status", "success")
                                            .put("fhirVersion", fhirVersion.name())
                                            .put("igPackage", key)
                                            .put("owner", after.access().get(key).ownerShortName())
                                            .put("shareable", after.access().get(key).shareable())
                                            .put("terminology", after.terminologyOf(key).toJson())
                                            .put("revision", revision)));
                }))
                .compose(result -> LocalApplyWait.await(vertx, applyLocally.apply(fhirVersion), localApplyWaitMs,
                                () -> warnings.add(STILL_APPLYING_WARNING))
                        .recover(err -> {
                            logger.warn("Access change of IG {} in {} is committed but not applied locally yet", key, fhirVersion, err);
                            warnings.add(NOT_APPLIED_WARNING);
                            return Future.succeededFuture();
                        })
                        .map(v -> result.put("warnings", new JsonArray(warnings))))
                .onSuccess(r -> logger.info("Changed IG {} in {} (owner {}, shareable {}, terminology {}, revision {}) by {}", key,
                        fhirVersion, r.getString("owner"), r.getBoolean("shareable"),
                        r.getJsonObject("terminology").getString("mode"), r.getLong("revision"), caller.label()));
    }

    /**
     * 400 when the package declares FHIR versions and none of them is {@code fhirVersion}'s release (CHK038). A
     * package that declares none, or only labels that are not known releases, is accepted.
     */
    private static Future<Void> checkFhirVersion(SqlClient tx, SupportedFhirVersion fhirVersion, String name, String version) {
        return tx.preparedQuery("SELECT ig_package_meta FROM %s.fhir_implementation_guides ".formatted(DB_POSTGRES_SCHEMA_NAME)
                        + "WHERE ig_package_id = $1 AND ig_package_version = $2")
                .execute(Tuple.of(name, version))
                .compose(rows -> {
                    Set<SupportedFhirVersion> declared = declaredFhirVersions(
                            rows.size() == 0 ? null : (JsonObject) rows.iterator().next().getValue(0));
                    if (declared.isEmpty() || declared.contains(fhirVersion)) {
                        return Future.succeededFuture();
                    }
                    return Future.failedFuture(new ProblemException(400, "invalid-request", "IG package " + name + "#"
                            + version + " is for FHIR " + declared.stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                            + ", not " + fhirVersion.name()));
                });
    }

    /** FHIR releases a stored package declares; the rule lives in {@link PackageCatalogView.parseDeclaredFhirVersions(JsonObject)}. */
    static Set<SupportedFhirVersion> declaredFhirVersions(JsonObject meta) {
        return nzi.fhir.validator.core.npm.PackageCatalogView.parseDeclaredFhirVersions(meta);
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

    /** The dependency catalog plus each package's access, read in one query (spec 005: include decides visibility). */
    public record CatalogWithAccess(DependencyResolver.Catalog catalog, Map<String, Access> access) {
    }

    public static Future<CatalogWithAccess> loadCatalogWithAccess(SqlClient client) {
        return client.query(("SELECT g.ig_package_id, g.ig_package_version, g.dependencies, g.owner, o.name_short, g.is_shareable "
                        + "FROM %s.fhir_implementation_guides g JOIN %s.organisations o ON o.id = g.owner")
                        .formatted(DB_POSTGRES_SCHEMA_NAME, DB_POSTGRES_SCHEMA_NAME))
                .execute()
                .map(rows -> {
                    Map<String, List<String>> packages = new HashMap<>();
                    Map<String, Access> access = new HashMap<>();
                    rows.forEach(row -> {
                        String[] deps = row.getArrayOfStrings(2);
                        String key = row.getString(0) + "#" + row.getString(1);
                        packages.put(key, deps == null ? List.of() : List.of(deps));
                        access.put(key, new Access(row.getUUID(3), row.getString(4), row.getBoolean(5)));
                    });
                    return new CatalogWithAccess(DependencyResolver.Catalog.of(packages), access);
                });
    }

    public static IgPackageService create(Vertx vertx, Pool pgPool) {
        PostgresPackageCacheManager cacheManager = new PostgresPackageCacheManager(vertx, pgPool);
        return new IgPackageService(vertx, pgPool, cacheManager);
    }

    public static IgPackageService create(Vertx vertx, Pool pgPool, PostgresPackageCacheManager cacheManager) {
        return new IgPackageService(vertx, pgPool, cacheManager);
    }

    /**
     * Outcome of {@code POST /igs/register} (spec 004 FR-030).
     *
     * @param downloaded whether the package archive was fetched in this call
     */
    public record Registration(NpmPackage pkg, boolean downloaded, List<String> warnings, Access access) {
        public Registration {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Registers {@code name} from the package registries (spec 004 FR-027 to FR-031). With no version or
     * {@code latest}, the registries' highest release is used, falling back to the highest stored version the caller
     * may use with a warning when no registry can decide (spec 006 FR-023). A version that is already stored is returned without downloading it again;
     * a new one is downloaded and stored, with its dependencies when {@code includeDependency}.
     *
     * @return fails with 404 {@code package-not-found} when no source has the package
     */
    public Future<Registration> register(String name, String version, boolean includeDependency) {
        return register(Caller.system(Organisations.DEFAULT_SYSTEM_SHORT_NAME), name, version, includeDependency, true);
    }

    /**
     * Like {@link #register(String, String, boolean)} for {@code caller} (spec 005 FR-016, FR-017): every package newly
     * stored is owned by the caller's organisation with {@code shareable}; a version already stored keeps its owner and
     * sharing, and is answered 404 when the caller may not use it.
     */
    public Future<Registration> register(Caller caller, String name, String version, boolean includeDependency,
                                         boolean shareable) {
        PackageOwnership ownership;
        try {
            ownership = PackageOwnership.of(caller, shareable);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(new ProblemException(403, "forbidden", "This API key does not allow this operation"));
        }
        boolean latest = version == null || version.isBlank() || "latest".equals(version);
        Future<PostgresPackageCacheManager.RegistrationVersion> chosen;
        if (VersionSeries.isSeries(version)) {
            VersionSeries series;
            try {
                series = VersionSeries.parse(version);
            } catch (ProblemException e) {
                return Future.failedFuture(e);
            }
            chosen = resolveSeriesForRegistration(caller, name, series);
        } else {
            chosen = latest
                    ? resolveLatestForRegistration(caller, name)
                    : Future.succeededFuture(new PostgresPackageCacheManager.RegistrationVersion(version, false, null));
        }
        return chosen.compose(rv -> {
            if (rv == null) {
                return Future.failedFuture(new ProblemException(404, ProblemException.TYPE_PACKAGE_NOT_FOUND, "IG package "
                        + name + " was not found in the configured package registries and is not stored"));
            }
            String key = name + "#" + rv.version();
            List<String> warnings = rv.warning() == null ? List.of() : List.of(rv.warning());
            ProblemException notFound = new ProblemException(404, ProblemException.TYPE_PACKAGE_NOT_FOUND,
                    "IG package " + key + " was not found in the configured package registries");
            return packageAccess(pgPool, name, rv.version()).compose(stored -> {
                if (stored.isPresent() && !AccessPolicy.canUse(caller, stored.get())) {
                    // Stored by another organisation and private: answered like a package no source has (FR-017).
                    return Future.failedFuture(notFound);
                }
                return cacheManager.loadPackage(key, false, includeDependency, ownership)
                        .compose(pkg -> pkg == null ? Future.failedFuture(notFound)
                                : packageAccess(pgPool, pkg.name(), pkg.version())
                                .map(access -> new Registration(pkg, stored.isEmpty(), warnings, access.orElse(null))));
            });
        }).onSuccess(r -> logger.info("Registered IG {}#{} (requested: {}, downloaded: {}) by {}", r.pkg().name(),
                        r.pkg().version(), version, r.downloaded(), caller.label()))
                .onFailure(e -> logger.warn("Failed to register IG {}#{}: {}", name, version, e.getMessage()));
    }

    /**
     * The concrete version {@code latest} registers (spec 004 FR-027 to FR-029, spec 006 FR-023, research R11): the
     * registries' highest release, else the highest stored version {@code caller} may use, with the registries'
     * warning. Completes with {@code null} (answered 404) when the caller may use no stored version.
     */
    private Future<PostgresPackageCacheManager.RegistrationVersion> resolveLatestForRegistration(Caller caller, String name) {
        return cacheManager.resolveForRegistration(name).compose(result -> result.version() != null
                ? Future.succeededFuture(new PostgresPackageCacheManager.RegistrationVersion(result.version(), true, null))
                : storedFallback(caller, name, PackageVersionComparator::max, result.warning())
                .map(v -> v.orElse(null)));
    }

    /**
     * The concrete version a series registers (spec 006 FR-006, FR-009, research R4): the highest match listed by the
     * registries, else the highest stored match {@code caller} may use, with the registries' warning. A stored version
     * of another organisation that is private is never a fallback candidate. Fails with 404 when nothing matches.
     */
    private Future<PostgresPackageCacheManager.RegistrationVersion> resolveSeriesForRegistration(Caller caller, String name,
                                                                                              VersionSeries series) {
        return cacheManager.resolveSeriesFromRegistries(name, series).compose(result -> result.version() != null
                ? Future.succeededFuture(new PostgresPackageCacheManager.RegistrationVersion(result.version(), true, null))
                : storedFallback(caller, name, series::highest, result.warning())
                .compose(v -> v.map(Future::succeededFuture).orElseGet(() -> Future.failedFuture(new ProblemException(404,
                        ProblemException.TYPE_PACKAGE_NOT_FOUND, "IG package " + name + " has no version in series "
                        + series.raw() + " in the configured package registries and none is stored")))));
    }

    /**
     * The stored fallback shared by {@code latest} and series registration (spec 006 FR-009, FR-023, research R11):
     * {@code pick} applied to the stored versions of {@code name} that {@code caller} may use, so another
     * organisation's private version is never chosen or named.
     */
    private Future<Optional<PostgresPackageCacheManager.RegistrationVersion>> storedFallback(Caller caller, String name,
            Function<Collection<String>, Optional<String>> pick, String warning) {
        return storedVersionsWithAccess(pgPool, name).map(stored -> pick.apply(stored.entrySet().stream()
                        .filter(e -> AccessPolicy.canUse(caller, e.getValue()))
                        .map(Map.Entry::getKey)
                        .toList())
                .map(v -> new PostgresPackageCacheManager.RegistrationVersion(v, false, warning)));
    }

    /** Every stored version of {@code name} with its owner and sharing (spec 006 research R4). */
    static Future<Map<String, Access>> storedVersionsWithAccess(SqlClient client, String name) {
        return client.preparedQuery("SELECT g.ig_package_version, g.owner, o.name_short, g.is_shareable FROM %s.fhir_implementation_guides g "
                                .formatted(DB_POSTGRES_SCHEMA_NAME) + "JOIN %s.organisations o ON o.id = g.owner "
                                .formatted(DB_POSTGRES_SCHEMA_NAME) + "WHERE g.ig_package_id = $1")
                .execute(Tuple.of(name))
                .map(rows -> {
                    Map<String, Access> versions = new HashMap<>();
                    rows.forEach(row -> versions.put(row.getString(0),
                            new Access(row.getUUID(1), row.getString(2), row.getBoolean(3))));
                    return versions;
                });
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
        return registerIg(igPackageBytes, loadDependencies, source, PackageOwnership.SYSTEM);
    }

    /**
     * Like {@link #registerIg(byte[], boolean, String)}, owned as {@code ownership} says (spec 005 research R7).
     *
     * @return fails with 409 {@code conflict} when another organisation stored the same {@code name#version}
     */
    public Future<NpmPackage> registerIg(byte[] igPackageBytes, boolean loadDependencies, String source,
                                         PackageOwnership ownership) {
        if (igPackageBytes == null) {
            return Future.failedFuture(new ProblemException(400, "invalid-request", "No package content"));
        }
        return cacheManager.store(igPackageBytes, source, ownership).compose(npmPackage -> {
            if (!loadDependencies || npmPackage.dependencies() == null || npmPackage.dependencies().isEmpty()) {
                return Future.succeededFuture(npmPackage);
            }
            List<Future<?>> dependencyFutures = npmPackage.dependencies().stream()
                    .filter(dependency -> !DependencyResolver.isCore(dependency.contains("#") ? dependency.substring(0, dependency.indexOf('#')) : dependency))
                    .<Future<?>>map(dependency -> cacheManager.loadPackage(dependency, false, true, ownership))
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

    /** Like {@link #registerUploadedFile(String)}, owned as {@code ownership} says (spec 005 research R7). */
    public Future<NpmPackage> registerUploadedFile(String path, PackageOwnership ownership) {
        return cacheManager.storeFile(path, "UPLOAD", ownership);
    }

    /** Owner and sharing of a stored package (for responses, FR-020). */
    public Future<Optional<Access>> access(String name, String version) {
        return packageAccess(pgPool, name, version);
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
     * Declared dependencies of a stored package; 404 problem if it is not stored, or {@code caller} may not use it
     * (spec 005: the same answer).
     */
    public Future<JsonObject> getDependencyGraph(Caller caller, String name, String version) {
        return pgPool.preparedQuery("SELECT dependencies, owner, is_shareable FROM %s.fhir_implementation_guides "
                                .formatted(DB_POSTGRES_SCHEMA_NAME) + "WHERE ig_package_id = $1 AND ig_package_version = $2")
                .execute(Tuple.of(name, version))
                .compose(rows -> {
                    if (rows.size() == 0 || !AccessPolicy.canUse(caller, new Access(rows.iterator().next().getUUID(1), null,
                            rows.iterator().next().getBoolean(2)))) {
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
     * File listing of a stored package (the deprecated "conformance" operation); 404 problem if it is not stored, or
     * {@code caller} may not use it (spec 005: the same answer).
     */
    public Future<JsonObject> listPackageFiles(Caller caller, String name, String version) {
        return packageAccess(pgPool, name, version)
                .compose(access -> AccessPolicy.canUse(caller, access.orElse(null))
                        ? cacheManager.loadRawPackage(name, version) : Future.succeededFuture(null))
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

    /** Owner and sharing of a stored package (with the owner's short name), or empty when it isn't stored. */
    static Future<Optional<Access>> packageAccess(SqlClient client, String name, String version) {
        return client.preparedQuery("SELECT g.owner, o.name_short, g.is_shareable FROM %s.fhir_implementation_guides g "
                                .formatted(DB_POSTGRES_SCHEMA_NAME) + "JOIN %s.organisations o ON o.id = g.owner "
                                .formatted(DB_POSTGRES_SCHEMA_NAME) + "WHERE g.ig_package_id = $1 AND g.ig_package_version = $2")
                .execute(Tuple.of(name, version))
                .map(rows -> rows.size() == 0 ? Optional.empty() : Optional.of(new Access(rows.iterator().next().getUUID(0),
                        rows.iterator().next().getString(1), rows.iterator().next().getBoolean(2))));
    }

    private static ProblemException notStored(String name, String version) {
        return new ProblemException(404, "package-not-found", "IG package " + name + "#" + version + " is not stored");
    }

}