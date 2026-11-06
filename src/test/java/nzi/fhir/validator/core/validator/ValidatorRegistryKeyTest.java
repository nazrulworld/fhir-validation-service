package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The keyed registry with a fake builder (spec 004, research R2, data-model §2.5): base, pinned and on-demand slots,
 * single-flight builds, the build wait, admission, LRU and memory eviction, demotion, promotion and invalidation.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
class ValidatorRegistryKeyTest {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    /** Records builds; each completes when the test says so, or at once when {@code auto} is set. */
    static final class FakeBuilder extends SnapshotBuilder {
        final List<SnapshotKey> builds = new ArrayList<>();
        final List<Promise<ValidatorSnapshot>> pending = new ArrayList<>();
        final List<HeapBudget.Evictor> evictors = new ArrayList<>();
        volatile boolean auto = true;
        volatile RuntimeException failWith;

        FakeBuilder() {
            super(null, null, new nzi.fhir.validator.core.npm.PackageCache(1), 8, null);
        }

        @Override
        public synchronized Future<ValidatorSnapshot> build(SnapshotKey key, long revision, ResolvedContext resolved,
                                                            HeapBudget.Evictor evictor) {
            builds.add(key);
            evictors.add(evictor);
            if (failWith != null) {
                return Future.failedFuture(failWith);
            }
            ValidatorSnapshot snapshot = new ValidatorSnapshot(key, revision, resolved.packages(), resolved.hashes(), null, null, null);
            if (auto) {
                return Future.succeededFuture(snapshot);
            }
            Promise<ValidatorSnapshot> p = Promise.promise();
            pending.add(p);
            return p.future().map(v -> snapshot);
        }

        synchronized long count(SnapshotKey key) {
            return builds.stream().filter(key::equals).count();
        }

        synchronized void completeAll() {
            List<Promise<ValidatorSnapshot>> copy = new ArrayList<>(pending);
            pending.clear();
            copy.forEach(p -> p.complete(null));
        }
    }

    private FakeBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new FakeBuilder();
    }

    private static ResolvedContext ctx(String root, byte hash) {
        return new ResolvedContext(List.of(root), Map.of(root, new byte[]{hash}));
    }

    private static SnapshotKey key(String ig) {
        return SnapshotKey.of(R4, ig);
    }

    private ValidatorRegistry registry(Vertx vertx, int maxOnDemand, long waitMs, AdmissionController admission) {
        ValidatorRegistry registry = new ValidatorRegistry(vertx, builder, maxOnDemand, waitMs, admission);
        registry.setCatalog(() -> catalogView);
        return registry;
    }

    private PackageCatalogView catalogView = view(Map.of("a#1", (byte) 1, "b#1", (byte) 2, "c#1", (byte) 3, "d#1", (byte) 4));

    private static PackageCatalogView view(Map<String, Byte> hashes) {
        Map<String, PackageCatalogView.Entry> entries = new java.util.HashMap<>();
        hashes.forEach((k, h) -> entries.put(k, new PackageCatalogView.Entry(java.util.Set.of(), List.of(), new byte[]{h}, 1)));
        return new PackageCatalogView(1, entries);
    }

    @Test
    void basePinnedAndUnchangedKeysAreNotRebuilt(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        assertEquals(1, registry.notReadyReasons().stream().filter(r -> r.startsWith("R4 ")).count(), "base not built yet");
        assertTrue(registry.applyPinned(R4, 1, Map.of()).succeeded());
        assertEquals(List.of(), registry.current(R4).resolvedPackages(), "the base context holds no package");

        assertTrue(registry.applyPinned(R4, 2, Map.of("a#1", ctx("a#1", (byte) 1), "b#1", ctx("b#1", (byte) 2))).succeeded());
        ValidatorSnapshot a = registry.current(key("a#1"));
        assertEquals(ValidatorRegistry.Kind.PINNED, registry.kind(key("a#1")));
        assertEquals(2, registry.appliedRevision(R4));

        assertTrue(registry.applyPinned(R4, 3, Map.of("a#1", ctx("a#1", (byte) 1), "b#1", ctx("b#1", (byte) 2))).succeeded());
        assertEquals(1, builder.count(key("a#1")), "unchanged packages and content: no rebuild");
        assertSame(a, registry.current(key("a#1")));
        assertEquals(1, builder.count(SnapshotKey.base(R4)));

        assertTrue(registry.applyPinned(R4, 4, Map.of("a#1", ctx("a#1", (byte) 9), "b#1", ctx("b#1", (byte) 2))).succeeded());
        assertEquals(2, builder.count(key("a#1")), "replaced content: rebuilt");
        assertEquals(1, builder.count(key("b#1")));
    }

    @Test
    void excludedKeyIsDemotedAndIncludedOnDemandKeyIsPromotedWithoutRebuild(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        registry.applyPinned(R4, 1, Map.of("a#1", ctx("a#1", (byte) 1), "b#1", ctx("b#1", (byte) 2)));
        ValidatorSnapshot b = registry.current(key("b#1"));
        registry.applyPinned(R4, 2, Map.of("a#1", ctx("a#1", (byte) 1)));
        assertEquals(ValidatorRegistry.Kind.ON_DEMAND, registry.kind(key("b#1")), "demoted, not dropped");
        assertSame(b, registry.current(key("b#1")));
        assertEquals(1, registry.contextCount(R4, ValidatorRegistry.Kind.ON_DEMAND));
        assertEquals(1, registry.contextCount(R4, ValidatorRegistry.Kind.PINNED));

        assertTrue(registry.snapshot(key("c#1")).succeeded());
        registry.applyPinned(R4, 3, Map.of("a#1", ctx("a#1", (byte) 1), "c#1", ResolvedContext.of("c#1", catalogView)));
        assertEquals(ValidatorRegistry.Kind.PINNED, registry.kind(key("c#1")));
        assertEquals(1, builder.count(key("c#1")), "promotion keeps the on-demand snapshot");
    }

    @Test
    void oneKeysFailureLeavesOthersServingAndOnlyBasePinnedCountForReadiness(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            registry.applyPinned(v, 1, Map.of());
        }
        registry.applyPinned(R4, 2, Map.of("a#1", ctx("a#1", (byte) 1)));
        builder.failWith = new SnapshotBuilder.MissingPackageException("b#1");
        Future<Void> failed = registry.applyPinned(R4, 3, Map.of("a#1", ctx("a#1", (byte) 1), "b#1", ctx("b#1", (byte) 2)));
        assertTrue(failed.failed());
        assertEquals(ValidatorRegistry.Status.STALE, registry.status(key("b#1")));
        assertEquals(ValidatorRegistry.Status.ACTIVE, registry.status(key("a#1")));
        assertNotNull(registry.current(key("a#1")));
        assertEquals(List.of("R4:b#1 validator is out of date: Package b#1 is not stored"), registry.notReadyReasons());
        registry.close();

        builder.failWith = null;
        ValidatorRegistry fresh = registry(vertx, 4, 10_000, null);
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            fresh.applyPinned(v, 1, Map.of());
        }
        builder.auto = false;
        fresh.snapshot(key("c#1"));
        assertTrue(fresh.isReady(), "an on-demand context being built never makes the instance unready");
    }

    @Test
    void concurrentRequestsShareOneOnDemandBuild(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        builder.auto = false;
        List<Future<ValidatorSnapshot>> all = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            all.add(registry.snapshot(key("a#1")));
        }
        assertEquals(1, builder.count(key("a#1")));
        builder.completeAll();
        assertTrue(all.stream().allMatch(Future::succeeded));
        assertEquals(1, all.stream().map(Future::result).distinct().count(), "one snapshot for all");
        assertSame(all.get(0).result(), registry.snapshot(key("a#1")).result(), "later requests reuse it");
        assertEquals(1, builder.count(key("a#1")));
    }

    @Test
    void buildWaitTimesOutWith503WhileTheBuildContinues(Vertx vertx, VertxTestContext test) {
        ValidatorRegistry registry = registry(vertx, 4, 200, null);
        builder.auto = false;
        registry.snapshot(key("a#1")).onComplete(test.failing(err -> test.verify(() -> {
            ProblemException p = assertInstanceOf(ProblemException.class, err);
            assertEquals(503, p.status());
            assertEquals("not-ready", p.type());
            assertEquals("5", p.headers().get("Retry-After"));
            builder.completeAll();
            assertTrue(registry.snapshot(key("a#1")).succeeded(), "the build finished; a retry finds it");
            assertEquals(1, builder.count(key("a#1")));
            test.completeNow();
        })));
    }

    @Test
    void startingABuildTakesAnAdmissionSlotAndJoiningDoesNot(Vertx vertx) {
        AdmissionController admission = new AdmissionController(1);
        ValidatorRegistry registry = registry(vertx, 4, 10_000, admission);
        builder.auto = false;
        Future<ValidatorSnapshot> first = registry.snapshot(key("a#1"));
        Future<ValidatorSnapshot> joined = registry.snapshot(key("a#1"));
        assertEquals(1, admission.pending());
        ProblemException refused = assertInstanceOf(ProblemException.class, registry.snapshot(key("b#1")).cause());
        assertEquals(503, refused.status());
        assertEquals("overloaded", refused.type());
        builder.completeAll();
        assertTrue(first.succeeded() && joined.succeeded());
        assertEquals(0, admission.pending(), "released when the build ends");
    }

    @Test
    void onDemandContextsAreBoundedLeastRecentlyUsedFirst(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 2, 10_000, null);
        registry.snapshot(key("a#1"));
        registry.snapshot(key("b#1"));
        registry.snapshot(key("a#1"));
        registry.snapshot(key("c#1"));
        assertNull(registry.kind(key("b#1")), "b was least recently used");
        assertEquals(ValidatorRegistry.Kind.ON_DEMAND, registry.kind(key("a#1")));
        assertEquals(2, registry.contextCount(R4, ValidatorRegistry.Kind.ON_DEMAND));
    }

    @Test
    void evictorSkipsBuildingAndPinnedContexts(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        registry.applyPinned(R4, 1, Map.of("d#1", ctx("d#1", (byte) 4)));
        registry.snapshot(key("a#1"));
        builder.auto = false;
        registry.snapshot(key("b#1"));
        assertFalse(registry.evictOne(key("a#1")), "a is excepted, b is building, d is pinned");
        assertTrue(registry.evictOne(key("b#1")));
        assertNull(registry.kind(key("a#1")));
        assertEquals(ValidatorRegistry.Kind.PINNED, registry.kind(key("d#1")));
    }

    @Test
    void failedOnDemandBuildsMapToCuratedProblemsAndAreNotCached(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        builder.failWith = new SnapshotBuilder.MissingPackageException("dep#2");
        ProblemException missing = assertInstanceOf(ProblemException.class, registry.snapshot(key("a#1")).cause());
        assertEquals(424, missing.status());
        assertEquals("dependency-not-found", missing.type());
        assertTrue(missing.detail().contains("dep#2"));
        assertNull(registry.kind(key("a#1")), "a failed slot is removed");

        builder.failWith = new HeapBudget.InsufficientCapacityException("x");
        ProblemException capacity = assertInstanceOf(ProblemException.class, registry.snapshot(key("a#1")).cause());
        assertEquals(503, capacity.status());
        assertEquals("insufficient-capacity", capacity.type());
        assertEquals("30", capacity.headers().get("Retry-After"));

        builder.failWith = new HeapBudget.NeverFitsException("needs about 9 MB");
        ProblemException never = assertInstanceOf(ProblemException.class, registry.snapshot(key("a#1")).cause());
        assertEquals(413, never.status());
        assertEquals("package-too-large", never.type());

        builder.failWith = new IllegalStateException("internal detail");
        ProblemException other = assertInstanceOf(ProblemException.class, registry.snapshot(key("a#1")).cause());
        assertEquals(503, other.status());
        assertFalse(other.detail().contains("internal detail"), "causes stay in the log");

        builder.failWith = null;
        assertTrue(registry.snapshot(key("a#1")).succeeded(), "the next request tries again");
        assertEquals(5, builder.count(key("a#1")));
    }

    @Test
    void catalogChangeMarksPinnedStaleAndDropsChangedOnDemandContexts(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        registry.applyPinned(R4, 1, Map.of("a#1", ResolvedContext.of("a#1", catalogView)));
        registry.snapshot(key("b#1"));
        registry.snapshot(key("c#1"));
        registry.onCatalogChanged(view(Map.of("a#1", (byte) 7, "b#1", (byte) 8, "c#1", (byte) 3, "d#1", (byte) 4)));
        assertEquals(ValidatorRegistry.Status.STALE, registry.status(key("a#1")));
        ProblemException stale = assertThrows(ProblemException.class, () -> registry.current(key("a#1")));
        assertEquals(503, stale.status());
        assertTrue(registry.notReadyReasons().get(0).contains("package a#1 was replaced"), registry.notReadyReasons().toString());
        assertNull(registry.kind(key("b#1")), "on-demand context with replaced content is dropped");
        assertEquals(ValidatorRegistry.Kind.ON_DEMAND, registry.kind(key("c#1")), "unchanged one stays");
    }

    /** Spec 004 FR-032, contracts §5: health lists every context with its kind; readiness counts base and pinned only. */
    @Test
    void healthDetailsListEveryContextAndReadinessIgnoresOnDemand(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx, 4, 10_000, null);
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            registry.applyPinned(v, 1, Map.of());
        }
        registry.applyPinned(R4, 2, Map.of("a#1", ctx("a#1", (byte) 1)));
        registry.snapshot(key("b#1"));
        builder.auto = false;
        registry.snapshot(key("c#1"));

        nzi.fhir.validator.core.service.HealthService health = new nzi.fhir.validator.core.service.HealthService(vertx, null);
        health.setValidatorReadiness(registry::notReadyReasons, registry::details);
        health.setCatalogRevision(() -> 42L);
        io.vertx.core.json.JsonObject readiness = health.readiness();
        assertEquals(42L, readiness.getLong("catalogRevision"));
        io.vertx.core.json.JsonObject r4 = readiness.getJsonObject("versions").getJsonObject("R4");
        assertEquals("ACTIVE", r4.getJsonObject("base").getString("state"));
        assertEquals(List.of(), r4.getJsonObject("base").getJsonArray("packages").getList());
        assertEquals(2, r4.getLong("appliedRevision"));
        io.vertx.core.json.JsonArray contexts = r4.getJsonArray("contexts");
        assertEquals(3, contexts.size(), contexts.encode());
        assertEquals(List.of("a#1", "b#1", "c#1"), contexts.stream().map(c -> ((io.vertx.core.json.JsonObject) c).getString("key")).toList());
        assertEquals(List.of("pinned", "on-demand", "on-demand"),
                contexts.stream().map(c -> ((io.vertx.core.json.JsonObject) c).getString("kind")).toList());
        assertEquals(List.of("ACTIVE", "ACTIVE", "BUILDING"),
                contexts.stream().map(c -> ((io.vertx.core.json.JsonObject) c).getString("state")).toList());
        assertEquals(List.of("a#1"), contexts.getJsonObject(0).getJsonArray("packages").getList());
        assertNotNull(contexts.getJsonObject(1).getString("lastUsed"));
        assertTrue(registry.notReadyReasons().isEmpty(), "a building on-demand context does not make the instance unready");

        builder.auto = true;
        builder.failWith = new SnapshotBuilder.MissingPackageException("a#1");
        registry.applyPinned(R4, 3, Map.of("a#1", ctx("a#1", (byte) 5)));
        assertEquals(List.of("R4:a#1 validator is out of date: Package a#1 is not stored"), registry.notReadyReasons(),
                "a stale pinned context makes the instance unready");
        registry.close();
    }
}
