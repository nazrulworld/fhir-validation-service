package nzi.fhir.validator.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCache;
import nzi.fhir.validator.core.validator.ProfileStore;
import nzi.fhir.validator.core.validator.ValidatorRegistry;

import java.time.Duration;
import java.util.Map;

/**
 * The service's own Prometheus meters (FR-031), exposed at {@code /metrics} together with Vert.x's HTTP and pool
 * metrics when those are enabled on the same registry.
 */
public class ServiceMetrics {
    public static final String OUTCOME_VALID = "valid";
    public static final String OUTCOME_INVALID = "invalid";
    public static final String OUTCOME_UNPARSEABLE = "unparseable";
    public static final String OUTCOME_ERROR = "error";

    private final PrometheusMeterRegistry registry;

    public ServiceMetrics(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    public static PrometheusMeterRegistry newRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    public String scrape() {
        return registry.scrape();
    }

    /** Gauges and function counters over live service state. */
    public void bind(AdmissionController admission, AdmissionController adminAdmission, ValidatorRegistry validators,
                     PackageCache packageCache, Map<SupportedFhirVersion, ProfileStore> profileStores) {
        Gauge.builder("fv.validation.pending", admission, AdmissionController::pending)
                .description("Validations running or queued").register(registry);
        Gauge.builder("fv.validation.max.pending", admission, AdmissionController::maxPending)
                .description("Admission limit (FV_VALIDATION_MAX_PENDING)").register(registry);
        Gauge.builder("fv.admin.pending", adminAdmission, AdmissionController::pending)
                .description("Administrative operations in progress").register(registry);
        Gauge.builder("fv.admin.max.pending", adminAdmission, AdmissionController::maxPending)
                .description("Admission limit for administrative operations (FV_ADMIN_MAX_PENDING)").register(registry);
        Gauge.builder("fv.package.cache.size", packageCache, PackageCache::weightBytes)
                .baseUnit("bytes").description("Estimated memory of cached IG packages").register(registry);
        FunctionCounter.builder("fv.package.cache.hits", packageCache, PackageCache::hits)
                .description("IG package cache hits").register(registry);
        FunctionCounter.builder("fv.package.cache.misses", packageCache, PackageCache::misses)
                .description("IG package cache misses").register(registry);
        for (SupportedFhirVersion version : SupportedFhirVersion.enabled()) {
            Gauge.builder("fv.config.revision", validators, v -> v.appliedRevision(version))
                    .tag("version", version.name()).description("Configuration revision applied on this instance").register(registry);
            Gauge.builder("fv.validator.ready", validators, v -> v.status(version) == ValidatorRegistry.Status.ACTIVE ? 1 : 0)
                    .tag("version", version.name()).description("1 when the version's validator serves requests").register(registry);
            Gauge.builder("fv.snapshot.packages", validators, v -> v.snapshotPackages(version))
                    .tag("version", version.name()).description("IG packages in the active validator").register(registry);
            Gauge.builder("fv.snapshot.package.size", validators, v -> v.snapshotPackageBytes(version))
                    .tag("version", version.name()).baseUnit("bytes")
                    .description("Archive size of the IG packages in the active validator; a rebuild briefly holds two snapshots")
                    .register(registry);
            ProfileStore store = profileStores.get(version);
            if (store != null) {
                Gauge.builder("fv.profile.count", store, ProfileStore::size)
                        .tag("version", version.name()).description("Custom profiles loaded").register(registry);
            }
        }
    }

    public void recordValidation(SupportedFhirVersion version, String outcome, long nanos) {
        Counter.builder("fv.validations").tag("version", version.name()).tag("outcome", outcome)
                .description("Validation requests by outcome").register(registry).increment();
        Timer.builder("fv.validation").tag("version", version.name())
                .description("Validation duration").publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry).record(Duration.ofNanos(nanos));
    }

    public void recordBuildFailure(SupportedFhirVersion version) {
        Counter.builder("fv.snapshot.build.failures").tag("version", version.name())
                .description("Failed validator builds").register(registry).increment();
    }
}
