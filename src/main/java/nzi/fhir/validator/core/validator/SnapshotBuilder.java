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
import nzi.fhir.validator.core.service.FhirContextLoader;
import nzi.fhir.validator.core.terminology.DeniedTerminologySupport;
import nzi.fhir.validator.core.terminology.RoutedTerminologySupport;
import nzi.fhir.validator.core.terminology.ServerTerminologySupport;
import nzi.fhir.validator.core.terminology.TerminologyBudget;
import nzi.fhir.validator.core.terminology.TerminologyMode;
import nzi.fhir.validator.core.terminology.TerminologyOnlySupport;
import nzi.fhir.validator.core.terminology.TerminologyServices;
import nzi.fhir.validator.core.terminology.TerminologySettings;
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
     * dependencies, or nothing for the base context), with its terminology setting (spec 007).
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
            for (Future<Loaded> f : loaded) {
                npmPackages.add(f.result().pkg());
                archiveBytes += f.result().archiveBytes();
                expandedBytes += PostgresPackageCacheManager.expandedBytes(f.result().pkg());
            }
            // A build that cannot fit fails like any other (version STALE, reason in /health) instead of ending the
            // process with an OutOfMemoryError.
            long reserved = heap.reserve(expandedBytes,
                    HeapBudget.terminologyCacheBytes(resolved.terminology(), txServices.chainCache().getCacheSize()));
            try {
                ValidatorSnapshot snapshot = assemble(key, revision, resolvedPackages, resolved.hashes(), npmPackages,
                        resolved.terminology(), txServices);
                snapshot.setPackageArchiveBytes(archiveBytes);
                // Warm caches so the first real request does not pay HAPI's lazy initialisation. A budget of 0: the
                // warm-up never calls a terminology server (spec 007 research R5).
                TerminologyBudget.open(0);
                try {
                    snapshot.validate(WARM_UP_RESOURCE, SupportedContentType.JSON, List.of());
                } finally {
                    TerminologyBudget.close();
                }
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
        return assemble(key, revision, resolvedPackages, hashes, npmPackages, TerminologySettings.NONE,
                TerminologyServices.OFFLINE);
    }

    /**
     * Builds the chain for one context (spec 007). Order matters: the chain asks modules in turn, so everything that
     * can be decided from packages and built-in code systems is answered locally, and only the rest can reach a
     * terminology server (FR-013, research R4). A terminology module only answers terminology operations; it never
     * supplies StructureDefinitions, ValueSets or CodeSystems, so definitions still come from packages only (FR-014,
     * spec 003 FR-002).
     *
     * @param tx         the context's terminology setting; {@link TerminologyMode#NONE} keeps it fully offline
     * @param txServices shared terminology infrastructure (allowlist, registry resolver, client pool, code cache)
     */
    static ValidatorSnapshot assemble(SnapshotKey key, long revision, List<String> resolvedPackages,
                                      java.util.Map<String, byte[]> hashes, List<NpmPackage> npmPackages,
                                      TerminologySettings tx, TerminologyServices txServices) {
        SupportedFhirVersion version = key.fhirVersion();
        FhirContext ctx = FhirContextLoader.getInstance().getContext(version);
        List<IValidationSupport> modules = new ArrayList<>();
        modules.add(new DefaultProfileValidationSupport(ctx));
        modules.add(new CustomNpmPackageValidationSupport(ctx, npmPackages));
        // Spec 007 research R4 (verified by TerminologyRoutingIT): the terminology module goes before the in-memory
        // one, which otherwise answers a value set it can't expand (an IG value set including LOINC) with an error
        // before a server is asked. It declines every code system decided locally (complete package CodeSystems and
        // the common code systems), so those are still checked in-process (FR-013).
        switch (tx.mode()) {
            case NONE -> {
                // Spec 003 behaviour: no terminology server, zero outbound requests.
            }
            case SERVER -> modules.add(new TerminologyOnlySupport(serverModule(ctx, version, key, tx, txServices)));
            case ECOSYSTEM -> modules.add(new TerminologyOnlySupport(ecosystemModule(ctx, version, key, tx, txServices)));
        }
        modules.add(new InMemoryTerminologyServerValidationSupport(ctx));
        modules.add(new CommonCodeSystemsTerminologyService(ctx));
        modules.add(new SnapshotGeneratingValidationSupport(ctx));
        // Code results are cached per snapshot: a different server means a different setting, a new snapshot and a
        // fresh cache, so answers are never shared between servers (FR-018).
        ValidationSupportChain chain = tx.mode() == TerminologyMode.NONE
                ? new ValidationSupportChain(txServices.chainCache(), modules)
                : new CacheCountingChain(txServices.chainCache(), modules, version, txServices.clients());
        // FR-002 / research R1: no IValidatorResourceFetcher is set, so the validator can never fetch a profile,
        // reference or other conformance resource over the network. Do not add one.
        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(new FhirInstanceValidator(chain));
        ValidatorSnapshot snapshot = new ValidatorSnapshot(key, revision, resolvedPackages, hashes, ctx, validator, chain);
        snapshot.setTerminology(tx, txServices.requestBudgetMs());
        return snapshot;
    }

    /**
     * The chain of a context with a terminology mode (spec 007 FR-026, T078): a code check that the chain answers
     * without reaching the terminology module was answered from the code cache (the modules before it answer no code
     * checks), so it is counted as a cache hit.
     */
    static final class CacheCountingChain extends ValidationSupportChain {
        private final SupportedFhirVersion version;
        private final nzi.fhir.validator.core.terminology.TerminologyClients clients;

        CacheCountingChain(ValidationSupportChain.CacheConfiguration cache, List<IValidationSupport> modules,
                           SupportedFhirVersion version, nzi.fhir.validator.core.terminology.TerminologyClients clients) {
            super(cache, modules);
            this.version = version;
            this.clients = clients;
        }

        @Override
        public CodeValidationResult validateCode(ca.uhn.fhir.context.support.ValidationSupportContext ctx,
                                                 ca.uhn.fhir.context.support.ConceptValidationOptions options, String system,
                                                 String code, String display, String valueSetUrl) {
            long before = RoutedTerminologySupport.reached();
            CodeValidationResult result = super.validateCode(ctx, options, system, code, display, valueSetUrl);
            count(before, result);
            return result;
        }

        @Override
        public CodeValidationResult validateCodeInValueSet(ca.uhn.fhir.context.support.ValidationSupportContext ctx,
                                                           ca.uhn.fhir.context.support.ConceptValidationOptions options,
                                                           String system, String code, String display,
                                                           org.hl7.fhir.instance.model.api.IBaseResource valueSet) {
            long before = RoutedTerminologySupport.reached();
            CodeValidationResult result = super.validateCodeInValueSet(ctx, options, system, code, display, valueSet);
            count(before, result);
            return result;
        }

        private void count(long before, CodeValidationResult result) {
            if (result != null && RoutedTerminologySupport.reached() == before) {
                clients.codeCacheHit(version);
            }
        }
    }

    /**
     * The module of a context in mode {@code ecosystem} (spec 007 FR-016). Its own server, when set, is checked again
     * like a {@code server} setting; one that is no longer allowed fails closed for terminology only.
     */
    private static RoutedTerminologySupport ecosystemModule(FhirContext ctx, SupportedFhirVersion version, SnapshotKey key,
                                                            TerminologySettings tx, TerminologyServices txServices) {
        if (tx.server() != null) {
            try {
                txServices.allowlist().current().require(tx.server(), version);
            } catch (nzi.fhir.validator.web.ProblemException e) {
                logger.warn("Terminology server of context {} is not on the allowlist; its codes are not checked by a server", key);
                return new DeniedTerminologySupport(ctx, version);
            }
        }
        return new nzi.fhir.validator.core.terminology.EcosystemRoutingTerminologySupport(ctx, version, tx.server(),
                txServices.resolver(), txServices.clients());
    }

    /**
     * The module of a context in mode {@code server} (spec 007 FR-015). The server is checked against the allowlist
     * again here, because the allowlist may have changed since the setting was saved (FR-010 b); a server that is no
     * longer allowed makes the build fail closed for terminology only (US4 scenario 4).
     */
    private static RoutedTerminologySupport serverModule(FhirContext ctx, SupportedFhirVersion version, SnapshotKey key,
                                                         TerminologySettings tx, TerminologyServices txServices) {
        try {
            txServices.allowlist().current().require(tx.server(), version);
            return new ServerTerminologySupport(ctx, version, txServices.clients(), tx.server());
        } catch (nzi.fhir.validator.web.ProblemException e) {
            logger.warn("Terminology server of context {} is not on the allowlist; its codes are not checked by a server", key);
            return new DeniedTerminologySupport(ctx, version);
        }
    }
}
