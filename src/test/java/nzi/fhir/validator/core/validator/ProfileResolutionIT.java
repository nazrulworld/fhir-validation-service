package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.testsupport.CoreHarness;
import nzi.fhir.validator.testsupport.TestPackages;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Profiles are resolved only from the chosen validation context, matched by exact canonical, never fetched; an
 * unresolved requested profile rejects the request (spec 003: US1, US2, FR-001 to FR-008, SC-001 to SC-004). Since
 * spec 004 (US1-8, US1-9, FR-007) the context is the request's {@code ig} package, its dependencies and the core
 * specification, or the core specification alone without {@code ig}.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 240, timeUnit = TimeUnit.SECONDS)
class ProfileResolutionIT extends BaseTestContainer {
    private static final String BASE = "http://example.org/fhir/StructureDefinition/";
    /** Requires birthDate. */
    private static final String P = BASE + "res-p";
    /** Requires gender. */
    private static final String Q = BASE + "res-q";
    private static final String OBS = BASE + "res-obs";
    private static final String EXT = BASE + "res-ext";
    private static final String UNKNOWN = BASE + "does-not-exist";
    private static final String MAIN = "res.main#1.0.0";

    private CoreHarness core;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext ctx) {
        startContainers();
        resetSchema();
        CoreHarness.start(vertx, connectOptions())
                .compose(h -> {
                    core = h;
                    return TestPackages.includePackage(h.services(), TestPackages.profilePackage("res.main", "1.0.0",
                            TestPackages.requiredPatientElements(P, "birthDate").put("version", "1.0.0"),
                            TestPackages.requiredPatientElements(Q, "gender"),
                            observationProfile(OBS),
                            extensionDefinition(EXT)), "res.main", "1.0.0");
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        (core == null ? Future.<Void>succeededFuture() : core.stop()).onComplete(ar -> ctx.completeNow());
    }

    /** Validates against the main test IG ({@value #MAIN}). */
    private Future<ValidatorSnapshot.Outcome> validate(String body, String... profiles) {
        return validateIn(MAIN, body, profiles);
    }

    /** @param ig the request's ig value, or {@code null} for the core-only base context */
    private Future<ValidatorSnapshot.Outcome> validateIn(String ig, String body, String... profiles) {
        return validateOn(core, ig, body, profiles);
    }

    private static Future<ValidatorSnapshot.Outcome> validateOn(CoreHarness on, String ig, String body, String... profiles) {
        return on.services().validationService()
                .validate(nzi.fhir.validator.core.security.Caller.system("system"), SupportedFhirVersion.R4, ig == null ? null : IgReference.parse(ig), body, SupportedContentType.JSON,
                        List.of(profiles), java.util.function.Function.identity());
    }

    private static void assertUnknown(Throwable err, String... unresolved) {
        ProblemException p = assertInstanceOf(ProblemException.class, err);
        assertEquals(400, p.status());
        assertEquals(ProblemException.TYPE_UNKNOWN_PROFILE, p.type());
        assertEquals(new JsonArray(List.of(unresolved)), p.extensions().getJsonArray("unresolvedProfiles"));
    }

    private static JsonObject observationProfile(String url) {
        return new JsonObject()
                .put("resourceType", "StructureDefinition").put("id", "res-obs").put("url", url).put("name", "ResObs")
                .put("status", "active").put("fhirVersion", "4.0.1").put("kind", "resource").put("abstract", false)
                .put("type", "Observation").put("baseDefinition", "http://hl7.org/fhir/StructureDefinition/Observation")
                .put("derivation", "constraint")
                .put("differential", new JsonObject().put("element", new JsonArray()
                        .add(new JsonObject().put("id", "Observation").put("path", "Observation"))));
    }

    private static JsonObject extensionDefinition(String url) {
        return new JsonObject()
                .put("resourceType", "StructureDefinition").put("id", "res-ext").put("url", url).put("name", "ResExt")
                .put("status", "active").put("fhirVersion", "4.0.1").put("kind", "complex-type").put("abstract", false)
                .put("context", new JsonArray().add(new JsonObject().put("type", "element").put("expression", "Patient")))
                .put("type", "Extension").put("baseDefinition", "http://hl7.org/fhir/StructureDefinition/Extension")
                .put("derivation", "constraint")
                .put("differential", new JsonObject().put("element", new JsonArray()
                        .add(new JsonObject().put("id", "Extension").put("path", "Extension"))
                        .add(new JsonObject().put("id", "Extension.url").put("path", "Extension.url")
                                .put("fixedUri", url))));
    }

    private static String patientWithMetaProfile(String... profiles) {
        return new JsonObject().put("resourceType", "Patient").put("id", "p1").put("active", true)
                .put("meta", new JsonObject().put("profile", new JsonArray(List.of(profiles)))).encode();
    }

    // ---- US1 ----

    @Test
    void requestedProfileIsApplied(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), P).onComplete(ctx.succeeding(o -> ctx.verify(() -> {
            assertFalse(o.result().isSuccessful());
            assertTrue(TestPackages.hasError(o, "birthDate"), o.result().getMessages().toString());
            assertEquals(List.of(P), o.appliedProfiles());
            ctx.completeNow();
        })));
    }

    @Test
    void everyRequestedProfileIsApplied(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), P, Q).onComplete(ctx.succeeding(o -> ctx.verify(() -> {
            assertTrue(TestPackages.hasError(o, "birthDate"));
            assertTrue(TestPackages.hasError(o, "gender"));
            assertEquals(List.of(P, Q), o.appliedProfiles());
            ctx.completeNow();
        })));
    }

    @Test
    void unknownProfileAmongKnownOnesIsNeverSilentlyDropped(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), P, UNKNOWN).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertUnknown(err, UNKNOWN);
            ctx.completeNow();
        })));
    }

    @Test
    void singleUnknownProfileIsRejected(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), UNKNOWN).onComplete(ctx.failing(err -> ctx.verify(() -> {
            assertUnknown(err, UNKNOWN);
            ctx.completeNow();
        })));
    }

    @Test
    void versionedCanonicalSelectsOnlyThatVersion(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), P + "|1.0.0")
                .compose(o -> {
                    ctx.verify(() -> assertTrue(TestPackages.hasError(o, "birthDate")));
                    return validate(TestPackages.minimalPatient(), P + "|9.9.9");
                })
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertUnknown(err, P + "|9.9.9");
                    ctx.completeNow();
                })));
    }

    @Test
    void partialUrlsAreNotExactCanonicals(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), "res-p", "StructureDefinition/res-p")
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertUnknown(err, "res-p", "StructureDefinition/res-p");
                    ctx.completeNow();
                })));
    }

    @Test
    void unknownMetaProfileStaysAnErrorAndIsNotListedAsApplied(VertxTestContext ctx) {
        validate(patientWithMetaProfile(UNKNOWN)).onComplete(ctx.succeeding(o -> ctx.verify(() -> {
            assertFalse(o.result().isSuccessful(), "an unknown meta.profile is an ERROR (Q2: A)");
            assertFalse(o.appliedProfiles().contains(UNKNOWN));
            ctx.completeNow();
        })));
    }

    @Test
    void profileBothDeclaredAndRequestedIsListedOnce(VertxTestContext ctx) {
        validate(patientWithMetaProfile(P), P).onComplete(ctx.succeeding(o -> ctx.verify(() -> {
            assertEquals(List.of(P), o.appliedProfiles());
            ctx.completeNow();
        })));
    }

    @Test
    void profileForAnotherResourceTypeResolvesAndReportsTheMismatch(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), OBS).onComplete(ctx.succeeding(o -> ctx.verify(() -> {
            assertFalse(o.result().isSuccessful(), "the type mismatch is a validation issue");
            assertEquals(List.of(OBS), o.appliedProfiles());
            ctx.completeNow();
        })));
    }

    @Test
    void definitionThatIsNotAResourceProfileResolves(VertxTestContext ctx) {
        validate(TestPackages.minimalPatient(), EXT).onComplete(ctx.succeeding(o -> ctx.verify(() -> {
            assertEquals(List.of(EXT), o.appliedProfiles());
            ctx.completeNow();
        })));
    }

    @Test
    void bundleIsValidatedAgainstAProfileOfItsEntryType(VertxTestContext ctx) {
        String bundle = new JsonObject().put("resourceType", "Bundle").put("type", "collection")
                .put("entry", new JsonArray().add(new JsonObject().put("fullUrl", "urn:uuid:6f2e1c0a-0000-4000-8000-000000000001")
                        .put("resource", new JsonObject().put("resourceType", "Patient").put("active", true))))
                .encode();
        validate(bundle, P).onComplete(ctx.succeeding(o -> ctx.verify(() -> {
            assertEquals(List.of(P), o.appliedProfiles());
            ctx.completeNow();
        })));
    }

    // ---- US2 (as scoped by spec 004) ----

    @Test
    void storedPackageContributesProfilesOnlyWhenItIsTheChosenIg(VertxTestContext ctx) {
        String onlyInB = BASE + "res-only-in-b";
        core.services().igPackageService()
                .registerIg(TestPackages.profilePackage("res.notincluded", "1.0.0",
                        TestPackages.requiredPatientElements(onlyInB, "birthDate")), false)
                .compose(pkg -> validate(TestPackages.minimalPatient(), onlyInB))
                .<ValidatorSnapshot.Outcome>transform(ar -> {
                    ctx.verify(() -> {
                        assertTrue(ar.failed(), "another stored package's profile is not visible through " + MAIN);
                        assertUnknown(ar.cause(), onlyInB);
                    });
                    // Named as the ig once it is included (spec 007 FR-021), its own profiles apply.
                    return core.services().igPackageService().include(SupportedFhirVersion.R4, "res.notincluded", "1.0.0")
                            .compose(v -> validateIn("res.notincluded#1.0.0", TestPackages.minimalPatient(), onlyInB));
                })
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(o, "birthDate"));
                    assertEquals(List.of(onlyInB), o.appliedProfiles());
                    assertEquals("res.notincluded#1.0.0", o.igPackage());
                    ctx.completeNow();
                })));
    }

    @Test
    void profileOfAnotherIncludedIgIsUnknown(VertxTestContext ctx) {
        String other = BASE + "res-other-included";
        TestPackages.includePackage(core.services(), TestPackages.profilePackage("res.other", "1.0.0",
                        TestPackages.requiredPatientElements(other, "gender")), "res.other", "1.0.0")
                .compose(v -> validate(TestPackages.minimalPatient(), other))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertUnknown(err, other);
                    ctx.completeNow();
                })));
    }

    @Test
    void withoutIgOnlyTheCoreSpecificationResolves(VertxTestContext ctx) {
        validateIn(null, TestPackages.minimalPatient(), P)
                .<ValidatorSnapshot.Outcome>transform(ar -> {
                    ctx.verify(() -> {
                        assertTrue(ar.failed(), "an included IG's profile is not available in the base context");
                        assertUnknown(ar.cause(), P);
                        ProblemException p = (ProblemException) ar.cause();
                        assertTrue(p.detail().contains("core specification"), p.detail());
                    });
                    return validateIn(null, TestPackages.minimalPatient(), "http://hl7.org/fhir/StructureDefinition/Patient");
                })
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertNull(o.igPackage());
                    assertEquals(List.of("http://hl7.org/fhir/StructureDefinition/Patient"), o.appliedProfiles());
                    ctx.completeNow();
                })));
    }

    @Test
    void profileFromADependencyOfTheChosenIgIsApplied(VertxTestContext ctx) {
        core.services().igPackageService()
                .registerIg(TestPackages.npmPackage("res.dependent", "1.0.0", java.util.Map.of("res.main", "1.0.0"), List.of()), false)
                .compose(pkg -> core.services().igPackageService().include(SupportedFhirVersion.R4, "res.dependent", "1.0.0"))
                .compose(v -> validateIn("res.dependent#1.0.0", TestPackages.minimalPatient(), P))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertTrue(TestPackages.hasError(o, "birthDate"));
                    assertEquals(List.of(P), o.appliedProfiles());
                    ctx.completeNow();
                })));
    }

    @Test
    void profilesAreNeverFetchedFromTheNetwork(Vertx vertx, VertxTestContext ctx) {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = vertx.createHttpServer().requestHandler(req -> {
            hits.incrementAndGet();
            req.response().putHeader("Content-Type", "application/fhir+json")
                    .end(TestPackages.requiredPatientElements(req.absoluteURI(), "birthDate").encode());
        });
        server.listen(0, "127.0.0.1")
                .compose(s -> {
                    String remote = "http://127.0.0.1:" + s.actualPort() + "/StructureDefinition/x";
                    return validate(TestPackages.minimalPatient(), remote)
                            .<Void>transform(ar -> {
                                ctx.verify(() -> {
                                    assertTrue(ar.failed(), "a requested remote profile must not resolve");
                                    assertUnknown(ar.cause(), remote);
                                });
                                return validate(patientWithMetaProfile(remote)).map(o -> {
                                    ctx.verify(() -> assertFalse(o.result().isSuccessful()));
                                    return null;
                                });
                            });
                })
                .eventually(() -> server.close())
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertEquals(0, hits.get(), "validation made outbound requests");
                    ctx.completeNow();
                })));
    }

    /**
     * Two packages of one context define the same canonical: the IG dup.b depends on dup.a, and both define x. The
     * last package in the sorted resolved set wins, identically on every instance (spec 003 FR-003).
     */
    @Test
    void duplicateCanonicalResolvesDeterministicallyOnEveryInstance(Vertx vertx, VertxTestContext ctx) {
        String x = BASE + "res-dup";
        Vertx vertxB = Vertx.vertx();
        var ig = core.services().igPackageService();
        Future<CoreHarness> second = ig.registerIg(TestPackages.profilePackage("dup.a", "1.0.0",
                        TestPackages.requiredPatientElements(x, "birthDate").put("version", "1.0.0")), false)
                .compose(v -> ig.registerIg(TestPackages.npmPackage("dup.b", "1.0.0", java.util.Map.of("dup.a", "1.0.0"),
                        List.of(TestPackages.requiredPatientElements(x, "gender").put("version", "2.0.0"))), false))
                .compose(v -> ig.include(SupportedFhirVersion.R4, "dup.b", "1.0.0"))
                .compose(v -> CoreHarness.start(vertxB, connectOptions()));
        second.<io.vertx.core.CompositeFuture>compose(b -> Future.all(
                                validateIn("dup.b#1.0.0", TestPackages.minimalPatient(), x),
                                validateOn(b, "dup.b#1.0.0", TestPackages.minimalPatient(), x),
                                validateIn("dup.b#1.0.0", TestPackages.minimalPatient(), x + "|1.0.0"),
                                validateIn("dup.b#1.0.0", TestPackages.minimalPatient(), x + "|2.0.0"))
                        .eventually(() -> b.stop()))
                .eventually(() -> vertxB.close())
                .onComplete(ctx.succeeding(all -> ctx.verify(() -> {
                    ValidatorSnapshot.Outcome onA = all.resultAt(0);
                    ValidatorSnapshot.Outcome onB = all.resultAt(1);
                    // The last package in the sorted resolved set wins (dup.b, requires gender).
                    assertTrue(TestPackages.hasError(onA, "gender") && !TestPackages.hasError(onA, "birthDate"));
                    assertTrue(TestPackages.hasError(onB, "gender") && !TestPackages.hasError(onB, "birthDate"));
                    assertTrue(TestPackages.hasError(all.resultAt(2), "birthDate"));
                    assertTrue(TestPackages.hasError(all.resultAt(3), "gender"));
                    ctx.completeNow();
                })));
    }

    /** Spec 004 US2: two versions of one IG are separate contexts, each with only its own profiles. */
    @Test
    void eachIncludedVersionKeepsOnlyItsOwnProfiles(VertxTestContext ctx) {
        String r = BASE + "res-replaced";
        TestPackages.includePackage(core.services(), TestPackages.profilePackage("p.test", "1.0.0",
                        TestPackages.requiredPatientElements(r, "birthDate").put("version", "1.0.0")), "p.test", "1.0.0")
                .compose(v -> TestPackages.includePackage(core.services(), TestPackages.profilePackage("p.test", "2.0.0",
                        TestPackages.requiredPatientElements(r, "gender").put("version", "2.0.0")), "p.test", "2.0.0"))
                .compose(v -> validateIn("p.test#2.0.0", TestPackages.minimalPatient(), r + "|2.0.0"))
                .compose(o -> {
                    ctx.verify(() -> assertTrue(TestPackages.hasError(o, "gender")));
                    return validateIn("p.test#1.0.0", TestPackages.minimalPatient(), r + "|1.0.0");
                })
                .compose(o -> {
                    ctx.verify(() -> assertTrue(TestPackages.hasError(o, "birthDate"), "v1 is still available beside v2"));
                    return validateIn("p.test#2.0.0", TestPackages.minimalPatient(), r + "|1.0.0");
                })
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertUnknown(err, r + "|1.0.0");
                    ctx.completeNow();
                })));
    }
}
