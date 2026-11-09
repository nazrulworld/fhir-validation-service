package nzi.fhir.validator.core.terminology;

import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 007 data-model §1: the terminology setting of an included context, its JSON form and URL normalisation. */
class TerminologySettingsTest {

    private static ProblemException refused(String json) {
        ProblemException e = assertThrows(ProblemException.class, () -> TerminologySettings.parse(new JsonObject(json)));
        assertEquals(400, e.status());
        assertEquals("invalid-request", e.type());
        assertFalse(e.detail().contains("Exception"), e.detail());
        return e;
    }

    @Test
    void parsesTheThreeModes() {
        assertEquals(TerminologySettings.NONE, TerminologySettings.parse(new JsonObject("{\"mode\":\"none\"}")));
        assertEquals(new TerminologySettings(TerminologyMode.SERVER, "https://tx.fhir.ch/r4"),
                TerminologySettings.parse(new JsonObject("{\"mode\":\"server\",\"server\":\"https://tx.fhir.ch/r4\"}")));
        assertEquals(new TerminologySettings(TerminologyMode.ECOSYSTEM, null),
                TerminologySettings.parse(new JsonObject("{\"mode\":\"ecosystem\"}")));
        assertEquals(new TerminologySettings(TerminologyMode.ECOSYSTEM, "http://tx.fhir.org/r4"),
                TerminologySettings.parse(new JsonObject("{\"mode\":\"ecosystem\",\"server\":\"http://tx.fhir.org/r4/\"}")));
    }

    @Test
    void refusesMalformedSettings() {
        assertTrue(refused("{}").detail().contains("mode"));
        refused("{\"mode\":\"remote\"}");
        refused("{\"mode\":\"SERVER\",\"server\":\"https://a.example/fhir\"}");
        refused("{\"mode\":1}");
        assertTrue(refused("{\"mode\":\"none\",\"extra\":true}").detail().contains("extra"));
        assertTrue(refused("{\"mode\":\"server\"}").detail().contains("server"));
        refused("{\"mode\":\"none\",\"server\":\"https://a.example/fhir\"}");
        refused("{\"mode\":\"server\",\"server\":\"https://a.example/" + "x".repeat(500) + "\"}");
        refused("{\"mode\":\"server\",\"server\":\"ftp://a.example/fhir\"}");
        refused("{\"mode\":\"server\",\"server\":\"not a url\"}");
        refused("{\"mode\":\"server\",\"server\":\"https://a.example/fhir?x=1\"}");
        refused("{\"mode\":\"server\",\"server\":\"https://a.example/fhir#frag\"}");
        refused("{\"mode\":\"server\",\"server\":\"https://user:pw@a.example/fhir\"}");
        refused("{\"mode\":\"server\",\"server\":42}");
    }

    @Test
    void normalisesUrls() {
        assertEquals("http://tx.fhir.org/r4", TerminologySettings.normaliseUrl("HTTP://TX.FHIR.ORG/r4/"));
        assertEquals("http://tx.fhir.org/r4", TerminologySettings.normaliseUrl("http://tx.fhir.org:80/r4"));
        assertEquals("https://tx.fhir.ch/r4", TerminologySettings.normaliseUrl("https://tx.fhir.ch:443/r4"));
        assertEquals("https://tx.fhir.ch:8443/r4", TerminologySettings.normaliseUrl("https://tx.fhir.ch:8443/r4"));
        assertEquals("https://ts.caty.hdata.tw", TerminologySettings.normaliseUrl("https://ts.caty.hdata.tw/"));
        assertEquals("https://a.example/Path/Case", TerminologySettings.normaliseUrl("https://A.example/Path/Case"),
                "the path keeps its case");
        assertThrows(IllegalArgumentException.class, () -> TerminologySettings.normaliseUrl("https://a.example/f?x"));
        assertThrows(IllegalArgumentException.class, () -> TerminologySettings.normaliseUrl("mailto:a@b"));
    }

    @Test
    void jsonFormAndEquality() {
        assertEquals(new JsonObject().put("mode", "none"), TerminologySettings.NONE.toJson());
        TerminologySettings s = new TerminologySettings(TerminologyMode.SERVER, "https://tx.fhir.ch/r4");
        assertEquals(new JsonObject().put("mode", "server").put("server", "https://tx.fhir.ch/r4"), s.toJson());
        assertEquals(s, TerminologySettings.parse(s.toJson()));
        assertEquals(s.hashCode(), new TerminologySettings(TerminologyMode.SERVER, "https://tx.fhir.ch/r4").hashCode());
        assertNotEquals(s, TerminologySettings.NONE);
        assertEquals(TerminologyMode.ECOSYSTEM, TerminologyMode.fromWire("ecosystem"));
        assertEquals("server", TerminologyMode.SERVER.wire());
    }
}
