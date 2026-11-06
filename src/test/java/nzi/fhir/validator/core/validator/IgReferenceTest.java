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
                IgReference.parse("p#1.0.0").resolve(SupportedFhirVersion.R4, catalog).result());
        assertEquals(SnapshotKey.of(SupportedFhirVersion.R4, "p#2.0.0"),
                IgReference.parse("p").resolve(SupportedFhirVersion.R4, catalog).result(), "releases outrank pre-releases");
        assertEquals(SnapshotKey.of(SupportedFhirVersion.R4, "p#2.0.0"),
                IgReference.parse("p#latest").resolve(SupportedFhirVersion.R4, catalog).result());
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
        Future<SnapshotKey> missing = IgReference.parse("missing#1.0.0").resolve(SupportedFhirVersion.R4, catalog);
        ProblemException e = assertInstanceOf(ProblemException.class, missing.cause());
        assertEquals(404, e.status());
        assertEquals("package-not-found", e.type());
        assertTrue(e.detail().contains("missing#1.0.0") && e.detail().contains("Register or upload"), e.detail());
        assertEquals(1, loads.get());

        // Stored elsewhere since: a later miss (after the rate-limit window) finds it.
        db.set(view(Map.of("p#1.0.0", new String[]{"4.0.1"}, "new#1.0.0", new String[]{"4.0.1"})));
        PackageCatalog later = new PackageCatalog(() -> Future.succeededFuture(db.get()), () -> 5_000L);
        assertEquals(SnapshotKey.of(SupportedFhirVersion.R4, "new#1.0.0"),
                IgReference.parse("new#1.0.0").resolve(SupportedFhirVersion.R4, later).result());
    }

    @Test
    void packageForAnotherFhirVersionIs400NamingTheResolvedVersion() {
        AtomicReference<PackageCatalogView> db = new AtomicReference<>(view(Map.of(
                "r5only#1.0.0", new String[]{"5.0.0"}, "any#1.0.0", new String[]{})));
        PackageCatalog catalog = catalogOf(db, new AtomicInteger());
        ProblemException e = assertInstanceOf(ProblemException.class,
                IgReference.parse("r5only").resolve(SupportedFhirVersion.R4, catalog).cause());
        assertEquals(400, e.status());
        assertEquals("invalid-request", e.type());
        assertTrue(e.detail().contains("r5only#1.0.0") && e.detail().contains("R5"), e.detail());
        assertTrue(IgReference.parse("any#1.0.0").resolve(SupportedFhirVersion.STU3, catalog).succeeded(),
                "a package declaring no FHIR version is accepted for every version");
    }
}
