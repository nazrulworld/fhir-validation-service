package nzi.fhir.validator.core.service;

import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;

import java.util.List;

/**
 * Validates resources with the current validator snapshot of the requested FHIR version. Stateless: validators live
 * in {@link ValidatorRegistry}. Work is admitted up to a bound ({@link AdmissionController}) and runs on the
 * {@code fv-validation} pool, unordered, so requests are validated in parallel (fixes P1).
 *
 * @author Md Nazrul Islam
 */
public class FhirValidationService {

    private final ValidatorRegistry registry;
    private final Executors executors;
    private final AdmissionController admission;
    private volatile ServiceMetrics metrics;

    public FhirValidationService(ValidatorRegistry registry, Executors executors, AdmissionController admission) {
        this.registry = registry;
        this.executors = executors;
        this.admission = admission;
    }

    public void setMetrics(ServiceMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * @return the outcome; fails with {@code DataFormatException} if the body is not parseable FHIR, with a 503
     * problem if the version's validator is not ready or the instance is overloaded
     */
    public Future<ValidatorSnapshot.Outcome> validate(SupportedFhirVersion version, String body,
                                                      SupportedContentType contentType, List<String> profiles) {
        ValidatorSnapshot snapshot;
        try {
            snapshot = registry.current(version);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        long started = System.nanoTime();
        return admission.admit(() -> executors.validation()
                        .executeBlocking(() -> snapshot.validate(body, contentType, profiles), false))
                .onComplete(ar -> {
                    ServiceMetrics m = metrics;
                    if (m != null) {
                        String outcome = ar.succeeded()
                                ? (ar.result().result().isSuccessful() ? ServiceMetrics.OUTCOME_VALID : ServiceMetrics.OUTCOME_INVALID)
                                : (ar.cause() instanceof DataFormatException ? ServiceMetrics.OUTCOME_UNPARSEABLE : ServiceMetrics.OUTCOME_ERROR);
                        m.recordValidation(version, outcome, System.nanoTime() - started);
                    }
                });
    }

    public ValidatorRegistry registry() {
        return registry;
    }

    public AdmissionController admission() {
        return admission;
    }
}
