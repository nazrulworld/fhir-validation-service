package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.IValidationContext;
import ca.uhn.fhir.validation.IValidatorModule;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.validator.ProfileStore;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 regression: validations on one instance run in parallel (previously serialised by ordered executeBlocking).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class ConcurrencyIT {

    /** Blocks each validation until {@code parties} validations are running at the same time. */
    private static final class RendezvousModule implements IValidatorModule {
        final CountDownLatch latch;
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();

        RendezvousModule(int parties) {
            latch = new CountDownLatch(parties);
        }

        @Override
        public void validateResource(IValidationContext<IBaseResource> ctx) {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            latch.countDown();
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    SingleValidationMessage m = new SingleValidationMessage();
                    m.setSeverity(ResultSeverityEnum.ERROR);
                    m.setMessage("validations did not run concurrently");
                    ctx.addValidationMessage(m);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
        }
    }

    private static ValidatorRegistry registryServing(ValidatorSnapshot snapshot) {
        return new ValidatorRegistry(null, null, Map.of()) {
            @Override
            public ValidatorSnapshot current(SupportedFhirVersion version) {
                return snapshot;
            }
        };
    }

    static ValidatorSnapshot snapshotWith(IValidatorModule module) {
        FhirContext ctx = FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4);
        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(module);
        ValidationSupportChain chain = new ValidationSupportChain(new ca.uhn.fhir.context.support.DefaultProfileValidationSupport(ctx));
        return new ValidatorSnapshot(SupportedFhirVersion.R4, 1, List.of(), ctx, validator, new FhirInstanceValidator(chain),
                chain, new ProfileStore(SupportedFhirVersion.R4, ctx), List.of());
    }

    // Held in fields: Vert.x closes a worker executor automatically once it becomes unreachable, and the test
    // method returns before the asynchronous work completes.
    private Executors executors;
    private FhirValidationService service;

    @Test
    void validationsRunInParallel(Vertx vertx, VertxTestContext ctx) {
        RendezvousModule module = new RendezvousModule(4);
        executors = Executors.create(vertx, 4, 30_000, 1);
        service = new FhirValidationService(registryServing(snapshotWith(module)), executors, new AdmissionController(16));
        vertx.runOnContext(v -> {
            try {
                List<Future<?>> all = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    all.add(service.validate(SupportedFhirVersion.R4, "{\"resourceType\":\"Patient\"}", SupportedContentType.JSON, List.of())
                            .map(o -> {
                                assertTrue(o.result().isSuccessful(), o.result().getMessages().toString());
                                return o;
                            }));
                }
                Future.all(all).onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                    assertTrue(module.maxRunning.get() >= 4, "max concurrent validations: " + module.maxRunning.get());
                    ctx.completeNow();
                })));
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });
    }
}
