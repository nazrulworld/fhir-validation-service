package nzi.fhir.validator.core.service;

import ca.uhn.fhir.validation.IValidationContext;
import ca.uhn.fhir.validation.IValidatorModule;
import io.reactiverse.contextual.logging.ContextualData;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.validator.IgReference;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Constitution VII / spec 004 T068 follow-up: a validation request keeps its request id (Vert.x contextual data) for
 * every step, also when its ig lookup joins a catalog refresh that completes on another context (e.g. one started by
 * the background catalog sync). Found by CI on #48: build log lines carried an empty id.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
class ValidationContextPropagationTest {
    private Executors executors;

    @AfterEach
    void tearDown() {
        if (executors != null) {
            executors.close();
        }
    }

    @Test
    void stepsAfterACatalogRefreshOnAnotherContextKeepTheRequestId(Vertx vertx, VertxTestContext ctx) {
        List<String> seen = new CopyOnWriteArrayList<>();
        // A refresh that completes on a different event-loop context than the request's.
        Context other = vertx.getOrCreateContext();
        PackageCatalogView view = new PackageCatalogView(2, Map.of("ctx.ig#1.0.0",
                new PackageCatalogView.Entry(java.util.Set.of(), List.of(), new byte[]{1}, 1)));
        PackageCatalog catalog = new PackageCatalog(() -> {
            Promise<PackageCatalogView> p = Promise.promise();
            other.runOnContext(v -> p.complete(view));
            return p.future();
        }, System::currentTimeMillis);

        IValidatorModule recorder = new IValidatorModule() {
            @Override
            public void validateResource(IValidationContext<IBaseResource> c) {
                seen.add("validate=" + ContextualData.get("requestId"));
            }
        };
        ValidatorSnapshot snapshot = ConcurrencyIT.snapshotWith(recorder);
        ValidatorRegistry registry = new ValidatorRegistry(null, null) {
            @Override
            public Future<ValidatorSnapshot> snapshot(SnapshotKey key) {
                seen.add("snapshot=" + ContextualData.get("requestId"));
                return Future.succeededFuture(snapshot);
            }
        };
        executors = Executors.create(vertx, 2, 30_000, 1);
        FhirValidationService service = new FhirValidationService(registry, executors, new AdmissionController(4));
        service.setCatalog(catalog);
        // Spec 007 FR-021: ctx.ig is an included context.
        service.setPinnedAccess(v -> nzi.fhir.validator.core.validator.PinnedAccess.of(List.of("ctx.ig#1.0.0"), Map.of(), "system"));

        ContextInternal request = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        request.runOnContext(v -> {
            ContextualData.put("requestId", "req-ctx-1");
            service.validate(nzi.fhir.validator.core.security.Caller.system("system"), SupportedFhirVersion.R4, IgReference.parse("ctx.ig#1.0.0"),
                            "{\"resourceType\":\"Patient\"}", SupportedContentType.JSON, List.of(), Function.identity())
                    .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                        assertEquals(List.of("snapshot=req-ctx-1", "validate=req-ctx-1"), seen);
                        ctx.completeNow();
                    })));
        });
    }
}
