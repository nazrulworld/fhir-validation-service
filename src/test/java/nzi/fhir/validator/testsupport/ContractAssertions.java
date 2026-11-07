package nzi.fhir.validator.testsupport;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared assertions for the HTTP contract (contracts/http-api.md).
 */
public final class ContractAssertions {

    private ContractAssertions() {
    }

    /**
     * The response is an RFC 9457 problem with the given status and type, without internal details.
     */
    public static JsonObject assertProblem(HttpResponse<Buffer> r, int status, String type) {
        assertEquals(status, r.statusCode(), r.bodyAsString());
        assertNotNull(r.getHeader("Content-Type"), "Content-Type missing");
        assertTrue(r.getHeader("Content-Type").startsWith("application/problem+json"), r.getHeader("Content-Type"));
        JsonObject body = r.bodyAsJsonObject();
        assertTrue(body.getString("type").endsWith("/" + type), "type: " + body.getString("type"));
        assertEquals(status, body.getInteger("status"));
        assertNotNull(body.getString("title"));
        assertNotNull(body.getString("correlationId"));
        assertEquals(r.getHeader("X-Request-Id"), body.getString("correlationId"));
        String text = r.bodyAsString();
        assertFalse(text.contains("java.") || text.contains("Exception") || text.contains("\tat "), "internal details leaked: " + text);
        return body;
    }

    /**
     * Every top-level field of {@code body} is a property declared by {@code components.schemas.<schema>} in
     * {@code openapi.yaml}, and {@code required} properties are declared there too.
     */
    @SuppressWarnings("unchecked")
    public static void assertDeclaredBySchema(JsonObject body, String schema, String... required) {
        java.util.Map<String, Object> properties = schemaProperties(schema);
        for (String field : body.fieldNames()) {
            assertTrue(properties.containsKey(field), "openapi.yaml " + schema + " does not declare '" + field + "'");
        }
        for (String field : required) {
            assertTrue(properties.containsKey(field), "openapi.yaml " + schema + " does not declare '" + field + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> schemaProperties(String schema) {
        try (java.io.InputStream in = ContractAssertions.class.getResourceAsStream("/openapi.yaml")) {
            java.util.Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(java.util.Objects.requireNonNull(in));
            java.util.Map<String, Object> schemas = (java.util.Map<String, Object>)
                    ((java.util.Map<String, Object>) root.get("components")).get("schemas");
            java.util.Map<String, Object> definition = (java.util.Map<String, Object>) schemas.get(schema);
            assertNotNull(definition, "openapi.yaml has no schema " + schema);
            return (java.util.Map<String, Object>) definition.get("properties");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
