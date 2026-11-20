package nzi.fhir.validator.core.validator;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.model.MediaTypes;
import nzi.fhir.validator.core.npm.DependencyResolver;
import nzi.fhir.validator.core.npm.SharedPackageDefinitions;
import nzi.fhir.validator.core.terminology.EffectiveTerminology;
import nzi.fhir.validator.core.terminology.RegistryResolver;
import nzi.fhir.validator.core.terminology.TerminologyOverride;
import nzi.fhir.validator.core.terminology.TerminologyRouter;
import nzi.fhir.validator.core.terminology.TerminologyServices;
import nzi.fhir.validator.core.terminology.TerminologySettings;
import nzi.fhir.validator.core.terminology.TestTerminology;
import nzi.fhir.validator.testsupport.Fixtures;
import nzi.fhir.validator.testsupport.GoldenOutcomes;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 SC-004, FR-021 to FR-023 (release gate): with package definitions shared between contexts, validation
 * results are identical to 1.0.0's. The corpus ({@code src/test/resources/corpus/010}, see its README) is validated in
 * three R4 contexts built on one instance's {@link SharedPackageDefinitions} (DK Core 3.5.0, MedCom Core 4.0.0, which
 * share ten packages, and a context where two packages define the same canonical URL), sequentially and then with 8
 * threads in random order, and every OperationOutcome is compared with the golden file recorded from the v1.0.0 tag
 * ({@code scripts/golden-010.sh}).
 * <p>
 * The packages come from the locked test packages ({@code scripts/fetch-test-packages.sh}); the package list of each
 * context is resolved by the service's own {@link DependencyResolver}, as the include did on 1.0.0.
 */
@Tag("release-gate")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class SharedDefinitionsDifferentialIT {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    static final String DUP = "test.dup.canonical#1.0.0";

    private final Map<String, byte[]> archives = new LinkedHashMap<>();
    private final Map<String, NpmPackage> parsed = new HashMap<>();
    private final Map<String, byte[]> hashes = new HashMap<>();
    private final Map<String, ValidatorSnapshot> contexts = new HashMap<>();
    private SharedPackageDefinitions shared;
    private Path corpus;

    /** The corpus folder of each context, and the IG it validates against. */
    static final Map<String, String> CONTEXTS = Map.of("dk-core", Fixtures.DK_CORE_350, "medcom-core", Fixtures.MEDCOM_CORE_400);

    @BeforeAll
    void buildContexts() throws Exception {
        corpus = Path.of(getClass().getClassLoader().getResource(GoldenOutcomes.CORPUS).toURI());
        for (String id : Fixtures.lockedPackages()) {
            archives.put(id, Fixtures.lockedPackage(id));
        }
        archives.put(DUP, Files.readAllBytes(corpus.resolve("duplicate-canonical/test.dup.canonical-1.0.0.tgz")));
        Map<String, List<String>> catalog = new HashMap<>();
        for (Map.Entry<String, byte[]> e : archives.entrySet()) {
            NpmPackage pkg = NpmPackage.fromPackage(new ByteArrayInputStream(e.getValue()));
            parsed.put(e.getKey(), pkg);
            hashes.put(e.getKey(), MessageDigest.getInstance("SHA-256").digest(e.getValue()));
            List<String> deps = new ArrayList<>();
            pkg.dependencies().forEach(d -> deps.add(d));
            catalog.put(e.getKey(), deps);
        }
        shared = new SharedPackageDefinitions();
        for (String root : List.of(Fixtures.DK_CORE_350, Fixtures.MEDCOM_CORE_400, DUP)) {
            contexts.put(root, build(root, TerminologyServices.OFFLINE, shared));
        }
    }

    /**
     * One context as the service builds it: the resolved package list, in its sorted order, with the given terminology
     * infrastructure and shared definitions ({@code null}: every package parsed for this context alone, as on 1.0.0).
     */
    private ValidatorSnapshot build(String root, TerminologyServices services, SharedPackageDefinitions sharedDefinitions) {
        Map<String, List<String>> deps = new HashMap<>();
        for (Map.Entry<String, NpmPackage> e : parsed.entrySet()) {
            deps.put(e.getKey(), e.getValue().dependencies());
        }
        DependencyResolver.Resolution resolution = DependencyResolver.resolve(List.of(root), DependencyResolver.Catalog.of(deps));
        // Lower versions superseded by a higher one ("highest wins") are reported missing and skipped, exactly as when
        // 1.0.0 built these contexts for the golden files.
        assertTrue(resolution.resolved().contains(root));
        List<String> resolved = resolution.resolved();
        List<NpmPackage> npm = resolved.stream().map(parsed::get).toList();
        Map<String, byte[]> h = new HashMap<>();
        resolved.forEach(p -> h.put(p, hashes.get(p)));
        return SnapshotBuilder.assemble(SnapshotKey.of(R4, root), 1, resolved, h, npm, services, sharedDefinitions);
    }

    /** A corpus file to validate: its path relative to the corpus root, and the context it belongs to. */
    record Case(String relative, String context) {
    }

    private List<Case> cases() throws Exception {
        List<Case> out = new ArrayList<>();
        for (String group : List.of("examples", "invalid")) {
            for (Map.Entry<String, String> c : CONTEXTS.entrySet()) {
                try (Stream<Path> files = Files.list(corpus.resolve(group).resolve(c.getKey()))) {
                    files.sorted().forEach(f -> out.add(new Case(group + "/" + c.getKey() + "/" + f.getFileName(), c.getValue())));
                }
            }
        }
        try (Stream<Path> files = Files.list(corpus.resolve("duplicate-canonical/resources"))) {
            files.sorted().forEach(f -> out.add(new Case("duplicate-canonical/resources/" + f.getFileName(), DUP)));
        }
        return out;
    }

    private static String goldenPath(Case c) {
        return c.relative().startsWith("duplicate-canonical/")
                ? "duplicate-canonical/" + Path.of(c.relative()).getFileName() : c.relative();
    }

    private void check(Case c) throws Exception {
        String body = Files.readString(corpus.resolve(c.relative()), StandardCharsets.UTF_8);
        ValidatorSnapshot.Outcome outcome = contexts.get(c.context()).validate(body, SupportedContentType.JSON, List.of());
        String operationOutcome = OutcomeRenderer.render(outcome, MediaTypes.ResponseFormat.FHIR_JSON);
        GoldenOutcomes.assertSame(GoldenOutcomes.golden(corpus, goldenPath(c)), operationOutcome, c.relative());
    }

    @Test
    void theTwoIgContextsShareMostOfTheirPackages() {
        long dk = contexts.get(Fixtures.DK_CORE_350).resolvedPackages().size();
        long medcom = contexts.get(Fixtures.MEDCOM_CORE_400).resolvedPackages().size();
        assertTrue(shared.entries(R4) < dk + medcom, "shared entries: " + shared.entries(R4));
        assertEquals(13, shared.entries(R4), "the 12 locked packages and the duplicate-canonical test package, each once");
    }

    @Test
    void resultsAreIdenticalTo100Sequentially() throws Exception {
        List<Case> all = cases();
        assertTrue(all.size() >= 84 + 50, "the corpus has every example and at least 50 invalid variants");
        for (Case c : all) {
            check(c);
        }
    }

    @Test
    void resultsAreIdenticalTo100Concurrently() throws Exception {
        List<Case> rounds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rounds.addAll(cases());
        }
        Collections.shuffle(rounds, new Random(10));
        runConcurrently(rounds, c -> () -> check(c));
    }

    /**
     * Spec 013 SC-001 (opt-in: {@code FV_LOAD_ROUNDS=75}, on a 4-CPU machine): the corpus validated {@code rounds} times
     * on 8 threads, at least 10,000 validations, and every result equal to the one recorded alone (the golden file, which
     * {@link #resultsAreIdenticalTo100Sequentially} checks the resource gives alone). Prints the re-checks needed.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "FV_LOAD_ROUNDS", matches = "[1-9][0-9]*")
    void theSameVerdictUnderSustainedLoad() throws Exception {
        int rounds = Integer.parseInt(System.getenv("FV_LOAD_ROUNDS"));
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> counts = new java.util.concurrent.ConcurrentHashMap<>();
        RegexRecheck.Listener counting = new RegexRecheck.Listener() {
            @Override
            public void onRecheck(SupportedFhirVersion version, String kind) {
                counts.computeIfAbsent("recheck " + kind, k -> new java.util.concurrent.atomic.LongAdder()).increment();
            }

            @Override
            public void onOutcome(SupportedFhirVersion version, String contextKey, String outcome, String reason, int attempts) {
                counts.computeIfAbsent(outcome + (reason.isEmpty() ? "" : " " + reason),
                        k -> new java.util.concurrent.atomic.LongAdder()).increment();
            }
        };
        RegexRecheck.resetSharedStateForTests();
        contexts.values().forEach(s -> s.setRegexRecheck(new RegexRecheck(30_000, counting)));
        try {
            List<Case> load = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                load.addAll(cases());
            }
            Collections.shuffle(load, new Random(13));
            long started = System.nanoTime();
            runConcurrently(load, c -> () -> check(c));
            System.out.printf("SC-001: %d validations on 8 threads in %d s, 0 differences; re-checks %s%n", load.size(),
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started), new java.util.TreeMap<>(counts));
            assertTrue(load.size() >= 10_000 || rounds < 75, "FV_LOAD_ROUNDS=75 gives at least 10,000 validations");
        } finally {
            contexts.values().forEach(s -> s.setRegexRecheck(RegexRecheck.DEFAULT));
        }
    }

    /** The corpus root resolves from the test classpath (sanity, independent of the packages). */
    @Test
    void goldenFilesExistForEveryCase() throws Exception {
        for (Case c : cases()) {
            assertTrue(Files.isRegularFile(corpus.resolve("golden").resolve(goldenPath(c))), goldenPath(c));
        }
        JsonArray sample = GoldenOutcomes.golden(corpus, "duplicate-canonical/Patient-no-birthdate.json");
        assertFalse(sample.isEmpty());
        assertNotNull(new JsonObject().put("x", sample).getJsonArray("x"));
    }

    /**
     * FR-023 with a terminology server: given identical server answers (a stub that knows a few SNOMED CT and LOINC
     * codes and rejects the rest), contexts built on shared definitions give the same OperationOutcomes as contexts
     * that parse every package for themselves (the 1.0.0 behaviour), sequentially and with 8 threads.
     */
    @Test
    void withATerminologyServerSharedAndPrivateContextsAgree() throws Exception {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        nzi.fhir.validator.testsupport.StubTerminologyServer stub = new nzi.fhir.validator.testsupport.StubTerminologyServer("/r4")
                .code("http://loinc.org", "8867-4", "Heart rate")
                .code("http://snomed.info/sct", "399912005", "Pressure ulcer");
        try {
            stub.start(vertx).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            var allowlist = TestTerminology.allowlist(R4, stub.base());
            var clients = TestTerminology.clients(allowlist, 5_000);
            TerminologyServices services = TestTerminology.services(allowlist, clients,
                    RegistryResolver.DISABLED, 60_000);
            // Parsed definitions don't depend on the terminology setting: the shared side reuses the class's shared
            // definitions (no second parse); only the private baseline parses every package again.
            Map<String, ValidatorSnapshot> sharedCtx = new HashMap<>();
            Map<String, ValidatorSnapshot> privateCtx = new HashMap<>();
            for (String root : List.of(Fixtures.DK_CORE_350, Fixtures.MEDCOM_CORE_400, DUP)) {
                sharedCtx.put(root, build(root, services, shared));
                privateCtx.put(root, build(root, services, null));
            }
            var effective = EffectiveTerminology.resolve(
                    TerminologySettings.NONE,
                    new TerminologyOverride(null, stub.base()));
            var router = TerminologyRouter.server(stub.base());
            List<Case> all = cases();
            Map<String, String> expected = new HashMap<>();
            for (Case c : all) {
                expected.put(c.relative(), render(privateCtx.get(c.context()), c, effective, router));
                assertEquals(expected.get(c.relative()), render(sharedCtx.get(c.context()), c, effective, router),
                        c.relative() + ": shared and private contexts differ with a terminology server");
            }
            assertTrue(stub.hits() > 0, "the corpus reached the terminology server");
            privateCtx.clear(); // the baseline is recorded; release its contexts before the concurrent phase
            List<Case> rounds = new ArrayList<>(all);
            rounds.addAll(all);
            Collections.shuffle(rounds, new Random(23));
            runConcurrently(rounds, c -> () -> assertEquals(expected.get(c.relative()),
                    render(sharedCtx.get(c.context()), c, effective, router), c.relative() + ": differs under concurrent load"));
        } finally {
            stub.stop();
            vertx.close();
        }
    }

    /** The comparison projection of one validation in mode server. */
    private String render(ValidatorSnapshot snapshot, Case c, EffectiveTerminology effective,
                          TerminologyRouter router) throws Exception {
        String body = Files.readString(corpus.resolve(c.relative()), StandardCharsets.UTF_8);
        ValidatorSnapshot.Outcome outcome = snapshot.validate(body, SupportedContentType.JSON, List.of(), Integer.MAX_VALUE,
                effective, router);
        return GoldenOutcomes.project(OutcomeRenderer.render(outcome, MediaTypes.ResponseFormat.FHIR_JSON)).encode();
    }

    /** A check that may throw (the comparison of one case). */
    interface Check {
        void run() throws Exception;
    }

    /**
     * Runs every case's check on 8 threads. No retry for regex timeouts (spec 013 SC-002): a validation whose regex
     * check times out under this load is re-checked by the snapshot itself ({@link RegexRecheck}), so any difference
     * here is a real one.
     */
    private static void runConcurrently(List<Case> cases, java.util.function.Function<Case, Check> checkOf) throws Exception {
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Case c : cases) {
                futures.add(pool.submit(() -> {
                    checkOf.apply(c).run();
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(10, TimeUnit.MINUTES);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof AssertionError a) {
                        throw a;
                    }
                    throw e;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
