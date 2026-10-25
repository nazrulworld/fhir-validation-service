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
}
