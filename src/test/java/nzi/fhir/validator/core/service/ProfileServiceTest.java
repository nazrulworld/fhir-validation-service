package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class ProfileServiceTest extends BaseTestContainer {

    private CoreHarness core;
    private ProfileService r4;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        CoreHarness.start(vertx, connectOptions()).onComplete(ctx.succeeding(h -> {
            core = h;
            r4 = h.services().profileServices().get(SupportedFhirVersion.R4);
            ctx.completeNow();
        }));
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    private static int status(Throwable err) {
        return ((ProblemException) err).status();
    }

    @Test
    void registeredProfileIsServedAndRevisionIncreases(VertxTestContext ctx) {
        String url = "http://example.org/fhir/StructureDefinition/registered";
        r4.registerProfile(TestPackages.requiredPatientElements(url, "birthDate"))
                .compose(first -> r4.registerProfile(TestPackages.requiredPatientElements(url, "gender").put("name", "Updated"))
                        .map(second -> new long[]{first, second}))
                .onComplete(ctx.succeeding(revs -> ctx.verify(() -> {
                    assertTrue(revs[1] > revs[0]);
                    StructureDefinition sd = (StructureDefinition) r4.getProfile(url);
                    assertNotNull(sd);
                    assertEquals("Updated", sd.getName(), "an update replaces the stored profile");
                    ctx.completeNow();
                })));
    }

    @Test
    void unknownProfileIsNull() {
        assertNull(r4.getProfile("http://example.org/nope"));
    }

    @Test
    void missingUrlIsRejected(VertxTestContext ctx) {
        r4.registerProfile(new JsonObject().put("resourceType", "StructureDefinition"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertEquals(400, status(err));
                    ctx.completeNow();
                })));
    }

    @Test
    void nonStructureDefinitionIsRejected(VertxTestContext ctx) {
        r4.registerProfile(new JsonObject().put("resourceType", "Patient").put("url", "http://example.org/p"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertEquals(400, status(err));
                    ctx.completeNow();
                })));
    }

    @Test
    void unparseableResourceIsRejected(VertxTestContext ctx) {
        r4.registerProfile(new JsonObject().put("resourceType", "NoSuchType").put("url", "http://example.org/p"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertEquals(400, status(err));
                    assertTrue(((ProblemException) err).detail().contains("Failed to parse profile"));
                    ctx.completeNow();
                })));
    }

    @Test
    void emptyBodyIsRejected(VertxTestContext ctx) {
        r4.registerProfile(new JsonObject())
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertEquals(400, status(err));
                    ctx.completeNow();
                })));
    }
}
