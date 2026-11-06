package nzi.fhir.validator.core.service;

import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.IgReference;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;

import java.util.List;
import java.util.function.Function;

/**
 * Validates resources with the validator context the request chose: the IG package named by {@code ig}, or the
 * core-only base context (spec 004). Stateless: validators live in {@link ValidatorRegistry}. Work is admitted up to a
 * bound ({@link AdmissionController}) and runs on the {@code fv-validation} pool, unordered, so requests are validated
 * in parallel (fixes P1).
 * <p>
 * The admission slot is taken first and held while the {@code ig} reference is resolved and while the request waits
 * for an on-demand context build, so the number of waiting requests is bounded by {@code FV_VALIDATION_MAX_PENDING}
 * (constitution Principle VI, research R5). Every failure, {@code package-not-found} included, is counted once.
 *
 * @author Md Nazrul Islam
 */
public class FhirValidationService {

    private final ValidatorRegistry registry;
    private final Executors executors;
    private final AdmissionController admission;
    private final int maxBundleEntries;
    private volatile ServiceMetrics metrics;
    private volatile nzi.fhir.validator.core.npm.PackageCatalog catalog =
            new nzi.fhir.validator.core.npm.PackageCatalog(
                    () -> Future.succeededFuture(nzi.fhir.validator.core.npm.PackageCatalogView.EMPTY), System::currentTimeMillis);

    private record Done<T>(boolean valid, T value) {
    }

    public FhirValidationService(ValidatorRegistry registry, Executors executors, AdmissionController admission) {
        this(registry, executors, admission, Integer.MAX_VALUE);
    }

    public FhirValidationService(ValidatorRegistry registry, Executors executors, AdmissionController admission,
                                 int maxBundleEntries) {
        this.registry = registry;
        this.executors = executors;
        this.admission = admission;
        this.maxBundleEntries = maxBundleEntries;
    }

    public void setMetrics(ServiceMetrics metrics) {
        this.metrics = metrics;
    }

    /** The package catalog {@code ig} references are resolved against. */
    public void setCatalog(nzi.fhir.validator.core.npm.PackageCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @return the outcome; fails with {@code DataFormatException} if the body is not parseable FHIR, with a 503
     * problem if the version's validator is not ready or the instance is overloaded
     */
    public Future<ValidatorSnapshot.Outcome> validate(SupportedFhirVersion version, String body,
                                                      SupportedContentType contentType, List<String> profiles) {
        return validate(version, body, contentType, profiles, Function.identity());
    }

    /**
     * Validates and then applies {@code finisher} to the outcome, both on the validation worker, so rendering large
     * results never runs on the event loop (constitution Principle I).
     */
    public <T> Future<T> validate(SupportedFhirVersion version, String body, SupportedContentType contentType,
                                  List<String> profiles, Function<ValidatorSnapshot.Outcome, T> finisher) {
        return validate(version, null, body, contentType, profiles, finisher);
    }

    /**
     * @param ig the request's {@code ig} reference, or {@code null} for the base context
     */
    public <T> Future<T> validate(SupportedFhirVersion version, IgReference ig, String body, SupportedContentType contentType,
                                  List<String> profiles, Function<ValidatorSnapshot.Outcome, T> finisher) {
        long started = System.nanoTime();
        return admission.admit(() -> resolve(version, ig)
                        .compose(registry::snapshot)
                        .compose(snapshot -> executors.validation().executeBlocking(() -> {
                            ValidatorSnapshot.Outcome outcome = snapshot.validate(body, contentType, profiles, maxBundleEntries);
                            return new Done<>(outcome.result().isSuccessful(), finisher.apply(outcome));
                        }, false)))
                .onComplete(ar -> {
                    ServiceMetrics m = metrics;
                    if (m != null) {
                        String outcome = ar.succeeded()
                                ? (ar.result().valid() ? ServiceMetrics.OUTCOME_VALID : ServiceMetrics.OUTCOME_INVALID)
                                : failureOutcome(ar.cause());
                        m.recordValidation(version, outcome, System.nanoTime() - started);
                    }
                })
                .map(Done::value);
    }

    private Future<SnapshotKey> resolve(SupportedFhirVersion version, IgReference ig) {
        return ig == null ? Future.succeededFuture(SnapshotKey.base(version)) : ig.resolve(version, catalog);
    }

    private static String failureOutcome(Throwable cause) {
        if (cause instanceof DataFormatException) {
            return ServiceMetrics.OUTCOME_UNPARSEABLE;
        }
        if (cause instanceof nzi.fhir.validator.web.ProblemException p
                && nzi.fhir.validator.web.ProblemException.TYPE_UNKNOWN_PROFILE.equals(p.type())) {
            return ServiceMetrics.OUTCOME_UNKNOWN_PROFILE;
        }
        if (cause instanceof nzi.fhir.validator.web.ProblemException p
                && nzi.fhir.validator.web.ProblemException.TYPE_PACKAGE_NOT_FOUND.equals(p.type())) {
            return ServiceMetrics.OUTCOME_PACKAGE_NOT_FOUND;
        }
        return ServiceMetrics.OUTCOME_ERROR;
    }

    public ValidatorRegistry registry() {
        return registry;
    }

    public AdmissionController admission() {
        return admission;
    }
}
