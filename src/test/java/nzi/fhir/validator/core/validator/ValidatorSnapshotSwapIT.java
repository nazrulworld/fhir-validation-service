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
 * B4/B5 regression: an IG upgrade replaces the validator atomically and leaves no content of the old version.
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

    private Future<String> classify(String profile) {
        return core.services().validationService()
                .validate(SupportedFhirVersion.R4, TestPackages.minimalPatient(), SupportedContentType.JSON, List.of(profile))
                .map(o -> {
                    boolean constraint = o.result().getMessages().stream().anyMatch(m ->
                            m.getSeverity().ordinal() >= ResultSeverityEnum.ERROR.ordinal() && m.getMessage().contains("birthDate"));
                    boolean unknown = o.result().getMessages().stream().anyMatch(m ->
                            "Validation_VAL_Profile_Unknown".equals(m.getMessageId()) && m.getMessage().contains(profile));
                    if (constraint == unknown) {
                        return "MIXED:" + o.result().getMessages();
                    }
                    return constraint ? "OLD" : "NEW";
                });
    }

    @Test
    void upgradeSwapsAtomicallyAndDropsOldContent(Vertx vertx, VertxTestContext ctx) {
        var ig = core.services().igPackageService();
        ig.include(SupportedFhirVersion.R4, "example.swap", "1.0.0")
                .compose(r -> classify(P1))
                .compose(before -> {
                    ctx.verify(() -> assertEquals("OLD", before, "v1 profile must apply"));
                    List<Future<String>> during = new ArrayList<>();
                    for (int i = 0; i < 200; i++) {
                        during.add(classify(P1));
                    }
                    List<Future<?>> all = new ArrayList<>(during);
                    all.add(ig.include(SupportedFhirVersion.R4, "example.swap", "2.0.0"));
                    return Future.all(all).map(done -> during);
                })
                .compose(during -> {
                    ctx.verify(() -> during.forEach(f -> assertTrue(f.result().equals("OLD") || f.result().equals("NEW"),
                            "every validation runs entirely on one revision: " + f.result())));
                    return classify(P1);
                })
                .onComplete(ctx.succeeding(after -> ctx.verify(() -> {
                    assertEquals("NEW", after, "content only in v1 must be gone after the upgrade");
                    assertEquals(List.of("example.swap#2.0.0"),
                            core.services().validatorRegistry().current(SupportedFhirVersion.R4).resolvedPackages());
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
