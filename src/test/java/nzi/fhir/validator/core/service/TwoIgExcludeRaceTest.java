package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.validator.IgRequest;
import nzi.fhir.validator.core.validator.PinnedAccess;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Code review of spec 012: a two-IG context excluded between a request's lookup and its access check is answered as
 * "not included" (400), as for a single-IG context, never as "not stored" (404). The lookup and the access check read
 * the same published set of included contexts.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
class TwoIgExcludeRaceTest {
    private static final String KEY = "race.a#1.0.0|race.b#2.0.0";
    private static final Access SHARED = new Access(Organisations.SYSTEM_ID, "system", true);

    private Executors executors;

    @AfterEach
    void tearDown() {
        if (executors != null) {
            executors.close();
        }
    }

    @Test
    void anExcludeBetweenLookupAndAccessCheckIsNotIncluded(Vertx vertx, VertxTestContext ctx) {
        PackageCatalogView view = new PackageCatalogView(1, Map.of(
                "race.a#1.0.0", new PackageCatalogView.Entry(Set.of(), List.of(), new byte[]{1}, 1, SHARED),
                "race.b#2.0.0", new PackageCatalogView.Entry(Set.of(), List.of(), new byte[]{2}, 1, SHARED)));
        PackageCatalog catalog = new PackageCatalog(() -> Future.succeededFuture(view), System::currentTimeMillis);
        // The registry has already released the excluded context.
        ValidatorRegistry registry = new ValidatorRegistry(null, null) {
            @Override
            public Future<ValidatorSnapshot> snapshot(SnapshotKey key) {
                return Future.failedFuture(ProblemException.igNotIncluded(key.igPackage(), key.fhirVersion()));
            }
        };
        executors = Executors.create(vertx, 2, 30_000, 1);
        FhirValidationService service = new FhirValidationService(registry, executors, new AdmissionController(4));
        service.setCatalog(catalog);
        // The pair is included for the first read of the published set and excluded for every later one.
        PinnedAccess included = PinnedAccess.of(List.of(KEY), Map.of(KEY, SHARED), "system");
        AtomicInteger reads = new AtomicInteger();
        service.setPinnedAccess(v -> reads.getAndIncrement() == 0 ? included : PinnedAccess.EMPTY);

        Caller caller = Caller.of(UUID.randomUUID(), "client", ApiKeyRepository.Role.VALIDATE);
        catalog.current();
        service.validate(caller, SupportedFhirVersion.R4, IgRequest.parse("race.b#2.0.0|race.a#1.0.0"),
                        "{\"resourceType\":\"Patient\"}", SupportedContentType.JSON, List.of(), Function.identity())
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.failed(), "expected a refusal");
                    ProblemException p = assertInstanceOf(ProblemException.class, ar.cause());
                    assertEquals(400, p.status(), p.detail());
                    assertEquals(1, reads.get(), "one read of the published set per request");
                    ctx.completeNow();
                }));
    }
}
