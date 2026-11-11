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
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorRegistry;

import java.time.Duration;

/**
 * The service's own Prometheus meters (FR-031), exposed at {@code /metrics} together with Vert.x's HTTP and pool
 * metrics when those are enabled on the same registry.
 */
public class ServiceMetrics {
    public static final String OUTCOME_VALID = "valid";
    public static final String OUTCOME_INVALID = "invalid";
    public static final String OUTCOME_UNPARSEABLE = "unparseable";
    public static final String OUTCOME_ERROR = "error";
    /** A requested profile is not defined by the version's core definitions or included IGs (400). */
    public static final String OUTCOME_UNKNOWN_PROFILE = "unknown_profile";
    /** The request's {@code ig} package is not stored (404, spec 004 FR-003). */
    public static final String OUTCOME_PACKAGE_NOT_FOUND = "package_not_found";

    private final PrometheusMeterRegistry registry;
    /**
     * Counters on the validation path, created once per tag combination: building and registering a meter on every
     * request costs measurable latency (spec 004 SC-007).
     */
    private final java.util.concurrent.ConcurrentMap<String, Counter> counters = new java.util.concurrent.ConcurrentHashMap<>();

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
                     PackageCache packageCache) {
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
                    .description("Archive size of the IG packages in the base and pinned validators; a rebuild briefly holds two snapshots")
                    .register(registry);
            for (ValidatorRegistry.Kind kind : ValidatorRegistry.Kind.values()) {
                Gauge.builder("fv.validator.contexts", validators, v -> v.contextCount(version, kind))
                        .tag("fhir_version", version.name()).tag("kind", kindLabel(kind))
                        .description("Validator contexts on this instance (spec 004)").register(registry);
            }
        }
        validators.setEvents(new ValidatorRegistry.Events() {
            @Override
            public void build(SnapshotKey key, ValidatorRegistry.Kind kind, String outcome) {
                Counter.builder("fv.validator.context.builds").tag("fhir_version", key.fhirVersion().name())
                        .tag("kind", kindLabel(kind)).tag("outcome", outcome)
                        .description("Validator context builds by outcome").register(registry).increment();
            }

            @Override
            public void eviction(SnapshotKey key, String reason) {
                Counter.builder("fv.validator.context.evictions").tag("fhir_version", key.fhirVersion().name())
                        .tag("reason", reason).description("Validator contexts dropped, by reason (excluded)")
                        .register(registry).increment();
            }

            @Override
            public void lookup(SnapshotKey key, String result) {
                counter("fv.validator.context.lookups", "Validator context lookups (served from a built context)",
                        "fhir_version", key.fhirVersion().name(), "result", result).increment();
            }
        });
    }

    /**
     * Terminology meters (spec 007 FR-026, contracts §6). Labels never carry codes, value set URLs or IG names: the
     * server label is a host name, the outcome and result labels are fixed words.
     */
    public void bindTerminology(nzi.fhir.validator.core.terminology.TxRegistry txRegistry,
                                nzi.fhir.validator.core.terminology.TerminologyClients clients,
                                nzi.fhir.validator.core.terminology.RegistryResolver resolver) {
        clients.setListener(new nzi.fhir.validator.core.terminology.TerminologyClients.Listener() {
            @Override
            public void call(String server, SupportedFhirVersion version, String outcome, long elapsedNanos) {
                counter("fv.tx.calls", "Terminology server calls by outcome", "server", server,
                        "fhir_version", version.name(), "outcome", outcome).increment();
                if (elapsedNanos > 0) {
                    txTimers.computeIfAbsent(server + '|' + version, k -> Timer.builder("fv.tx.call").tag("server", server)
                            .tag("fhir_version", version.name()).description("Terminology server call duration")
                            .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                            .maximumExpectedValue(Duration.ofSeconds(60)).register(registry)).record(Duration.ofNanos(elapsedNanos));
                }
            }

            @Override
            public void codeCacheHit(SupportedFhirVersion version) {
                counter("fv.tx.code.cache.hits", "Code checks of terminology contexts answered from the code cache",
                        "fhir_version", version.name()).increment();
            }
        });
        resolver.setListener(new nzi.fhir.validator.core.terminology.RegistryResolver.Listener() {
            @Override
            public void lookup(SupportedFhirVersion version, String result) {
                counter("fv.tx.routing.lookups", "Terminology routing lookups: hit (cached), resolved or failed",
                        "fhir_version", version.name(), "result", result).increment();
            }
        });
        for (SupportedFhirVersion version : SupportedFhirVersion.enabled()) {
            for (nzi.fhir.validator.core.terminology.TxServerAllowlist.Source source
                    : nzi.fhir.validator.core.terminology.TxServerAllowlist.Source.values()) {
                Gauge.builder("fv.tx.allowlist.age", txRegistry, r -> r.current().list(version)
                                .filter(l -> l.source() == source && l.fetchedAt() != null)
                                .map(l -> (double) Duration.between(l.fetchedAt(), java.time.Instant.now()).toSeconds())
                                .orElse(Double.NaN))
                        .tag("fhir_version", version.name()).tag("source", source.wire()).baseUnit("seconds")
                        .description("Age of the terminology server list in use (NaN when another source is in use)")
                        .register(registry);
            }
        }
    }

    private final java.util.Map<String, Timer> txTimers = new java.util.concurrent.ConcurrentHashMap<>();

    private static String kindLabel(ValidatorRegistry.Kind kind) {
        return switch (kind) {
            case BASE -> "base";
            case PINNED -> "pinned";
        };
    }

    public void recordValidation(SupportedFhirVersion version, String outcome, long nanos) {
        counter("fv.validations", "Validation requests by outcome", "version", version.name(), "outcome", outcome).increment();
        timers.computeIfAbsent(version, v -> Timer.builder("fv.validation").tag("version", v.name())
                .description("Validation duration").publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry)).record(Duration.ofNanos(nanos));
    }

    private final java.util.Map<SupportedFhirVersion, Timer> timers = new java.util.concurrent.ConcurrentHashMap<>();

    /** A counter registered once per name and tag values ({@code tags} alternates keys and values). */
    private Counter counter(String name, String description, String... tags) {
        return counters.computeIfAbsent(name + '|' + String.join("|", tags),
                k -> Counter.builder(name).tags(tags).description(description).register(registry));
    }

    public void recordBuildFailure(SupportedFhirVersion version) {
        Counter.builder("fv.snapshot.build.failures").tag("version", version.name())
                .description("Failed validator builds").register(registry).increment();
    }

    // --- Spec 008 (FR-020, research R11): resources per validation and usage-sink health. No key or org tags. -------

    /**
     * Summaries cached per version (a read-only map filled once) and outcome, so recording builds no keys or strings
     * per validation (code review).
     */
    private final java.util.Map<SupportedFhirVersion, java.util.concurrent.ConcurrentMap<String, io.micrometer.core.instrument.DistributionSummary>> cpuSummaries =
            perVersion();
    private final java.util.Map<SupportedFhirVersion, java.util.concurrent.ConcurrentMap<String, io.micrometer.core.instrument.DistributionSummary>> allocSummaries =
            perVersion();

    private static java.util.Map<SupportedFhirVersion, java.util.concurrent.ConcurrentMap<String, io.micrometer.core.instrument.DistributionSummary>> perVersion() {
        java.util.EnumMap<SupportedFhirVersion, java.util.concurrent.ConcurrentMap<String, io.micrometer.core.instrument.DistributionSummary>> m =
                new java.util.EnumMap<>(SupportedFhirVersion.class);
        for (SupportedFhirVersion v : SupportedFhirVersion.values()) {
            m.put(v, new java.util.concurrent.ConcurrentHashMap<>());
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * CPU time and bytes allocated by one validation's work, by version and outcome; a value below 0 (unknown or not
     * reached) is skipped.
     */
    public void recordValidationResources(SupportedFhirVersion version, String outcome, long cpuNanos, long allocatedBytes) {
        if (cpuNanos >= 0) {
            cpuSummaries.get(version).computeIfAbsent(outcome, o -> summary("fv.validation.cpu", "seconds",
                    "CPU time of the validation work", version, o)).record(cpuNanos / 1e9);
        }
        if (allocatedBytes >= 0) {
            allocSummaries.get(version).computeIfAbsent(outcome, o -> summary("fv.validation.allocated", "bytes",
                    "Bytes allocated by the validation work (shared heap, not resident)", version, o)).record(allocatedBytes);
        }
    }

    private io.micrometer.core.instrument.DistributionSummary summary(String name, String unit, String description,
                                                                       SupportedFhirVersion version, String outcome) {
        return io.micrometer.core.instrument.DistributionSummary.builder(name).baseUnit(unit).description(description)
                .tag("version", version.name()).tag("outcome", outcome).register(registry);
    }

    /** Usage records written by the db sink. */
    public void usageWritten(long n) {
        counter("fv.usage.written", "Usage records written").increment(n);
    }

    /**
     * Usage records dropped: {@code queue_full}, {@code write_failed}, {@code closed}, {@code unattributed} or
     * {@code log_full} (the log sink's queue).
     */
    public void usageDropped(String reason, long n) {
        counter("fv.usage.dropped", "Usage records dropped", "reason", reason).increment(n);
    }

    /** Records waiting in the db sink's queue. */
    public void bindUsageQueue(java.util.Collection<?> queue) {
        Gauge.builder("fv.usage.queue.size", queue, java.util.Collection::size).description("Usage records waiting to be written")
                .register(registry);
    }
}
