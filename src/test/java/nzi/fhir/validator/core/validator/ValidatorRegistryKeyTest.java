package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.terminology.TerminologyMode;
import nzi.fhir.validator.core.terminology.TerminologySettings;
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
 * The keyed registry with a fake builder (spec 004 research R2, data-model §2.5; spec 007 FR-021): base and pinned
 * slots only. Unchanged keys are not rebuilt, excluded keys are dropped, a key that isn't included is refused with 400
 * and never built, catalog changes mark pinned contexts stale, and a changed terminology setting rebuilds.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
class ValidatorRegistryKeyTest {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    /** Records builds; each completes when the test says so, or at once when {@code auto} is set. */
    static final class FakeBuilder extends SnapshotBuilder {
        final List<SnapshotKey> builds = new ArrayList<>();
        final List<ResolvedContext> contexts = new ArrayList<>();
        final List<Promise<ValidatorSnapshot>> pending = new ArrayList<>();
        volatile boolean auto = true;
        volatile RuntimeException failWith;

        FakeBuilder() {
            super(null, null, new nzi.fhir.validator.core.npm.PackageCache(1), 8, null);
        }

        @Override
        public synchronized Future<ValidatorSnapshot> build(SnapshotKey key, long revision, ResolvedContext resolved) {
            builds.add(key);
            contexts.add(resolved);
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

    private ValidatorRegistry registry(Vertx vertx) {
        ValidatorRegistry registry = new ValidatorRegistry(vertx, builder);
        registry.setCatalog(() -> catalogView);
        return registry;
    }

    private PackageCatalogView catalogView = view(Map.of("a#1", (byte) 1, "b#1", (byte) 2, "c#1", (byte) 3, "d#1", (byte) 4));

    private static PackageCatalogView view(Map<String, Byte> hashes) {
        return view(1, hashes);
    }

    private static PackageCatalogView view(long revision, Map<String, Byte> hashes) {
        Map<String, PackageCatalogView.Entry> entries = new java.util.HashMap<>();
        hashes.forEach((k, h) -> entries.put(k, new PackageCatalogView.Entry(java.util.Set.of(), List.of(), new byte[]{h}, 1)));
        return new PackageCatalogView(revision, entries);
    }

    @Test
    void basePinnedAndUnchangedKeysAreNotRebuilt(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
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

    /** Spec 007 FR-004: a changed terminology setting rebuilds the context; the same setting doesn't. */
    @Test
    void aChangedTerminologySettingRebuildsTheContext(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
        TerminologySettings routed = new TerminologySettings(TerminologyMode.ECOSYSTEM, null);
        registry.applyPinned(R4, 1, Map.of("a#1", ctx("a#1", (byte) 1)));
        assertTrue(registry.applyPinned(R4, 2, Map.of("a#1", ctx("a#1", (byte) 1).withTerminology(routed))).succeeded());
        assertEquals(2, builder.count(key("a#1")));
        assertEquals(routed, builder.contexts.get(builder.contexts.size() - 1).terminology(), "built with the new setting");
        assertTrue(registry.applyPinned(R4, 3, Map.of("a#1", ctx("a#1", (byte) 1).withTerminology(routed))).succeeded());
        assertEquals(2, builder.count(key("a#1")), "same setting: no rebuild");
    }

    /** Spec 007 FR-021: an excluded context is dropped, not kept on demand. */
    @Test
    void anExcludedKeyIsDropped(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
        registry.applyPinned(R4, 1, Map.of("a#1", ctx("a#1", (byte) 1), "b#1", ctx("b#1", (byte) 2)));
        registry.applyPinned(R4, 2, Map.of("a#1", ctx("a#1", (byte) 1)));
        assertNull(registry.kind(key("b#1")), "dropped");
        assertEquals(ValidatorRegistry.Status.ABSENT, registry.status(key("b#1")));
        assertEquals(1, registry.contextCount(R4, ValidatorRegistry.Kind.PINNED));
        ProblemException p = assertInstanceOf(ProblemException.class, registry.snapshot(key("b#1")).cause());
        assertEquals(400, p.status());
    }

    /** Spec 007 FR-021: a key that isn't included is refused with 400 and nothing is built. */
    @Test
    void aKeyThatIsNotIncludedIsRefusedAndNeverBuilt(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
        registry.applyPinned(R4, 1, Map.of());
        ProblemException p = assertInstanceOf(ProblemException.class, registry.snapshot(key("c#1")).cause());
        assertEquals(400, p.status());
        assertEquals("invalid-request", p.type());
        assertEquals(ProblemException.igNotIncluded("c#1", R4).detail(), p.detail());
        assertEquals(0, builder.count(key("c#1")));
        assertNull(registry.kind(key("c#1")));
    }

    @Test
    void oneKeysFailureLeavesOthersServingAndMakesTheInstanceUnready(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
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
        ProblemException stale = assertInstanceOf(ProblemException.class, registry.snapshot(key("b#1")).cause());
        assertEquals(503, stale.status(), "an included context that isn't ready is 503, not 400");
        registry.close();
    }

    @Test
    void catalogChangeMarksPinnedContextsWithReplacedContentStale(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
        registry.applyPinned(R4, 1, Map.of("a#1", ResolvedContext.of("a#1", catalogView), "c#1", ResolvedContext.of("c#1", catalogView)));
        registry.onCatalogChanged(view(2, Map.of("a#1", (byte) 7, "b#1", (byte) 8, "c#1", (byte) 3, "d#1", (byte) 4)));
        assertEquals(ValidatorRegistry.Status.STALE, registry.status(key("a#1")));
        ProblemException stale = assertThrows(ProblemException.class, () -> registry.current(key("a#1")));
        assertEquals(503, stale.status());
        assertTrue(registry.notReadyReasons().get(0).contains("package a#1 was replaced"), registry.notReadyReasons().toString());
        assertEquals(ValidatorRegistry.Status.ACTIVE, registry.status(key("c#1")), "unchanged content stays active");
    }

    /** Convergence T074 (spec 004 FR-025): an older catalog view never marks a context built from a newer one stale. */
    @Test
    void anOlderCatalogViewNeverMarksContextsStale(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
        registry.applyPinned(R4, 1, Map.of("a#1", ResolvedContext.of("a#1", catalogView)));
        registry.onCatalogChanged(view(5, Map.of("a#1", (byte) 1)));
        registry.onCatalogChanged(view(3, Map.of("a#1", (byte) 9)));
        assertEquals(ValidatorRegistry.Status.ACTIVE, registry.status(key("a#1")), "outdated metadata is ignored");
        registry.onCatalogChanged(view(6, Map.of("a#1", (byte) 9)));
        assertEquals(ValidatorRegistry.Status.STALE, registry.status(key("a#1")), "a newer view still invalidates");
    }

    /** Spec 004 FR-032, contracts §5: health lists every context with its kind (only pinned since spec 007). */
    @Test
    void healthDetailsListEveryContext(Vertx vertx) {
        ValidatorRegistry registry = registry(vertx);
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            registry.applyPinned(v, 1, Map.of());
        }
        registry.applyPinned(R4, 2, Map.of("a#1", ctx("a#1", (byte) 1), "b#1", ctx("b#1", (byte) 2)));

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
        assertEquals(List.of("a#1", "b#1"), contexts.stream().map(c -> ((io.vertx.core.json.JsonObject) c).getString("key")).toList());
        assertEquals(List.of("pinned", "pinned"),
                contexts.stream().map(c -> ((io.vertx.core.json.JsonObject) c).getString("kind")).toList());
        assertEquals(List.of("a#1"), contexts.getJsonObject(0).getJsonArray("packages").getList());
        assertTrue(registry.notReadyReasons().isEmpty());

        builder.failWith = new SnapshotBuilder.MissingPackageException("a#1");
        registry.applyPinned(R4, 3, Map.of("a#1", ctx("a#1", (byte) 5)));
        assertEquals(List.of("R4:a#1 validator is out of date: Package a#1 is not stored"), registry.notReadyReasons(),
                "a stale pinned context makes the instance unready");
        registry.close();
    }

    /** Spec 007 FR-004: the terminology setting is part of a context's build identity. */
    @Test
    void sameAsComparesTheTerminologySetting() {
        ResolvedContext offline = ctx("a#1", (byte) 1);
        ResolvedContext routed = offline.withTerminology(new TerminologySettings(TerminologyMode.ECOSYSTEM, null));
        assertEquals(TerminologySettings.NONE, offline.terminology());
        assertTrue(offline.sameAs(ctx("a#1", (byte) 1)));
        assertFalse(offline.sameAs(routed), "same packages, different terminology");
        assertFalse(routed.sameAs(offline));
        assertTrue(routed.sameAs(ctx("a#1", (byte) 1).withTerminology(routed.terminology())));
        assertEquals(offline.packages(), routed.packages());
    }
}
