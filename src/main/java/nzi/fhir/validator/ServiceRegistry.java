package nzi.fhir.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.config.PgConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import nzi.fhir.validator.core.npm.PackageArchiveGuard;
import nzi.fhir.validator.core.npm.PackageCache;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.SafePackageFetcher;
import nzi.fhir.validator.core.org.OrganisationRepository;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.core.service.FhirValidationService;
import nzi.fhir.validator.core.service.HealthService;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.validator.ConfigSync;
import nzi.fhir.validator.core.validator.HeapBudget;
import nzi.fhir.validator.core.validator.SnapshotBuilder;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.web.endpoint.ValidationApi;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide services, built once by {@link Bootstrap} and shared by every HTTP verticle instance. Constructing it
 * performs no I/O; {@link ConfigSync#start()} builds the validators.
 */
public final class ServiceRegistry {
    private final ApplicationConfig config;
    private final Pool pool;
    private final Executors executors;
    private final HealthService healthService;
    private final IgPackageService igPackageService;
    private final ValidatorRegistry validatorRegistry;
    private final ConfigSync configSync;
    private final FhirValidationService validationService;
    private final ValidationApi validationApi;
    private final ApiKeyRepository apiKeys;
    private final OrganisationRepository organisations;
    private final ApiKeyAuthHandler auth;
    private final SafePackageFetcher fetcher;
    private final AdmissionController adminAdmission;
    private SnapshotBuilder snapshotBuilder;
    private HeapBudget heapBudget;
    private final AtomicInteger boundPort = new AtomicInteger(-1);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean refusingNewRequests = new AtomicBoolean();
    private ServiceMetrics metrics;

    private ServiceRegistry(ApplicationConfig config, Pool pool, Executors executors, HealthService healthService,
                            IgPackageService igPackageService, ValidatorRegistry validatorRegistry, ConfigSync configSync,
                            FhirValidationService validationService, SafePackageFetcher fetcher,
                            AdmissionController adminAdmission) {
        this.config = config;
        this.pool = pool;
        this.executors = executors;
        this.healthService = healthService;
        this.igPackageService = igPackageService;
        this.validatorRegistry = validatorRegistry;
        this.configSync = configSync;
        this.validationService = validationService;
        this.fetcher = fetcher;
        this.adminAdmission = adminAdmission;
        this.apiKeys = new ApiKeyRepository(pool);
        this.organisations = new OrganisationRepository(pool);
        this.auth = new ApiKeyAuthHandler(apiKeys, config.authCacheTtlMs(), config.anonymousValidate());
        this.validationApi = new ValidationApi(validationService, igPackageService);
    }

    public static ServiceRegistry create(Vertx vertx, ApplicationConfig config, Pool pool, Executors executors,
                                         HealthService healthService) {
        return create(vertx, config, pool, executors, healthService, ServiceMetrics.newRegistry());
    }

    public static ServiceRegistry create(Vertx vertx, ApplicationConfig config, Pool pool, Executors executors,
                                         HealthService healthService, PrometheusMeterRegistry meterRegistry) {
        SafePackageFetcher fetcher = SafePackageFetcher.create(vertx, executors.packages(), config);
        PostgresPackageCacheManager packages = new PostgresPackageCacheManager(pool, executors.packages(),
                PackageArchiveGuard.from(config), fetcher, config.packageRegistryUrls(), config.packageRegistryEnabled());
        IgPackageService igPackageService = IgPackageService.create(vertx, pool, packages);
        HeapBudget heap = HeapBudget.ofRuntime();
        SnapshotBuilder builder = new SnapshotBuilder(executors, packages, new PackageCache(config.packageCacheMaxMb()),
                config.packageWorkers(), heap);
        AdmissionController adminAdmission = new AdmissionController(config.adminMaxPending(),
                "Too many administrative operations in progress; retry shortly");
        // On-demand context builds share the administrative admission limit (spec 004 FR-014).
        ValidatorRegistry registry = new ValidatorRegistry(vertx, builder, config.igMaxOnDemand(), config.igBuildWaitMs(),
                adminAdmission);
        PackageCatalog catalog = PackageCatalog.of(pool);
        ConfigSync sync = new ConfigSync(vertx, pool, PgConfig.createPgOptions(config), registry, catalog, builder.cache(),
                config.syncPollIntervalMs());
        // A package stored here is visible at once on this instance; other instances follow the PACKAGE notification.
        packages.setStoreListener(key -> {
            catalog.markStale();
            sync.syncCatalog();
        });
        igPackageService.setApplyLocally(sync::syncNow);
        igPackageService.setAdmitBuild(expandedBytes -> executors.packages().executeBlocking(() -> {
            heap.admitInclude(expandedBytes, registry.evictor());
            return null;
        }, false));
        long localApplyWaitMs = Math.min(10_000, config.httpRequestTimeoutMs() / 2);
        igPackageService.setLocalApplyWaitMs(localApplyWaitMs);
        healthService.setValidatorReadiness(registry::notReadyReasons, registry::details);
        healthService.setCatalogRevision(() -> catalog.current().revision());
        PackageCache packageCache = builder.cache();
        FhirValidationService validationService = new FhirValidationService(registry, executors,
                new AdmissionController(config.validationMaxPending()), config.validationMaxBundleEntries());
        validationService.setCatalog(catalog);
        // Spec 005: access of included contexts for the validation hot path, and the health filter (research R12).
        validationService.setPinnedAccess(sync::pinnedAccess);
        registry.setPublicFilter((key, resolved) -> nzi.fhir.validator.core.security.AccessPolicy.isPublic(key, resolved,
                catalog.current(), sync.pinnedAccess(key.fhirVersion())));
        ServiceRegistry services = new ServiceRegistry(config, pool, executors, healthService, igPackageService, registry, sync,
                validationService, fetcher, adminAdmission);
        ServiceMetrics metrics = new ServiceMetrics(meterRegistry);
        metrics.bind(services.validationService.admission(), services.adminAdmission, registry, packageCache);
        services.validationService.setMetrics(metrics);
        registry.setBuildFailureListener(metrics::recordBuildFailure);
        services.metrics = metrics;
        services.snapshotBuilder = builder;
        services.heapBudget = heap;
        return services;
    }

    public Future<Void> stop() {
        validatorRegistry.close();
        healthService.stop();
        return configSync.stop();
    }

    public ApplicationConfig config() {
        return config;
    }

    public Pool pool() {
        return pool;
    }

    public Executors executors() {
        return executors;
    }

    public HealthService healthService() {
        return healthService;
    }

    public IgPackageService igPackageService() {
        return igPackageService;
    }

    public ValidatorRegistry validatorRegistry() {
        return validatorRegistry;
    }

    public HeapBudget heapBudget() {
        return heapBudget;
    }

    public SnapshotBuilder snapshotBuilder() {
        return snapshotBuilder;
    }

    public ConfigSync configSync() {
        return configSync;
    }

    public FhirValidationService validationService() {
        return validationService;
    }

    public ValidationApi validationApi() {
        return validationApi;
    }

    public ApiKeyRepository apiKeys() {
        return apiKeys;
    }

    /** Organisations (spec 005). */
    public OrganisationRepository organisations() {
        return organisations;
    }

    public ApiKeyAuthHandler auth() {
        return auth;
    }

    public SafePackageFetcher fetcher() {
        return fetcher;
    }

    /** Admission limit for administrative operations ({@code FV_ADMIN_MAX_PENDING}), separate from validation. */
    public AdmissionController adminAdmission() {
        return adminAdmission;
    }

    public ServiceMetrics metrics() {
        return metrics;
    }

    /** Requests currently being served (all HTTP verticle instances). */
    public AtomicInteger inFlight() {
        return inFlight;
    }

    /** Set once a drain has passed its pre-drain window: new requests are answered 503 (FR-029). */
    public AtomicBoolean refusingNewRequests() {
        return refusingNewRequests;
    }

    /** The port the HTTP server actually listens on (useful when configured with port 0). */
    public int boundPort() {
        return boundPort.get();
    }

    public void setBoundPort(int port) {
        boundPort.set(port);
    }
}
