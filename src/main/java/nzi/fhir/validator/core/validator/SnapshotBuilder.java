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
import nzi.fhir.validator.core.service.CustomNpmPackageValidationSupport;
import nzi.fhir.validator.core.service.CustomProfileValidationSupport;
import nzi.fhir.validator.core.service.FhirContextLoader;
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
 * the remaining worker stays free for profile parsing and refresh, re-assembly after profile changes, upload parsing
 * and DNS resolution, so profile changes keep propagating while validators rebuild (FR-008, SC-002).
 */
public class SnapshotBuilder {
    private static final Logger logger = LogManager.getLogger(SnapshotBuilder.class);
    private static final String WARM_UP_RESOURCE = "{\"resourceType\":\"Patient\"}";

    /**
     * A resolved package is no longer stored.
     */
    public static class MissingPackageException extends RuntimeException {
        public MissingPackageException(String idAndVersion) {
            super("Package " + idAndVersion + " is not stored", null, false, false);
        }
    }

    private final Executors executors;
    private final PostgresPackageCacheManager packages;
    private final PackageCache cache;
    private final AsyncLimiter builds;
    private final HeapBudget heap;
    private volatile Runnable buildHook;

    /**
     * @param packageWorkers size of the {@code fv-packages} pool ({@code FV_PACKAGE_WORKERS})
     * @param heap           refuses builds that would not fit in the heap
     */
    public SnapshotBuilder(Executors executors, PostgresPackageCacheManager packages, PackageCache cache, int packageWorkers,
                           HeapBudget heap) {
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

    public Future<ValidatorSnapshot> build(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                                           ProfileStore profileStore) {
        return builds.run(() -> buildNow(version, revision, resolvedPackages, profileStore));
    }

    private Future<ValidatorSnapshot> buildNow(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                                               ProfileStore profileStore) {
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
            for (Future<Loaded> f : loaded) {
                npmPackages.add(f.result().pkg());
                archiveBytes += f.result().archiveBytes();
                expandedBytes += PostgresPackageCacheManager.expandedBytes(f.result().pkg());
            }
            // A build that cannot fit fails like any other (version STALE, reason in /health) instead of ending the
            // process with an OutOfMemoryError.
            long reserved = heap.reserve(expandedBytes);
            try {
                ValidatorSnapshot snapshot = assemble(version, revision, resolvedPackages, npmPackages, profileStore);
                completeProfiles(snapshot);
                snapshot.setPackageArchiveBytes(archiveBytes);
                // Warm caches so the first real request does not pay HAPI's lazy initialisation.
                snapshot.validate(WARM_UP_RESOURCE, SupportedContentType.JSON, List.of());
                logger.info("Built validator for {} at revision {} with {} package(s) in {} ms", version, revision,
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

    /**
     * Profiles stored before their base IG package was available still lack a snapshot: generate it against this
     * snapshot's packages before the snapshot is published (see {@link SnapshotPregenerator}).
     */
    private static void completeProfiles(ValidatorSnapshot snapshot) {
        int completed = snapshot.profileStore().ensureSnapshots(snapshot.reusableSupports());
        if (completed > 0) {
            logger.info("Generated snapshots for {} stored profile(s) of {}", completed, snapshot.version());
        }
    }

    /** Test hook: runs on the package worker at the start of every full build (e.g. to simulate a slow one). */
    public void setBuildHook(Runnable hook) {
        this.buildHook = hook;
    }

    public PackageCache cache() {
        return cache;
    }

    static ValidatorSnapshot assemble(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                                      List<NpmPackage> npmPackages, ProfileStore profileStore) {
        FhirContext ctx = FhirContextLoader.getInstance().getContext(version);
        List<IValidationSupport> reusable = List.of(
                new DefaultProfileValidationSupport(ctx),
                new CustomNpmPackageValidationSupport(ctx, npmPackages));
        return assembleWith(version, revision, resolvedPackages, reusable, profileStore);
    }

    private static ValidatorSnapshot assembleWith(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                                                  List<IValidationSupport> reusable, ProfileStore profileStore) {
        FhirContext ctx = FhirContextLoader.getInstance().getContext(version);
        ValidationSupportChain chain = new ValidationSupportChain(
                reusable.get(0),
                new CustomProfileValidationSupport(ctx, profileStore),
                reusable.get(1),
                new InMemoryTerminologyServerValidationSupport(ctx),
                new CommonCodeSystemsTerminologyService(ctx),
                new SnapshotGeneratingValidationSupport(ctx));
        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(chain);
        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(instanceValidator);
        return new ValidatorSnapshot(version, revision, resolvedPackages, ctx, validator, instanceValidator, chain,
                profileStore, reusable);
    }

    /**
     * A fresh validator over the same packages, used after profile changes: HAPI caches conformance resources (and
     * "not found" answers) inside the chain and validator, so new or updated profiles need fresh instances. Cheap:
     * the parsed core definitions and IG packages are shared, nothing is re-read or re-parsed.
     */
    public Future<ValidatorSnapshot> reassemble(ValidatorSnapshot current) {
        return executors.packages().executeBlocking(() -> {
            ValidatorSnapshot snapshot = assembleWith(current.version(), current.revision(), current.resolvedPackages(),
                    current.reusableSupports(), current.profileStore());
            completeProfiles(snapshot);
            snapshot.setPackageArchiveBytes(current.packageArchiveBytes());
            snapshot.validate(WARM_UP_RESOURCE, SupportedContentType.JSON, List.of());
            return snapshot;
        }, false);
    }
}
