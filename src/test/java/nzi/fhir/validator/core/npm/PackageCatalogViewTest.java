package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The in-memory package metadata view (spec 004, research R4): version resolution, existence, declared FHIR versions,
 * dependency catalog and content hashes, plus the rate limit on miss-triggered refreshes.
 */
class PackageCatalogViewTest {

    private static PackageCatalogView.Entry entry(List<String> deps, byte[] sha, String... fhirVersions) {
        JsonObject meta = new JsonObject();
        if (fhirVersions.length > 0) {
            meta.put("fhirVersions", new JsonArray(List.of((Object[]) fhirVersions)));
        }
        return new PackageCatalogView.Entry(PackageCatalogView.parseDeclaredFhirVersions(meta), deps, sha, 100);
    }

    private static PackageCatalogView view() {
        return new PackageCatalogView(7, Map.of(
                "a.ig#1.0.0", entry(List.of("b.ig#1.0.0"), new byte[]{1}, "4.0.1"),
                "a.ig#1.10.0", entry(List.of("b.ig"), new byte[]{2}, "4.0.1"),
                "a.ig#2.0.0-ballot", entry(List.of(), new byte[]{3}, "4.0.1"),
                "b.ig#1.0.0", entry(List.of(), new byte[]{4}),
                "r5.only#1.0.0", entry(List.of(), new byte[]{5}, "5.0.0")));
    }

    @Test
    void highestUsesPackageVersionOrdering() {
        PackageCatalogView v = view();
        assertEquals("1.10.0", v.highest("a.ig").orElseThrow(), "releases outrank pre-releases; 1.10 > 1.0");
        assertTrue(v.highest("missing").isEmpty());
        assertEquals(7, v.revision());
    }

    @Test
    void containsAndSha() {
        PackageCatalogView v = view();
        assertTrue(v.contains("a.ig#1.0.0"));
        assertFalse(v.contains("a.ig#9.9.9"));
        assertArrayEquals(new byte[]{4}, v.sha256("b.ig#1.0.0"));
        assertNull(v.sha256("nope#1.0.0"));
    }

    @Test
    void supportsFollowsTheIncludeRule() {
        PackageCatalogView v = view();
        assertTrue(v.supports("a.ig#1.0.0", SupportedFhirVersion.R4));
        assertFalse(v.supports("a.ig#1.0.0", SupportedFhirVersion.R5));
        assertTrue(v.supports("b.ig#1.0.0", SupportedFhirVersion.R5), "nothing declared: accepted for every version");
        assertFalse(v.supports("r5.only#1.0.0", SupportedFhirVersion.R4));
        assertEquals(Set.of(SupportedFhirVersion.R5), v.declaredFhirVersions("r5.only#1.0.0"));
    }

    @Test
    void declaredVersionsFallBackToSingleFhirVersion() {
        assertEquals(Set.of(SupportedFhirVersion.R4),
                PackageCatalogView.parseDeclaredFhirVersions(new JsonObject().put("fhirVersion", "4.0.1")));
        assertEquals(Set.of(), PackageCatalogView.parseDeclaredFhirVersions(null));
        assertEquals(Set.of(), PackageCatalogView.parseDeclaredFhirVersions(new JsonObject().put("fhirVersions", new JsonArray().add("9.9.9"))));
    }

    @Test
    void asCatalogMatchesDependencyResolverCatalog() {
        DependencyResolver.Catalog expected = DependencyResolver.Catalog.of(Map.of(
                "a.ig#1.0.0", List.of("b.ig#1.0.0"), "a.ig#1.10.0", List.of("b.ig"), "a.ig#2.0.0-ballot", List.of(),
                "b.ig#1.0.0", List.of(), "r5.only#1.0.0", List.of()));
        DependencyResolver.Catalog actual = view().asCatalog();
        for (String root : List.of("a.ig#1.0.0", "a.ig#1.10.0", "a.ig", "r5.only#1.0.0")) {
            assertEquals(DependencyResolver.resolve(Set.of(root), expected), DependencyResolver.resolve(Set.of(root), actual), root);
        }
        assertEquals(List.of("a.ig#1.10.0", "b.ig#1.0.0"), DependencyResolver.resolve(Set.of("a.ig#1.10.0"), actual).resolved());
    }

    @Test
    void missRefreshIsRateLimited() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong(10_000);
        PackageCatalog catalog = new PackageCatalog(() -> {
            loads.incrementAndGet();
            return Future.succeededFuture(view());
        }, now::get);
        catalog.refreshOnMiss();
        assertEquals(1, loads.get());
        now.addAndGet(200);
        catalog.refreshOnMiss();
        assertEquals(1, loads.get(), "a second miss within 1 s is answered from the current view");
        now.addAndGet(900);
        catalog.refreshOnMiss();
        assertEquals(2, loads.get(), "1.1 s after the last refresh a miss may refresh again");
        catalog.refresh();
        assertEquals(3, loads.get(), "explicit refreshes are not rate limited");
        assertEquals(7, catalog.current().revision());
    }

    @Test
    void aLocalStoreLiftsTheRateLimitForTheNextMiss() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong(10_000);
        io.vertx.core.Promise<PackageCatalogView> slow = io.vertx.core.Promise.promise();
        PackageCatalog catalog = new PackageCatalog(() -> {
            int n = loads.incrementAndGet();
            return n == 2 ? slow.future() : Future.succeededFuture(view());
        }, now::get);
        catalog.refreshOnMiss();
        assertEquals(1, loads.get());
        catalog.markStale();
        catalog.refreshOnMiss();
        assertEquals(2, loads.get(), "stale: refreshed within the rate-limit window");
        catalog.markStale();
        Future<PackageCatalogView> afterInFlight = catalog.refreshOnMiss();
        assertEquals(2, loads.get(), "waits for the load already running");
        slow.complete(view());
        assertEquals(3, loads.get(), "then loads again, because the store may have committed after it started");
        assertTrue(afterInFlight.succeeded());
    }
}
