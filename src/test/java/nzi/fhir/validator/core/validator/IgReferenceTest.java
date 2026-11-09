package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsing and resolving the {@code ig} parameter (spec 004 FR-001 to FR-004, data-model §2.2).
 */
class IgReferenceTest {

    @Test
    void acceptsNameVersionLatestAndBareName() {
        assertEquals(new IgReference("hl7.fhir.us.core", "6.1.0"), IgReference.parse("hl7.fhir.us.core#6.1.0"));
        assertEquals(new IgReference("hl7.fhir.us.core", null), IgReference.parse("hl7.fhir.us.core#latest"));
        assertEquals(new IgReference("hl7.fhir.us.core", null), IgReference.parse("hl7.fhir.us.core#LATEST"));
        assertEquals(new IgReference("hl7.fhir.us.core", null), IgReference.parse("hl7.fhir.us.core"));
        assertEquals(new IgReference("a_b-c.d", "2.0.0-ballot+1"), IgReference.parse("a_b-c.d#2.0.0-ballot+1"));
        assertNull(IgReference.parseOptional(null));
        assertEquals("a#1", IgReference.parse("a#1").label());
        assertEquals("a", IgReference.parse("a#latest").label());
    }

    @Test
    void rejectsMalformedValuesBeforeAnyLookup() {
        String ok = "a".repeat(IgReference.MAX_LENGTH);
        assertEquals(ok, IgReference.parse(ok).name());
        for (String bad : List.of("", "#1.0.0", "a#", "a##b", "a#b#c", "a,b", "a#1,b#2", " a", "a b", "a/b", "../x",
                ".hidden", "a#-1", "a".repeat(IgReference.MAX_LENGTH + 1), "a#%23")) {
            ProblemException e = assertThrows(ProblemException.class, () -> IgReference.parse(bad), bad);
            assertEquals(400, e.status());
            assertEquals("invalid-request", e.type());
            assertTrue(e.detail().contains("%23"), "the error hints at encoding '#': " + e.detail());
        }
    }

    /** Spec 006 FR-018: the ig parameter needs an exact version; a version series is a client error. */
    @Test
    void rejectsAVersionSeries() {
        for (String series : List.of("p#1.2.x", "p#1.x", "p#1.X.x", "p#1.x.3")) {
            ProblemException e = assertThrows(ProblemException.class, () -> IgReference.parse(series), series);
            assertEquals(400, e.status());
            assertEquals("invalid-request", e.type());
            assertEquals("The ig parameter needs an exact version (for example the version reported by include-ig), "
                    + "not a version series", e.detail());
        }
        assertEquals(new IgReference("p", "1.2.3"), IgReference.parse("p#1.2.3"));
        assertEquals(new IgReference("p", null), IgReference.parse("p#latest"));
        assertEquals(new IgReference("p", null), IgReference.parse("p"));
    }

    private static PackageCatalogView view(Map<String, String[]> packages) {
        Map<String, PackageCatalogView.Entry> entries = new java.util.HashMap<>();
        packages.forEach((k, fhir) -> entries.put(k, new PackageCatalogView.Entry(PackageCatalogView.parseDeclaredFhirVersions(
                new JsonObject().put("fhirVersions", new JsonArray(List.of((Object[]) fhir)))), List.of(), new byte[]{1}, 1)));
        return new PackageCatalogView(3, entries);
    }

    private static PackageCatalog catalogOf(AtomicReference<PackageCatalogView> source, AtomicInteger loads) {
        PackageCatalog catalog = new PackageCatalog(() -> {
            loads.incrementAndGet();
            return Future.succeededFuture(source.get());
        }, System::currentTimeMillis);
        catalog.refresh();
        loads.set(0);
        return catalog;
    }

    @Test
    void resolvesExactAndVersionlessToTheHighestStoredVersion() {
        AtomicReference<PackageCatalogView> db = new AtomicReference<>(view(Map.of(
                "p#1.0.0", new String[]{"4.0.1"}, "p#2.0.0", new String[]{"4.0.1"}, "p#3.0.0-ballot", new String[]{"4.0.1"})));
        AtomicInteger loads = new AtomicInteger();
        PackageCatalog catalog = catalogOf(db, loads);
        assertEquals(SnapshotKey.of(SupportedFhirVersion.R4, "p#1.0.0"),
                IgReference.parse("p#1.0.0").resolve(SupportedFhirVersion.R4, catalog, nzi.fhir.validator.core.security.Caller.system("system"), ALL).result());
        assertEquals(SnapshotKey.of(SupportedFhirVersion.R4, "p#2.0.0"),
                IgReference.parse("p").resolve(SupportedFhirVersion.R4, catalog, nzi.fhir.validator.core.security.Caller.system("system"), ALL).result(), "releases outrank pre-releases");
        assertEquals(SnapshotKey.of(SupportedFhirVersion.R4, "p#2.0.0"),
                IgReference.parse("p#latest").resolve(SupportedFhirVersion.R4, catalog, nzi.fhir.validator.core.security.Caller.system("system"), ALL).result());
        assertEquals(0, loads.get(), "known packages resolve from memory");
    }

    @Test
    void unknownPackageRefreshesOnceThenIs404() {
        AtomicReference<PackageCatalogView> db = new AtomicReference<>(view(Map.of("p#1.0.0", new String[]{"4.0.1"})));
        AtomicInteger loads = new AtomicInteger();
        PackageCatalog catalog = new PackageCatalog(() -> {
            loads.incrementAndGet();
            return Future.succeededFuture(db.get());
        }, () -> 0L);
        Future<SnapshotKey> missing = IgReference.parse("missing#1.0.0").resolve(SupportedFhirVersion.R4, catalog, nzi.fhir.validator.core.security.Caller.system("system"), ALL);
        ProblemException e = assertInstanceOf(ProblemException.class, missing.cause());
        assertEquals(404, e.status());
        assertEquals("package-not-found", e.type());
        assertTrue(e.detail().contains("missing#1.0.0") && e.detail().contains("Register or upload"), e.detail());
        assertEquals(1, loads.get());

        // Stored elsewhere since: a later miss (after the rate-limit window) finds it.
        db.set(view(Map.of("p#1.0.0", new String[]{"4.0.1"}, "new#1.0.0", new String[]{"4.0.1"})));
        PackageCatalog later = new PackageCatalog(() -> Future.succeededFuture(db.get()), () -> 5_000L);
        assertEquals(SnapshotKey.of(SupportedFhirVersion.R4, "new#1.0.0"),
                IgReference.parse("new#1.0.0").resolve(SupportedFhirVersion.R4, later, nzi.fhir.validator.core.security.Caller.system("system"), ALL).result());
    }

    @Test
    void packageForAnotherFhirVersionIs400NamingTheResolvedVersion() {
        AtomicReference<PackageCatalogView> db = new AtomicReference<>(view(Map.of(
                "r5only#1.0.0", new String[]{"5.0.0"}, "any#1.0.0", new String[]{})));
        PackageCatalog catalog = catalogOf(db, new AtomicInteger());
        ProblemException e = assertInstanceOf(ProblemException.class,
                IgReference.parse("r5only").resolve(SupportedFhirVersion.R4, catalog, nzi.fhir.validator.core.security.Caller.system("system"), ALL).cause());
        assertEquals(400, e.status());
        assertEquals("invalid-request", e.type());
        assertTrue(e.detail().contains("r5only#1.0.0") && e.detail().contains("R5"), e.detail());
        assertTrue(IgReference.parse("any#1.0.0").resolve(SupportedFhirVersion.STU3, catalog, nzi.fhir.validator.core.security.Caller.system("system"), ALL).succeeded(),
                "a package declaring no FHIR version is accepted for every version");
    }

    // --- spec 005: resolution per caller (research R5) ----------------------------------------------------------

    private static final java.util.UUID ORG_A = java.util.UUID.randomUUID();
    private static final nzi.fhir.validator.core.security.Caller A = nzi.fhir.validator.core.security.Caller.of(ORG_A, "a",
            nzi.fhir.validator.core.security.ApiKeyRepository.Role.VALIDATE);
    private static final nzi.fhir.validator.core.security.Caller B = nzi.fhir.validator.core.security.Caller.of(
            java.util.UUID.randomUUID(), "b", nzi.fhir.validator.core.security.ApiKeyRepository.Role.VALIDATE);

    /** {@code p#1.0.0} shareable, {@code p#2.0.0} private to A, {@code r5#1.0.0} an R5 package private to A. */
    private static PackageCatalogView ownedView() {
        nzi.fhir.validator.core.security.Access privateA = new nzi.fhir.validator.core.security.Access(ORG_A, "a", false);
        Map<String, PackageCatalogView.Entry> entries = new java.util.HashMap<>();
        entries.put("p#1.0.0", new PackageCatalogView.Entry(java.util.Set.of(SupportedFhirVersion.R4), List.of(), null, 1));
        entries.put("p#2.0.0", new PackageCatalogView.Entry(java.util.Set.of(SupportedFhirVersion.R4), List.of(), null, 1, privateA));
        entries.put("r5#1.0.0", new PackageCatalogView.Entry(java.util.Set.of(SupportedFhirVersion.R5), List.of(), null, 1, privateA));
        return new PackageCatalogView(1, entries);
    }

    @Test
    void versionlessResolvesToTheHighestVersionTheCallerMayUse() {
        PackageCatalogView view = ownedView();
        assertEquals("p#2.0.0", IgReference.parse("p").resolveIn(SupportedFhirVersion.R4, view, A, ALL).orElseThrow().igPackage());
        assertEquals("p#1.0.0", IgReference.parse("p").resolveIn(SupportedFhirVersion.R4, view, B, ALL).orElseThrow().igPackage());
        assertEquals("p#1.0.0", IgReference.parse("p#latest").resolveIn(SupportedFhirVersion.R4, view,
                nzi.fhir.validator.core.security.Caller.ANONYMOUS, ALL).orElseThrow().igPackage());
    }

    @Test
    void anInvisibleExactVersionIsAMissNotAnError() {
        PackageCatalogView view = ownedView();
        assertTrue(IgReference.parse("p#2.0.0").resolveIn(SupportedFhirVersion.R4, view, B, ALL).isEmpty());
        assertTrue(IgReference.parse("p#2.0.0").resolveIn(SupportedFhirVersion.R4, view, A, ALL).isPresent());
    }

    @Test
    void theFhirVersionCheckOnlyRunsForVisiblePackages() {
        PackageCatalogView view = ownedView();
        assertTrue(IgReference.parse("r5#1.0.0").resolveIn(SupportedFhirVersion.R4, view, B, ALL).isEmpty(),
                "invisible: a miss (404), never the 400 that would reveal the package");
        ProblemException e = assertThrows(ProblemException.class,
                () -> IgReference.parse("r5#1.0.0").resolveIn(SupportedFhirVersion.R4, view, A, ALL));
        assertEquals(400, e.status());
    }

    @Test
    void anInvisiblePackageRefreshesOnceThenIsNotStored() {
        AtomicReference<PackageCatalogView> db = new AtomicReference<>(ownedView());
        AtomicInteger loads = new AtomicInteger();
        PackageCatalog catalog = catalogOf(db, loads);
        Future<SnapshotKey> refused = IgReference.parse("p#2.0.0").resolve(SupportedFhirVersion.R4, catalog, B, ALL);
        Future<SnapshotKey> missing = IgReference.parse("q#2.0.0").resolve(SupportedFhirVersion.R4, catalog, B, ALL);
        ProblemException a = assertInstanceOf(ProblemException.class, refused.cause());
        ProblemException b = assertInstanceOf(ProblemException.class, missing.cause());
        assertEquals(404, a.status());
        assertEquals(b.detail().replace("q#2.0.0", "p#2.0.0"), a.detail(), "the same answer as not stored");
    }

    // --- spec 007: included contexts only (FR-021 to FR-023) --------------------------------------------------

    /** Behaves as if every stored version were included: resolution as before 4.0.0. */
    private static final java.util.Set<String> ALL = new java.util.AbstractSet<>() {
        @Override
        public boolean contains(Object o) {
            return true;
        }

        @Override
        public java.util.Iterator<String> iterator() {
            return java.util.Collections.emptyIterator();
        }

        @Override
        public int size() {
            return 0;
        }
    };

    @Test
    void versionlessPicksTheHighestIncludedVersionTheCallerMayUse() {
        PackageCatalogView view = ownedView();
        java.util.Set<String> included = java.util.Set.of("p#1.0.0", "p#2.0.0");
        assertEquals("p#2.0.0", IgReference.parse("p").resolveIn(SupportedFhirVersion.R4, view, A, included).orElseThrow().igPackage());
        assertEquals("p#1.0.0", IgReference.parse("p").resolveIn(SupportedFhirVersion.R4, view, B, included).orElseThrow().igPackage(),
                "B can't use 2.0.0");
        assertEquals("p#1.0.0", IgReference.parse("p#latest").resolveIn(SupportedFhirVersion.R4, view, A,
                java.util.Set.of("p#1.0.0")).orElseThrow().igPackage(), "the highest included, not the highest stored");
    }

    @Test
    void versionlessWithNothingIncludedNamesTheHighestStoredVersion() {
        PackageCatalogView view = ownedView();
        ProblemException e = assertThrows(ProblemException.class,
                () -> IgReference.parse("p").resolveIn(SupportedFhirVersion.R4, view, A, java.util.Set.of()));
        assertEquals(400, e.status());
        assertEquals("invalid-request", e.type());
        assertTrue(e.detail().contains("p#2.0.0") && e.detail().contains("not included for R4"), e.detail());
        ProblemException forB = assertThrows(ProblemException.class,
                () -> IgReference.parse("p").resolveIn(SupportedFhirVersion.R4, view, B, java.util.Set.of()));
        assertTrue(forB.detail().contains("p#1.0.0"), "only a version B may use is named: " + forB.detail());
        assertTrue(IgReference.parse("q").resolveIn(SupportedFhirVersion.R4, view, A, java.util.Set.of()).isEmpty(),
                "nothing stored: 404 path");
    }

    @Test
    void anExactVisibleVersionThatIsNotIncludedIs400() {
        PackageCatalogView view = ownedView();
        ProblemException e = assertThrows(ProblemException.class,
                () -> IgReference.parse("p#1.0.0").resolveIn(SupportedFhirVersion.R4, view, B, java.util.Set.of("p#2.0.0")));
        assertEquals(ProblemException.igNotIncluded("p#1.0.0", SupportedFhirVersion.R4).detail(), e.detail());
        assertTrue(IgReference.parse("p#1.0.0").resolveIn(SupportedFhirVersion.R4, view, B, java.util.Set.of("p#1.0.0")).isPresent());
    }

    @Test
    void anInvisibleExactVersionStaysA404EvenWhenNotIncluded() {
        PackageCatalogView view = ownedView();
        assertTrue(IgReference.parse("p#2.0.0").resolveIn(SupportedFhirVersion.R4, view, B, java.util.Set.of()).isEmpty(),
                "invisible: a miss (404), never the not-included 400");
        assertTrue(IgReference.parse("r5#1.0.0").resolveIn(SupportedFhirVersion.R4, view, B, java.util.Set.of()).isEmpty());
    }

    @Test
    void theFhirVersionCheckComesBeforeNotIncluded() {
        PackageCatalogView view = ownedView();
        ProblemException e = assertThrows(ProblemException.class,
                () -> IgReference.parse("r5#1.0.0").resolveIn(SupportedFhirVersion.R4, view, A, java.util.Set.of()));
        assertTrue(e.detail().contains("is for FHIR R5"), e.detail());
    }
}
