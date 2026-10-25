package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.CustomNpmPackageValidationSupport;
import nzi.fhir.validator.core.service.FhirContextLoader;
import nzi.fhir.validator.testsupport.TestPackages;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HL7 core's snapshot generator modifies the base definition it works against, and HAPI shares the parsed core
 * definitions JVM-wide. When HAPI generated a missing snapshot lazily during validation, that write raced with every
 * concurrent validation reading the same base: unrelated validations failed with {@code NoSuchElementException} or
 * "Multiple matching extensions found for extension ...structuredefinition-fhir-type" (seen in ConfigPropagationIT on
 * CI). Snapshots are now generated up front against private copies ({@link SnapshotPregenerator}).
 * <p>
 * The two race tests reproduced the failure on HAPI 8.12.1 before the fix (most runs failed); like any race test they
 * detect a regression with high probability rather than certainty. The other tests check the mechanism directly.
 */
class SnapshotRaceTest {
    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";
    private static final int ROUNDS = 20;
    private static final int THREADS = 4;
    private static final FhirContext R4 = FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4);

    private static JsonObject differentialProfile(String url) {
        return TestPackages.requiredPatientElements(url, "birthDate");
    }

    private static boolean enforcesBirthDate(ValidatorSnapshot snapshot, String profileUrl) {
        ValidatorSnapshot.Outcome outcome = snapshot.validate(PATIENT, SupportedContentType.JSON, List.of(profileUrl));
        return TestPackages.hasError(outcome, "birthDate");
    }

    @Test
    void storedProfileHasASnapshotBeforeValidationCanSeeIt() {
        String url = "http://example.org/fhir/StructureDefinition/race-stored";
        ProfileStore store = new ProfileStore(SupportedFhirVersion.R4, R4);
        store.put(url, R4.newJsonParser().parseResource(differentialProfile(url).encode()));

        assertFalse(SnapshotPregenerator.needsSnapshot(R4, store.get(url)), "the stored profile has a snapshot");
        assertTrue(enforcesBirthDate(SnapshotBuilder.assemble(SupportedFhirVersion.R4, 1, List.of(), List.of(), store), url),
                "the generated snapshot carries the profile's constraint");
    }

    @Test
    void packageProfileHasASnapshotOnceThePackageIsLoaded() {
        String url = "http://example.org/fhir/StructureDefinition/race-package";
        CustomNpmPackageValidationSupport support = new CustomNpmPackageValidationSupport(R4,
                List.of(npmPackage("example.race", "1.0.0", differentialProfile(url).put("id", "racepkg"))));

        assertFalse(SnapshotPregenerator.needsSnapshot(R4, support.fetchStructureDefinition(url)),
                "the package profile has a snapshot");
    }

    @Test
    void firstUseOfNewProfilesDoesNotBreakConcurrentValidations() throws Exception {
        int failures = 0;
        for (int round = 0; round < ROUNDS; round++) {
            int r = round;
            AtomicInteger thread = new AtomicInteger();
            failures += concurrently(() -> {
                String url = "http://example.org/fhir/StructureDefinition/race-" + r + "-" + thread.incrementAndGet();
                ProfileStore store = new ProfileStore(SupportedFhirVersion.R4, R4);
                store.put(url, R4.newJsonParser().parseResource(differentialProfile(url).encode()));
                ValidatorSnapshot snapshot = SnapshotBuilder.assemble(SupportedFhirVersion.R4, r, List.of(), List.of(), store);
                snapshot.validate(PATIENT, SupportedContentType.JSON, List.of());
                assertTrue(enforcesBirthDate(snapshot, url));
                return null;
            });
        }
        assertEquals(0, failures, "validations failed while new profiles were first used");
    }

    @Test
    void packageProfilesBuiltConcurrentlyDoNotBreakValidations() throws Exception {
        int failures = 0;
        for (int round = 0; round < ROUNDS; round++) {
            int r = round;
            AtomicInteger thread = new AtomicInteger();
            failures += concurrently(() -> {
                int t = thread.incrementAndGet();
                String url = "http://example.org/fhir/StructureDefinition/race-pkg-" + r + "-" + t;
                NpmPackage pkg = npmPackage("example.race" + t, r + ".0.0", differentialProfile(url).put("id", "racepkg" + t));
                ProfileStore store = new ProfileStore(SupportedFhirVersion.R4, R4);
                ValidatorSnapshot snapshot = SnapshotBuilder.assemble(SupportedFhirVersion.R4, r,
                        List.of(pkg.name() + "#" + pkg.version()), List.of(pkg), store);
                snapshot.validate(PATIENT, SupportedContentType.JSON, List.of());
                assertTrue(enforcesBirthDate(snapshot, url));
                return null;
            });
        }
        assertEquals(0, failures, "validations failed while IG package profiles were built concurrently");
    }

    private static NpmPackage npmPackage(String name, String version, JsonObject profile) {
        try {
            return NpmPackage.fromPackage(new ByteArrayInputStream(TestPackages.npmPackage(name, version, Map.of(), List.of(profile))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs {@code work} on {@value #THREADS} threads released at the same moment; returns how many failed. */
    private static int concurrently(Callable<Void> work) throws Exception {
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return work.call();
                }));
            }
            go.countDown();
            int failures = 0;
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    failures++;
                    System.out.println("Concurrent validation failed: " + e.getCause());
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }
}
