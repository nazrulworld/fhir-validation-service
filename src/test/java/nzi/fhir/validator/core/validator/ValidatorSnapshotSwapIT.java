package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B4/B5 regression, as redefined by spec 004 (US2): including a second version of an IG adds a separate context and
 * leaves the first one untouched; each version's validation sees only its own content, and validations running while
 * the second version is included are never affected by it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 180, timeUnit = TimeUnit.SECONDS)
class ValidatorSnapshotSwapIT extends BaseTestContainer {
    private static final String P1 = "http://example.org/fhir/StructureDefinition/only-in-v1";
    private static final String P2 = "http://example.org/fhir/StructureDefinition/only-in-v2";

    private CoreHarness core;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        resetSchema();
        // 200 concurrent validations: raise the admission limit (load shedding is tested in AdmissionControllerTest).
        CoreHarness.start(vertx, connectOptions(), new io.vertx.core.json.JsonObject()
                        .put("validation", new io.vertx.core.json.JsonObject().put("workers", 4).put("maxPending", 400)))
                .compose(h -> {
                    core = h;
                    var ig = h.services().igPackageService();
                    return ig.registerIg(TestPackages.npmPackage("example.swap", "1.0.0", Map.of(),
                                    List.of(TestPackages.requiredPatientElements(P1, "birthDate").put("id", "p1"))))
                            .compose(p -> ig.registerIg(TestPackages.npmPackage("example.swap", "2.0.0", Map.of(),
                                    List.of(TestPackages.requiredPatientElements(P2, "gender").put("id", "p2")))));
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private Future<String> classify(String ig, String profile) {
        return core.services().validationService()
                .validate(nzi.fhir.validator.core.security.Caller.system("system"), SupportedFhirVersion.R4, IgReference.parse(ig), TestPackages.minimalPatient(), SupportedContentType.JSON,
                        List.of(profile), java.util.function.Function.identity())
                .map(o -> {
                    boolean constraint = o.result().getMessages().stream().anyMatch(m ->
                            m.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal() && m.getMessage().contains("birthDate"));
                    return constraint ? "OLD" : "MIXED:" + o.result().getMessages();
                })
                // Since 2.0.0 an unresolved requested profile rejects the request instead of adding a message.
                .recover(err -> err instanceof nzi.fhir.validator.web.ProblemException p
                        && nzi.fhir.validator.web.ProblemException.TYPE_UNKNOWN_PROFILE.equals(p.type())
                        ? Future.succeededFuture("NEW")
                        : Future.failedFuture(err));
    }

    @Test
    void secondVersionIsAddedBesideTheFirstWhichKeepsServing(Vertx vertx, VertxTestContext ctx) {
        var ig = core.services().igPackageService();
        ValidatorRegistry registry = core.services().validatorRegistry();
        SnapshotKey v1 = SnapshotKey.of(SupportedFhirVersion.R4, "example.swap#1.0.0");
        SnapshotKey v2 = SnapshotKey.of(SupportedFhirVersion.R4, "example.swap#2.0.0");
        ValidatorSnapshot[] v1Snapshot = new ValidatorSnapshot[1];
        ig.include(SupportedFhirVersion.R4, "example.swap", "1.0.0")
                .compose(r -> classify("example.swap#1.0.0", P1))
                .compose(before -> {
                    ctx.verify(() -> assertEquals("OLD", before, "v1 profile must apply"));
                    v1Snapshot[0] = registry.current(v1);
                    List<Future<String>> during = new ArrayList<>();
                    for (int i = 0; i < 200; i++) {
                        during.add(classify("example.swap#1.0.0", P1));
                    }
                    List<Future<?>> all = new ArrayList<>(during);
                    all.add(ig.include(SupportedFhirVersion.R4, "example.swap", "2.0.0"));
                    return Future.all(all).map(done -> during);
                })
                .compose(during -> {
                    ctx.verify(() -> during.forEach(f -> assertEquals("OLD", f.result(),
                            "validations against v1 are unaffected while v2 is included")));
                    return Future.all(classify("example.swap#2.0.0", P1), classify("example.swap#1.0.0", P1));
                })
                .onComplete(ctx.succeeding(after -> ctx.verify(() -> {
                    assertEquals("NEW", after.resultAt(0), "v1-only content is not visible through v2");
                    assertEquals("OLD", after.resultAt(1), "v1 keeps validating with its own content");
                    assertSame(v1Snapshot[0], registry.current(v1), "including v2 did not rebuild v1 (SC-005)");
                    assertEquals(ValidatorRegistry.Kind.PINNED, registry.kind(v1));
                    assertEquals(ValidatorRegistry.Kind.PINNED, registry.kind(v2));
                    assertEquals(List.of("example.swap#2.0.0"), registry.current(v2).resolvedPackages());
                    assertEquals(List.of(), registry.current(SupportedFhirVersion.R4).resolvedPackages(),
                            "the base context holds no IG");
                    ctx.completeNow();
                })));
    }

    @Test
    void includeOfUnknownPackageIs404(VertxTestContext ctx) {
        core.services().igPackageService().include(SupportedFhirVersion.R4, "no.such.package", "1.0.0")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertEquals(404, ((nzi.fhir.validator.web.ProblemException) err).status());
                    ctx.completeNow();
                })));
    }

    @Test
    void includeReportsResolvedSet(VertxTestContext ctx) {
        core.services().igPackageService().include(SupportedFhirVersion.R4, "example.swap", "latest")
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(new JsonArray().add("example.swap#2.0.0"), r.getJsonArray("resolved"));
                    assertTrue(r.getLong("revision") > 1);
                    ctx.completeNow();
                })));
    }
}
