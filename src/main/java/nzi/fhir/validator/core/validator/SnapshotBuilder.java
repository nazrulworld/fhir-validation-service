package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import io.vertx.core.Future;
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

    public SnapshotBuilder(Executors executors, PostgresPackageCacheManager packages, PackageCache cache) {
        this.executors = executors;
        this.packages = packages;
        this.cache = cache;
    }

    public Future<ValidatorSnapshot> build(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                                           ProfileStore profileStore) {
        List<Future<NpmPackage>> loaded = new ArrayList<>();
        for (String idAndVersion : resolvedPackages) {
            loaded.add(load(idAndVersion));
        }
        return Future.all(loaded).compose(all -> executors.packages().executeBlocking(() -> {
            long started = System.currentTimeMillis();
            List<NpmPackage> npmPackages = new ArrayList<>();
            for (Future<NpmPackage> f : loaded) {
                npmPackages.add(f.result());
            }
            ValidatorSnapshot snapshot = assemble(version, revision, resolvedPackages, npmPackages, profileStore);
            // Warm caches so the first real request does not pay HAPI's lazy initialisation.
            snapshot.validate(WARM_UP_RESOURCE, SupportedContentType.JSON, List.of());
            logger.info("Built validator for {} at revision {} with {} package(s) in {} ms", version, revision,
                    npmPackages.size(), System.currentTimeMillis() - started);
            return snapshot;
        }, false));
    }

    /**
     * A parsed package from the cache, or read from the database (event loop: I/O only) and parsed on the package
     * pool.
     */
    private Future<NpmPackage> load(String idAndVersion) {
        NpmPackage cached = cache.get(idAndVersion);
        if (cached != null) {
            return Future.succeededFuture(cached);
        }
        int hash = idAndVersion.indexOf('#');
        return packages.loadRawPackage(idAndVersion.substring(0, hash), idAndVersion.substring(hash + 1))
                .compose(buffer -> {
                    if (buffer == null) {
                        return Future.failedFuture(new MissingPackageException(idAndVersion));
                    }
                    byte[] bytes = buffer.getBytes();
                    return packages.parse(bytes).onSuccess(pkg -> cache.put(idAndVersion, pkg, bytes.length));
                });
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
            snapshot.validate(WARM_UP_RESOURCE, SupportedContentType.JSON, List.of());
            return snapshot;
        }, false);
    }
}
