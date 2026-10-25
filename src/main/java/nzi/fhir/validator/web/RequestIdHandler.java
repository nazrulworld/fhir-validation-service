package nzi.fhir.validator.web;

import io.reactiverse.contextual.logging.ContextualData;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.PlatformHandler;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accepts or generates the {@code X-Request-Id} correlation id, echoes it on the response and makes it available to
 * every log line written while serving the request (FR-031). A platform handler, so it runs before body parsing and
 * every other handler, and even body-limit errors carry the id.
 */
public class RequestIdHandler implements PlatformHandler {
    public static final String HEADER = "X-Request-Id";
    public static final String KEY = "requestId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public void handle(RoutingContext ctx) {
        String id = ctx.request().getHeader(HEADER);
        if (id == null || !VALID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        ctx.put(KEY, id);
        ContextualData.put(KEY, id);
        ctx.response().putHeader(HEADER, id);
        ctx.next();
    }

    public static String requestId(RoutingContext ctx) {
        String id = ctx.get(KEY);
        if (id == null) {
            id = UUID.randomUUID().toString();
            ctx.put(KEY, id);
            if (!ctx.response().headWritten()) {
                ctx.response().putHeader(HEADER, id);
            }
        }
        return id;
    }
}
