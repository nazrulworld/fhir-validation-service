package nzi.fhir.validator.core.concurrency;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import nzi.fhir.validator.core.config.ApplicationConfig;

import java.util.concurrent.TimeUnit;

/**
 * Named worker pools that keep CPU-heavy and blocking work off the event loop (constitution Principle I).
 * <ul>
 *     <li>{@code fv-validation}: resource parsing and HAPI validation</li>
 *     <li>{@code fv-packages}: IG package decompression/parsing, validator builds, Flyway</li>
 * </ul>
 */
public final class Executors {
    public static final String VALIDATION_POOL = "fv-validation";
    public static final String PACKAGES_POOL = "fv-packages";

    private final WorkerExecutor validation;
    private final WorkerExecutor packages;

    private Executors(WorkerExecutor validation, WorkerExecutor packages) {
        this.validation = validation;
        this.packages = packages;
    }

    public static Executors create(Vertx vertx, ApplicationConfig config) {
        return create(vertx, config.validationWorkers(), config.validationTimeoutMs(), config.packageWorkers());
    }

    public static Executors create(Vertx vertx, int validationWorkers, long validationTimeoutMs, int packageWorkers) {
        WorkerExecutor validation = vertx.createSharedWorkerExecutor(
                VALIDATION_POOL, validationWorkers, validationTimeoutMs, TimeUnit.MILLISECONDS);
        // Package work (large IG builds, Flyway) can legitimately take minutes.
        WorkerExecutor packages = vertx.createSharedWorkerExecutor(PACKAGES_POOL, packageWorkers, 30, TimeUnit.MINUTES);
        return new Executors(validation, packages);
    }

    public WorkerExecutor validation() {
        return validation;
    }

    public WorkerExecutor packages() {
        return packages;
    }

    public Future<Void> close() {
        return Future.all(validation.close(), packages.close()).mapEmpty();
    }
}
