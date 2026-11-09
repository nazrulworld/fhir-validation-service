package nzi.fhir.validator.core.terminology;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.testsupport.StubTxRegistry;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static nzi.fhir.validator.core.enums.SupportedFhirVersion.R4;
import static nzi.fhir.validator.core.enums.SupportedFhirVersion.R4B;
import static nzi.fhir.validator.core.enums.SupportedFhirVersion.R5;
import static nzi.fhir.validator.core.enums.SupportedFhirVersion.STU3;
import static org.junit.jupiter.api.Assertions.*;

/** Spec 007 FR-006 to FR-012: the allowlist built from recorded registry answers (2026-11-09) and the fallback. */
class TxServerAllowlistTest {

    private static List<TxServerEntry> kept(String fixture) {
        JsonArray results = new JsonObject(StubTxRegistry.read(fixture)).getJsonArray("results");
        return results.stream().map(o -> TxServerEntry.fromRegistry((JsonObject) o)).flatMap(java.util.Optional::stream).toList();
    }

    private static TxServerAllowlist registry(SupportedFhirVersion v, String fixture) {
        return TxServerAllowlist.builtIn().withVersion(v, TxServerAllowlist.VersionList.of(kept(fixture),
                TxServerAllowlist.Source.REGISTRY, Instant.now(), null));
    }

    @Test
    void keepsOnlyOpenHealthyEntriesOfTheRightVersion() {
        String text = StubTxRegistry.read("discovery-R4-mixed.json")
                .replace("{{BASE}}", "https://a.example/fhir").replace("{{BASE2}}", "https://b.example/fhir");
        List<TxServerEntry> mixed = new JsonObject(text).getJsonArray("results").stream()
                .map(o -> TxServerEntry.fromRegistry((JsonObject) o)).flatMap(java.util.Optional::stream).toList();
        assertEquals(List.of("https://a.example/fhir", "https://b.example/fhir", "https://r5only.example.org/fhir"),
                mixed.stream().map(TxServerEntry::url).toList(), "error, api-key and url-less entries are dropped");
        assertEquals(List.of(R4, R4, R5), mixed.stream().map(TxServerEntry::fhirVersion).toList());
        assertEquals("stub-a", mixed.get(0).serverCode());
        assertEquals(mixed.get(0), TxServerEntry.fromStored(mixed.get(0).toJson()).orElseThrow(), "stored form round-trips");
    }

    @Test
    void theRecordedRegistryListsFourteenOpenR4ServersAndNoneForStu3OrR4b() {
        List<TxServerEntry> r4 = kept("discovery-R4.json");
        assertEquals(14, r4.size(), r4.toString());
        assertTrue(r4.stream().allMatch(e -> e.fhirVersion() == R4));
        assertTrue(r4.stream().anyMatch(e -> e.url().equals("http://tx.fhir.org/r4")));
        assertTrue(r4.stream().noneMatch(e -> e.url().contains("terminologystandardsservice.ca")), "api-key server");
        assertEquals(5, kept("discovery-R5.json").size());
        assertEquals(0, kept("discovery-R3.json").size());
        assertEquals(0, kept("discovery-R4B.json").size());
    }

    @Test
    void mapsVersionsByMajorMinor() {
        for (String[] c : new String[][]{{"4.0.1", "R4"}, {"4.3.0", "R4B"}, {"5.0.0", "R5"}, {"3.0.2", "STU3"}}) {
            assertEquals(SupportedFhirVersion.valueOf(c[1]), TxServerEntry.fromRegistry(new JsonObject()
                    .put("url", "https://a.example/fhir").put("fhirVersion", c[0]).put("security", "open")).orElseThrow().fhirVersion());
        }
        assertTrue(TxServerEntry.fromRegistry(new JsonObject().put("url", "https://a.example/fhir").put("fhirVersion", "6.0.0")
                .put("security", "open")).isEmpty(), "R6 is not enabled");
    }

    @Test
    void matchesExactlyOnTheNormalisedUrlAndTheVersion() {
        TxServerAllowlist list = registry(R4, "discovery-R4.json");
        assertTrue(list.isAllowed("http://tx.fhir.org/r4", R4));
        assertTrue(list.isAllowed("HTTP://TX.FHIR.ORG/r4/", R4));
        assertTrue(list.isAllowed("http://tx.fhir.org:80/r4", R4));
        assertFalse(list.isAllowed("https://tx.fhir.org/r4", R4), "a different scheme is a different URL");
        assertFalse(list.isAllowed("http://tx.fhir.org/r4/extra", R4));
        assertFalse(list.isAllowed("http://tx.fhir.org/r4", R5), "R4 entries never serve R5");
        assertFalse(list.isAllowed("http://tx.fhir.org/r4", STU3));
        assertFalse(list.isAllowed("not a url", R4));
        assertFalse(list.isAllowed(null, R4));
    }

    @Test
    void requireRefusesWith400WhenAListExistsAnd503WhenItDoesNot() {
        TxServerAllowlist list = registry(R4, "discovery-R4.json");
        ProblemException unregistered = assertThrows(ProblemException.class, () -> list.require("http://10.0.0.5/fhir", R4));
        assertEquals(400, unregistered.status());
        assertEquals("tx-server-not-registered", unregistered.type());

        TxServerAllowlist fallback = TxServerAllowlist.builtIn();
        fallback.require("https://tx.fhir.org/r4", R4);
        fallback.require("https://tx.fhir.org/r5", R5);
        assertEquals(503, assertThrows(ProblemException.class, () -> fallback.require("http://tx.fhir.org/r4", R4)).status(),
                "the built-in list is https; the http address is not built in");
        ProblemException unavailable = assertThrows(ProblemException.class, () -> fallback.require("https://tx.fhir.ch/r4", R4));
        assertEquals(503, unavailable.status());
        assertEquals("tx-registry-unavailable", unavailable.type());
        assertEquals(503, assertThrows(ProblemException.class, () -> fallback.require("https://tx.fhir.org/r4", R4B)).status(),
                "no built-in server for R4B");
    }

    @Test
    void theBuiltInListAppliesOnlyUntilARegistryListExists() {
        TxServerAllowlist fallback = TxServerAllowlist.builtIn();
        assertTrue(fallback.isAllowed("https://tx.fhir.org/r4", R4));
        assertFalse(fallback.isAllowed("https://tx.fhir.ch/r4", R4));
        TxServerAllowlist withoutTxFhirOrg = fallback.withVersion(R4, TxServerAllowlist.VersionList.of(
                List.of(new TxServerEntry("https://tx.fhir.ch/r4", R4, "hl7-ch", List.of())),
                TxServerAllowlist.Source.REGISTRY, Instant.now(), null));
        assertFalse(withoutTxFhirOrg.isAllowed("http://tx.fhir.org/r4", R4), "the registry list alone decides");
        assertTrue(withoutTxFhirOrg.isAllowed("https://tx.fhir.ch/r4", R4));
        assertEquals(400, assertThrows(ProblemException.class, () -> withoutTxFhirOrg.require("http://tx.fhir.org/r4", R4)).status());
        assertTrue(withoutTxFhirOrg.isAllowed("https://tx.fhir.org/r5", R5), "other versions keep their own list");
    }

    @Test
    void theDefaultServerIsTxFhirOrgOnlyWhenAllowlisted() {
        assertEquals("http://tx.fhir.org/r4", registry(R4, "discovery-R4.json").defaultServer(R4).orElseThrow(),
                "the registry lists tx.fhir.org as http: that entry is the default server");
        assertEquals("https://tx.fhir.org/r4", TxServerAllowlist.builtIn().defaultServer(R4).orElseThrow());
        assertTrue(TxServerAllowlist.builtIn().defaultServer(R4B).isEmpty());
        TxServerAllowlist without = TxServerAllowlist.builtIn().withVersion(R4, TxServerAllowlist.VersionList.of(List.of(),
                TxServerAllowlist.Source.REGISTRY, Instant.now(), null));
        assertTrue(without.defaultServer(R4).isEmpty());
        assertFalse(TxServerAllowlist.EMPTY.isAllowed("http://tx.fhir.org/r4", R4));
    }

    @Test
    void healthStatusNamesTheSourceOfEachVersion() {
        JsonObject status = registry(R4, "discovery-R4.json").status();
        assertEquals("registry", status.getJsonObject("R4").getString("source"));
        assertEquals(14, status.getJsonObject("R4").getInteger("servers"));
        assertNotNull(status.getJsonObject("R4").getString("fetchedAt"));
        assertEquals("built-in", status.getJsonObject("R5").getString("source"));
        assertTrue(status.getJsonObject("R5").getString("reason").contains("built-in fallback list in use"));
        assertEquals(0, status.getJsonObject("R4B").getInteger("servers"));
        assertTrue(status.getJsonObject("R4B").getString("reason").contains("no built-in server"));
    }
}
