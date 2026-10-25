package nzi.fhir.validator.testsupport;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.ServiceRegistry;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.config.PgConfig;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.core.service.HealthService;

/**
 * The service core (validators, profiles, IG inclusion, configuration sync) without HTTP, wired exactly as in
 * production. Two harnesses on one database behave like two instances.
 */
public final class CoreHarness {
    private final ServiceRegistry services;

    private CoreHarness(ServiceRegistry services) {
        this.services = services;
    }

    public static Future<CoreHarness> start(Vertx vertx, PgConnectOptions db) {
        return start(vertx, db, new JsonObject());
    }

    public static Future<CoreHarness> start(Vertx vertx, PgConnectOptions db, JsonObject overrides) {
        JsonObject json = AppHarness.baseConfig(db.getHost(), db.getPort(), db.getDatabase(), db.getUser(), db.getPassword());
        json.mergeIn(overrides, true);
        ApplicationConfig config = ApplicationConfig.of(json);
        Executors executors = Executors.create(vertx, config);
        Pool pool = PgConfig.createPgPool(vertx, config);
        HealthService health = new HealthService(vertx, pool, config.healthDbStalenessMs());
        ServiceRegistry services = ServiceRegistry.create(vertx, config, pool, executors, health);
        return DatabaseService.migrate(vertx, executors, PgConfig.createPgOptions(config))
                .compose(v -> services.configSync().start())
                .map(v -> new CoreHarness(services));
    }

    public ServiceRegistry services() {
        return services;
    }

    public Future<Void> stop() {
        return services.stop()
                .eventually(() -> services.pool().close())
                .eventually(() -> services.executors().close());
    }
}
