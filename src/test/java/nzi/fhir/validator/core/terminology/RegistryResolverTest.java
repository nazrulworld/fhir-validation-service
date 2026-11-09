package nzi.fhir.validator.core.terminology;

import com.github.benmanes.caffeine.cache.Ticker;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testsupport.StubTxRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static nzi.fhir.validator.core.enums.SupportedFhirVersion.R4;
import static org.junit.jupiter.api.Assertions.*;

/** Spec 007 FR-016, FR-017, research R9: the choice order and the caching of registry answers. */
@ExtendWith(VertxExtension.class)
class RegistryResolverTest {
    private static final String A = "https://a.example/fhir";
    private static final String B = "https://b.example/fhir";
    private static final String TX = "http://tx.fhir.org/r4";
    private static final String CH = "https://tx.fhir.ch/r4";
    private static final String LOINC = "http://loinc.org";

    private StubTxRegistry stub;
    private final AtomicLong nanos = new AtomicLong();
    private RegistryResolver resolver;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        stub = new StubTxRegistry().replace("{{BASE}}", A).replace("{{BASE2}}", B);
        stub.start(vertx).onComplete(ctx.succeeding(s -> {
            resolver = new RegistryResolver(stub.base(), NetworkGuard.forTests(), 2_000, (Ticker) nanos::get);
            ctx.completeNow();
        }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        stub.stop().onComplete(ar -> ctx.completeNow());
    }

    private static TxServerAllowlist allow(String... urls) {
        return TestTerminology.allowlist(R4, urls).current();
    }

    @Test
    void anAuthoritativeServerComesFirst() {
        stub.resolve("R4", LOINC, "resolve-R4-authoritative.json");
        assertEquals(B, resolver.choose(R4, LOINC, null, allow(A, B)).orElseThrow());
        assertEquals(A, resolver.choose(R4, LOINC, null, allow(A)).orElseThrow(), "a non-allowlisted authority is skipped");
    }

    @Test
    void amongCompleteCandidatesTheDefaultServerIsPreferredThenRegistryOrder() {
        stub.resolve("R4", LOINC, "resolve-R4-loinc.json");
        assertEquals(TX, resolver.choose(R4, LOINC, null, allow(TX, CH, "http://tx.hl7europe.eu/r4")).orElseThrow(),
                "tx.fhir.org among equals");
        assertEquals("http://tx.hl7europe.eu/r4", resolver.choose(R4, LOINC, null, allow(CH, "http://tx.hl7europe.eu/r4"))
                .orElseThrow(), "otherwise the first complete one in registry order");
        assertEquals(CH, resolver.choose(R4, LOINC, CH, allow(TX, CH, "http://tx.hl7europe.eu/r4")).orElseThrow(),
                "the context's own server wins among equals, replacing tx.fhir.org");
    }

    @Test
    void notPresentCandidatesAreIgnoredAndTheFallbackApplies() {
        stub.resolve("R4", LOINC, "resolve-R4-loinc.json");
        // Only a not-present server is allowlisted (tx.fhir.org isn't): nothing is chosen.
        assertTrue(resolver.choose(R4, LOINC, null, allow("https://tx.hl7.org.au/fhir")).isEmpty());
        // With the context's own server allowlisted, it is the fallback.
        assertEquals(CH, resolver.choose(R4, LOINC, CH, allow("https://tx.hl7.org.au/fhir", CH)).orElseThrow());
    }

    @Test
    void unlistedCandidatesAreSkippedAndFragmentsComeAfterComplete() {
        stub.resolve("R4", LOINC, "resolve-R4-unlisted.json");
        assertEquals(B, resolver.choose(R4, LOINC, null, allow(B)).orElseThrow(), "the unlisted complete one is skipped");
    }

    @Test
    void anUnknownFormatVersionIsAFailureAndTheFallbackApplies() {
        stub.resolve("R4", LOINC, "resolve-R4-format2.json");
        assertEquals(TX, resolver.choose(R4, LOINC, null, allow(A, TX)).orElseThrow(), "format 2 ignored: default server");
        assertEquals(CH, resolver.choose(R4, LOINC, CH, allow(A, CH)).orElseThrow(), "or the context's own server");
    }

    @Test
    void entriesForAnotherVersionOrNeedingCredentialsAreSkipped() {
        JsonObject answer = new JsonObject().put("formatVersion", "1").put("candidates", new io.vertx.core.json.JsonArray()
                .add(new JsonObject().put("url", A).put("fhirVersion", "5.0.0").put("security", "open").put("content", "complete"))
                .add(new JsonObject().put("url", B).put("fhirVersion", "4.0.1").put("security", "api-key").put("content", "complete")));
        RegistryResolver.Answer parsed = RegistryResolver.parse(answer, R4);
        assertEquals(List.of(), parsed.candidates());
        assertFalse(parsed.failed());
    }

    @Test
    void successesAreKeptADayAndFailuresFiveMinutes() {
        stub.resolve("R4", LOINC, "resolve-R4-loinc.json");
        resolver.choose(R4, LOINC, null, allow(TX));
        resolver.choose(R4, LOINC, null, allow(TX));
        assertEquals(1, stub.resolveHits(), "cached");
        nanos.addAndGet(TimeUnit.HOURS.toNanos(23));
        resolver.choose(R4, LOINC, null, allow(TX));
        assertEquals(1, stub.resolveHits());
        nanos.addAndGet(TimeUnit.HOURS.toNanos(2));
        resolver.choose(R4, LOINC, null, allow(TX));
        assertEquals(2, stub.resolveHits(), "asked again after 24 h");

        stub.mode(StubTxRegistry.Mode.UNAVAILABLE);
        String snomed = "http://snomed.info/sct";
        resolver.choose(R4, snomed, null, allow(TX));
        resolver.choose(R4, snomed, null, allow(TX));
        assertEquals(3, stub.resolveHits(), "a failure is cached too");
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(5) + 1);
        resolver.choose(R4, snomed, null, allow(TX));
        assertEquals(4, stub.resolveHits(), "but only for 5 minutes");
    }

    @Test
    void theLookupAsksForValidationAsJson() {
        resolver.choose(R4, LOINC, null, allow(TX));
        assertEquals(List.of("application/json"), stub.accepts());
        String query = stub.queries().get(0);
        assertTrue(query.contains("fhirVersion=R4") && query.contains("usage=validation")
                && query.contains("url=http%3A%2F%2Floinc.org"), query);
    }

    @Test
    void theDisabledResolverOnlyFallsBack() {
        assertEquals(TX, RegistryResolver.DISABLED.choose(R4, LOINC, null, allow(TX)).orElseThrow());
        assertEquals(CH, RegistryResolver.DISABLED.choose(R4, LOINC, CH, allow(CH)).orElseThrow());
        assertTrue(RegistryResolver.DISABLED.choose(SupportedFhirVersion.R4B, LOINC, null, TxServerAllowlist.builtIn()).isEmpty());
    }
}
