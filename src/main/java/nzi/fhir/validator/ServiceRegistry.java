package nzi.fhir.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.config.PgConfig;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import nzi.fhir.validator.core.npm.PackageArchiveGuard;
import nzi.fhir.validator.core.npm.PackageCache;
import nzi.fhir.validator.core.npm.SafePackageFetcher;
import nzi.fhir.validator.core.security.ApiKeyAuthHandler;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.core.service.FhirContextLoader;
import nzi.fhir.validator.core.service.FhirValidationService;
import nzi.fhir.validator.core.service.HealthService;
import nzi.fhir.validator.core.service.IgPackageService;
import nzi.fhir.validator.core.service.ProfileService;
import nzi.fhir.validator.core.validator.ConfigSync;
import nzi.fhir.validator.core.validator.ProfileStore;
import nzi.fhir.validator.core.validator.SnapshotBuilder;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.web.endpoint.ProfileApi;
import nzi.fhir.validator.web.endpoint.ValidationApi;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide services, built once by {@link Bootstrap} and shared by every HTTP verticle instance. Constructing it
 * performs no I/O; {@link ConfigSync#start()} loads profiles and builds the validators.
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
    private final Map<SupportedFhirVersion, ProfileService> profileServices;
    private final ValidationApi validationApi;
    private final ProfileApi profileApi;
    private final ApiKeyRepository apiKeys;
    private final ApiKeyAuthHandler auth;
    private final SafePackageFetcher fetcher;
    private final AtomicInteger boundPort = new AtomicInteger(-1);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean refusingNewRequests = new AtomicBoolean();
    private ServiceMetrics metrics;

    private ServiceRegistry(ApplicationConfig config, Pool pool, Executors executors, HealthService healthService,
                            IgPackageService igPackageService, ValidatorRegistry validatorRegistry, ConfigSync configSync,
                            FhirValidationService validationService, Map<SupportedFhirVersion, ProfileService> profileServices,
                            SafePackageFetcher fetcher) {
        this.config = config;
        this.pool = pool;
        this.executors = executors;
        this.healthService = healthService;
        this.igPackageService = igPackageService;
        this.validatorRegistry = validatorRegistry;
        this.configSync = configSync;
        this.validationService = validationService;
        this.profileServices = profileServices;
        this.fetcher = fetcher;
        this.apiKeys = new ApiKeyRepository(pool);
        this.auth = new ApiKeyAuthHandler(apiKeys, config.authCacheTtlMs(), config.anonymousValidate());
        this.validationApi = new ValidationApi(validationService, igPackageService);
        this.profileApi = new ProfileApi(profileServices);
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
        Map<SupportedFhirVersion, ProfileStore> stores = new EnumMap<>(SupportedFhirVersion.class);
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            stores.put(v, new ProfileStore(v, FhirContextLoader.getInstance().getContext(v)));
        }
        SnapshotBuilder builder = new SnapshotBuilder(executors, packages, new PackageCache(config.packageCacheMaxMb()));
        ValidatorRegistry registry = new ValidatorRegistry(vertx, builder, stores);
        stores.forEach((v, store) -> store.setSnapshotSources(() -> registry.activeSupports(v)));
        ConfigSync sync = new ConfigSync(vertx, pool, PgConfig.createPgOptions(config), executors, registry, stores,
                config.syncPollIntervalMs());
        igPackageService.setApplyLocally(sync::syncNow);
        igPackageService.setLocalApplyWaitMs(Math.min(10_000, config.httpRequestTimeoutMs() / 2));
        Map<SupportedFhirVersion, ProfileService> profileServices = new EnumMap<>(SupportedFhirVersion.class);
        stores.forEach((v, store) -> profileServices.put(v, new ProfileService(pool, executors, store, sync::syncNow)));
        healthService.setValidatorReadiness(registry::notReadyReasons, registry::details);
        PackageCache packageCache = builder.cache();
        ServiceRegistry services = new ServiceRegistry(config, pool, executors, healthService, igPackageService, registry, sync,
                new FhirValidationService(registry, executors, new AdmissionController(config.validationMaxPending()),
                        config.validationMaxBundleEntries()), profileServices,
                fetcher);
        ServiceMetrics metrics = new ServiceMetrics(meterRegistry);
        metrics.bind(services.validationService.admission(), registry, packageCache, stores);
        services.validationService.setMetrics(metrics);
        registry.setBuildFailureListener(metrics::recordBuildFailure);
        services.metrics = metrics;
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

    public ConfigSync configSync() {
        return configSync;
    }

    public FhirValidationService validationService() {
        return validationService;
    }

    public Map<SupportedFhirVersion, ProfileService> profileServices() {
        return profileServices;
    }

    public ValidationApi validationApi() {
        return validationApi;
    }

    public ProfileApi profileApi() {
        return profileApi;
    }

    public ApiKeyRepository apiKeys() {
        return apiKeys;
    }

    public ApiKeyAuthHandler auth() {
        return auth;
    }

    public SafePackageFetcher fetcher() {
        return fetcher;
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
