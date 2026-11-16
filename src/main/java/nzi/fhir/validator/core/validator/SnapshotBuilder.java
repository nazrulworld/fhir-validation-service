package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import io.vertx.core.Future;
import nzi.fhir.validator.core.concurrency.AsyncLimiter;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCache;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.core.npm.SharedPackageDefinitions;
import nzi.fhir.validator.core.service.CustomNpmPackageValidationSupport;
import nzi.fhir.validator.core.service.FhirContextLoader;
import nzi.fhir.validator.core.terminology.RequestScopedTerminologySupport;
import nzi.fhir.validator.core.terminology.TerminologyOnlySupport;
import nzi.fhir.validator.core.terminology.TerminologyServices;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link ValidatorSnapshot}s. Package archives are read from the database on the event loop (I/O only);
 * decompression, parsing and validator construction run on the {@code fv-packages} pool.
 * <p>
 * Full builds can take minutes, so at most {@code maxConcurrentBuilds} run at once (one less than the pool size):
 * the remaining worker stays free for short package work such as upload parsing and DNS resolution (FR-008).
 * Snapshots are immutable: the only way to change what a version validates against is a new build from a different
 * resolved package set (spec 003).
 */
public class SnapshotBuilder {
    private static final Logger logger = LogManager.getLogger(SnapshotBuilder.class);
    private static final String WARM_UP_RESOURCE = "{\"resourceType\":\"Patient\"}";

    /**
     * A resolved package is no longer stored.
     */
    public static class MissingPackageException extends RuntimeException {
        private final String idAndVersion;

        public MissingPackageException(String idAndVersion) {
            super("Package " + idAndVersion + " is not stored", null, false, false);
            this.idAndVersion = idAndVersion;
        }

        public String idAndVersion() {
            return idAndVersion;
        }
    }

    private final Executors executors;
    private final PostgresPackageCacheManager packages;
    private final PackageCache cache;
    private final AsyncLimiter builds;
    private final HeapBudget heap;
    private final TerminologyServices txServices;
    /** Spec 010: parsed package definitions shared by every context of this instance. */
    private final SharedPackageDefinitions sharedDefinitions;
    private volatile Runnable buildHook;

    /**
     * @param packageWorkers size of the {@code fv-packages} pool ({@code FV_PACKAGE_WORKERS})
     * @param heap           refuses builds that would not fit in the heap
     */
    public SnapshotBuilder(Executors executors, PostgresPackageCacheManager packages, PackageCache cache, int packageWorkers,
                           HeapBudget heap) {
        this(executors, packages, cache, packageWorkers, heap, TerminologyServices.OFFLINE);
    }

    /**
     * @param txServices shared terminology infrastructure (spec 007); {@link TerminologyServices#OFFLINE} keeps every
     *                   context offline
     */
    public SnapshotBuilder(Executors executors, PostgresPackageCacheManager packages, PackageCache cache, int packageWorkers,
                           HeapBudget heap, TerminologyServices txServices) {
        this(executors, packages, cache, packageWorkers, heap, txServices, new SharedPackageDefinitions());
    }

    /**
     * @param sharedDefinitions the instance's shared package definitions (spec 010 FR-017); always used, there is no
     *                          setting that turns sharing off (FR-022)
     */
    public SnapshotBuilder(Executors executors, PostgresPackageCacheManager packages, PackageCache cache, int packageWorkers,
                           HeapBudget heap, TerminologyServices txServices, SharedPackageDefinitions sharedDefinitions) {
        this.sharedDefinitions = sharedDefinitions;
        this.txServices = txServices;
        this.executors = executors;
        this.packages = packages;
        this.cache = cache;
        this.builds = new AsyncLimiter(maxConcurrentBuilds(packageWorkers));
        this.heap = heap;
    }

    /** One worker is kept for short package work; with a single worker there is nothing to keep. */
    public static int maxConcurrentBuilds(int packageWorkers) {
        return Math.max(1, packageWorkers - 1);
    }

    public Future<ValidatorSnapshot> build(SupportedFhirVersion version, long revision, List<String> resolvedPackages) {
        return build(SnapshotKey.base(version), revision, new ResolvedContext(resolvedPackages, java.util.Map.of()));
    }

    /**
     * Builds the validator context {@code key} from exactly {@code resolved.packages()} (spec 004: one IG and its
     * dependencies, or nothing for the base context). The terminology setting is applied per request (spec 010).
     */
    public Future<ValidatorSnapshot> build(SnapshotKey key, long revision, ResolvedContext resolved) {
        return builds.run(() -> buildNow(key, revision, resolved));
    }

    private Future<ValidatorSnapshot> buildNow(SnapshotKey key, long revision, ResolvedContext resolved) {
        SupportedFhirVersion version = key.fhirVersion();
        List<String> resolvedPackages = resolved.packages();
        List<Future<Loaded>> loaded = new ArrayList<>();
        for (String idAndVersion : resolvedPackages) {
            loaded.add(load(idAndVersion));
        }
        return Future.all(loaded).compose(all -> executors.packages().executeBlocking(() -> {
            Runnable hook = buildHook;
            if (hook != null) {
                hook.run();
            }
            long started = System.currentTimeMillis();
            List<NpmPackage> npmPackages = new ArrayList<>();
            long archiveBytes = 0;
            long expandedBytes = 0;
            long sharedExpandedBytes = 0;
            for (Future<Loaded> f : loaded) {
                NpmPackage pkg = f.result().pkg();
                npmPackages.add(pkg);
                archiveBytes += f.result().archiveBytes();
                long bytes = PostgresPackageCacheManager.expandedBytes(pkg);
                // Spec 010 FR-024: a package another context already shares costs this build its indexes only.
                if (isShared(version, pkg.name() + "#" + pkg.version(), resolved.hashes())) {
                    sharedExpandedBytes += bytes;
                } else {
                    expandedBytes += bytes;
                }
            }
            // A build that cannot fit fails like any other (version STALE, reason in /health) instead of ending the
            // process with an OutOfMemoryError.
            // The code cache is reserved once per instance (spec 010 FR-024), not per build.
            long reserved = heap.reserve(expandedBytes, HeapBudget.sharedBytes(sharedExpandedBytes));
            try {
                ValidatorSnapshot snapshot = assemble(key, revision, resolvedPackages, resolved.hashes(), npmPackages,
                        txServices, sharedDefinitions);
                snapshot.setPackageArchiveBytes(archiveBytes);
                // Warm caches so the first real request does not pay HAPI's lazy initialisation. Mode none and a budget
                // of 0: the warm-up never calls a terminology server (spec 010 FR-004, spec 007 research R5).
                snapshot.validate(WARM_UP_RESOURCE, SupportedContentType.JSON, List.of());
                logger.info("Built validator for {} at revision {} with {} package(s) in {} ms", key, revision,
                        npmPackages.size(), System.currentTimeMillis() - started);
                return snapshot;
            } finally {
                heap.release(reserved);
            }
        }, false));
    }

    /**
     * A parsed package from the cache, or read from the database (event loop: I/O only) and parsed on the package
     * pool.
     */
    private record Loaded(NpmPackage pkg, long archiveBytes) {
    }

    private Future<Loaded> load(String idAndVersion) {
        NpmPackage cached = cache.get(idAndVersion);
        if (cached != null) {
            return Future.succeededFuture(new Loaded(cached, cache.archiveBytes(idAndVersion)));
        }
        int hash = idAndVersion.indexOf('#');
        return packages.loadRawPackage(idAndVersion.substring(0, hash), idAndVersion.substring(hash + 1))
                .compose(buffer -> {
                    if (buffer == null) {
                        return Future.failedFuture(new MissingPackageException(idAndVersion));
                    }
                    byte[] bytes = buffer.getBytes();
                    return packages.parse(bytes)
                            .onSuccess(pkg -> cache.put(idAndVersion, pkg, bytes.length))
                            .map(pkg -> new Loaded(pkg, bytes.length));
                });
    }

    /** Test hook: runs on the package worker at the start of every full build (e.g. to simulate a slow one). */
    public void setBuildHook(Runnable hook) {
        this.buildHook = hook;
    }

    public PackageCache cache() {
        return cache;
    }

    public SharedPackageDefinitions sharedDefinitions() {
        return sharedDefinitions;
    }

    /** Whether the definitions of {@code id} (with its content hash in {@code hashes}) are shared on this instance now. */
    public boolean isShared(SupportedFhirVersion version, String id, java.util.Map<String, byte[]> hashes) {
        byte[] hash = hashes == null ? null : hashes.get(id);
        return sharedDefinitions != null && hash != null
                && sharedDefinitions.contains(new nzi.fhir.validator.core.npm.SharedPackage.Key(version, id, hash));
    }

    /**
     * The only source of profiles is the chain built here: core definitions first, then the included IG packages and
     * their resolved dependencies (spec 003, FR-001). Must run on a worker thread.
     */
    static ValidatorSnapshot assemble(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                                      List<NpmPackage> npmPackages) {
        return assemble(SnapshotKey.base(version), revision, resolvedPackages, java.util.Map.of(), npmPackages);
    }

    static ValidatorSnapshot assemble(SnapshotKey key, long revision, List<String> resolvedPackages,
                                      java.util.Map<String, byte[]> hashes, List<NpmPackage> npmPackages) {
        return assemble(key, revision, resolvedPackages, hashes, npmPackages, TerminologyServices.OFFLINE);
    }

    /**
     * Builds the chain for one context (spec 007, spec 010). Order matters: the chain asks modules in turn, so
     * everything that can be decided from packages and built-in code systems is answered locally, and only the rest
     * can reach a terminology server (FR-013, research R4). The one terminology module routes with the request's
     * {@code TerminologyScope} (spec 010 FR-001), so the chain holds definitions only and a setting change never
     * rebuilds it. It only answers terminology operations; it never supplies StructureDefinitions, ValueSets or
     * CodeSystems, so definitions still come from packages only (FR-014, spec 003 FR-002).
     *
     * @param txServices shared terminology infrastructure (allowlist, registry resolver, client pool, code cache)
     */
    static ValidatorSnapshot assemble(SnapshotKey key, long revision, List<String> resolvedPackages,
                                      java.util.Map<String, byte[]> hashes, List<NpmPackage> npmPackages,
                                      TerminologyServices txServices) {
        return assemble(key, revision, resolvedPackages, hashes, npmPackages, txServices, null);
    }

    /**
     * @param sharedDefinitions the instance's shared package definitions, or {@code null} to parse every package for
     *                          this context alone (tests: the 1.0.0 baseline of the spec 010 differential check)
     */
    static ValidatorSnapshot assemble(SnapshotKey key, long revision, List<String> resolvedPackages,
                                      java.util.Map<String, byte[]> hashes, List<NpmPackage> npmPackages,
                                      TerminologyServices txServices, SharedPackageDefinitions sharedDefinitions) {
        SupportedFhirVersion version = key.fhirVersion();
        FhirContext ctx = FhirContextLoader.getInstance().getContext(version);
        List<IValidationSupport> modules = new ArrayList<>();
        modules.add(new DefaultProfileValidationSupport(ctx));
        modules.add(new CustomNpmPackageValidationSupport(ctx, npmPackages, hashes, sharedDefinitions, version));
        // Spec 007 research R4 (verified by TerminologyRoutingIT): the terminology module goes before the in-memory
        // one, which otherwise answers a value set it can't expand (an IG value set including LOINC) with an error
        // before a server is asked. It declines every code system decided locally (complete package CodeSystems and
        // the common code systems), so those are still checked in-process (FR-013). In mode none the chain skips it.
        IValidationSupport requestModule = new TerminologyOnlySupport(
                new RequestScopedTerminologySupport(ctx, version, txServices.clients()));
        modules.add(requestModule);
        modules.add(new InMemoryTerminologyServerValidationSupport(ctx));
        modules.add(new CommonCodeSystemsTerminologyService(ctx));
        modules.add(new SnapshotGeneratingValidationSupport(ctx));
        // Spec 010 FR-012: code results are cached per instance, keyed by snapshot and terminology scope, so one chain
        // serves every setting without sharing answers between servers; HAPI's cache keeps the definitions.
        long snapshotId = ValidatorSnapshot.newSnapshotId();
        ValidationSupportChain chain = new ScopedValidationSupportChain(txServices.chainCache(), modules, snapshotId,
                version, txServices.codeCache(), requestModule, txServices.clients());
        // FR-002 / research R1: no IValidatorResourceFetcher is set, so the validator can never fetch a profile,
        // reference or other conformance resource over the network. Do not add one.
        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(new FhirInstanceValidator(chain));
        ValidatorSnapshot snapshot = new ValidatorSnapshot(snapshotId, key, revision, resolvedPackages, hashes, ctx,
                validator, chain);
        snapshot.setTerminologyServices(txServices);
        return snapshot;
    }
}
