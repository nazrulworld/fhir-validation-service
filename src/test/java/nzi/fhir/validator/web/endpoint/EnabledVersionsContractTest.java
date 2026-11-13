package nzi.fhir.validator.web.endpoint;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 009 (T006, contracts/http-api.md): {@code openapi.yaml} declares the 422 on including a context for a FHIR
 * version that isn't enabled, and the new optional health fields.
 */
class EnabledVersionsContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> root() throws Exception {
        try (InputStream in = EnabledVersionsContractTest.class.getResourceAsStream("/openapi.yaml")) {
            return new org.yaml.snakeyaml.Yaml().load(Objects.requireNonNull(in));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String... path) {
        Map<String, Object> current = m;
        for (String key : path) {
            current = (Map<String, Object>) current.get(key);
            assertNotNull(current, "openapi.yaml has no " + String.join(".", path));
        }
        return current;
    }

    @Test
    void includeDeclaresFhirVersionDisabled() throws Exception {
        Map<String, Object> root = root();
        Map<String, Object> responses = map(root, "paths", "/{version}/validator", "post", "responses");
        Map<String, Object> r422 = map(responses, "422");
        assertEquals("#/components/responses/FhirVersionDisabled", r422.get("$ref"));
        Map<String, Object> declared = map(root, "components", "responses", "FhirVersionDisabled");
        assertTrue(String.valueOf(declared.get("description")).contains("fhir-version-disabled"));
        // Exclude and change keep their existing responses: no 422 there (FR-032).
        assertFalse(map(root, "paths", "/{version}/validator", "delete", "responses").containsKey("422"));
        assertFalse(map(root, "paths", "/{version}/validator", "patch", "responses").containsKey("422"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthResponseDeclaresServedVersionsAndSecurity() throws Exception {
        Map<String, Object> properties = map(root(), "components", "schemas", "HealthResponse", "properties");
        Map<String, Object> fhirVersions = map(properties, "fhirVersions", "properties");
        assertTrue(fhirVersions.containsKey("served"));
        Map<String, Object> items = map(fhirVersions, "inactiveContexts", "properties", "items");
        assertEquals(100, items.get("maxItems"));
        Map<String, Object> reason = map(items, "items", "properties", "reason");
        assertEquals(List.of("fhir-version-disabled"), reason.get("enum"));
        Map<String, Object> security = map(properties, "security", "properties");
        assertEquals(List.of("enabled", "disabled"), map(security, "aclSecurity").get("enum"));
        assertFalse(security.containsKey("adminNetworkRestricted"), "code review: not reported on /health");
        assertTrue(map(properties, "fhirVersions", "properties", "inactiveContexts", "properties").containsKey("private"));
    }
}
