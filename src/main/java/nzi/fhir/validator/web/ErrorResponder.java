package nzi.fhir.validator.web;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.validation.BadRequestException;
import io.vertx.ext.web.validation.BodyProcessorException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The single error format of the service: RFC 9457 {@code application/problem+json} with
 * {@code type, title, status, detail, correlationId}. Guarantees every request is answered exactly once and that no
 * exception text reaches clients (constitution Principle IV, FR-019, FR-020).
 */
public final class ErrorResponder {
    private static final Logger logger = LogManager.getLogger(ErrorResponder.class);

    public static final String PROBLEM_JSON = "application/problem+json";
    public static final String TYPE_BASE = "https://nazrulworld.github.io/fhir-validation-service/problems/";

    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("invalid-request", "Invalid request"),
            Map.entry("invalid-resource", "Invalid resource"),
            Map.entry("unsupported-fhir-version", "Unsupported FHIR version"),
            Map.entry("unauthorized", "Unauthorized"),
            Map.entry("forbidden", "Forbidden"),
            Map.entry("not-found", "Not found"),
            Map.entry("method-not-allowed", "Method not allowed"),
            Map.entry("package-not-found", "Package not found"),
            Map.entry("package-source-not-allowed", "Package source not allowed"),
            Map.entry("package-too-large", "Package too large"),
            Map.entry("package-unsafe", "Unsafe package"),
            Map.entry("unsupported-media-type", "Unsupported media type"),
            Map.entry("not-acceptable", "Not acceptable"),
            Map.entry("payload-too-large", "Payload too large"),
            Map.entry("overloaded", "Overloaded"),
            Map.entry("not-ready", "Not ready"),
            Map.entry("internal-error", "Internal error"));

    private static final Map<Integer, String> DEFAULT_TYPES = Map.of(
            400, "invalid-request",
            401, "unauthorized",
            403, "forbidden",
            404, "not-found",
            405, "method-not-allowed",
            406, "not-acceptable",
            413, "payload-too-large",
            415, "unsupported-media-type",
            500, "internal-error",
            503, "not-ready");

    private ErrorResponder() {
    }

    /**
     * Registers problem responses for router-level failures (unmatched routes, contract validation errors, body
     * limits, uncaught exceptions).
     */
    public static void install(Router router) {
        for (int status : DEFAULT_TYPES.keySet()) {
            router.errorHandler(status, ctx -> fail(ctx, ctx.failure(), ctx.statusCode()));
        }
    }

    /**
     * Ends the response from {@code future}: success writes JSON with 200, failure writes a problem.
     */
    public static void respond(RoutingContext ctx, Future<?> future) {
        respond(ctx, future, ErrorResponder::writeJson);
    }

    public static <T> void respond(RoutingContext ctx, Future<T> future, BiConsumer<RoutingContext, T> onSuccess) {
        future.onComplete(ar -> {
            if (isEnded(ctx)) {
                return;
            }
            if (ar.succeeded()) {
                try {
                    onSuccess.accept(ctx, ar.result());
                } catch (Exception e) {
                    fail(ctx, e);
                }
            } else {
                fail(ctx, ar.cause());
            }
        });
    }

    public static void fail(RoutingContext ctx, Throwable failure) {
        fail(ctx, failure, -1);
    }

    /**
     * @param statusHint the routing context status code when {@code failure} is null (e.g. {@code ctx.fail(413)})
     */
    public static void fail(RoutingContext ctx, Throwable failure, int statusHint) {
        if (isEnded(ctx)) {
            return;
        }
        if (failure instanceof ProblemException p) {
            write(ctx, p);
            return;
        }
        if (failure instanceof BodyProcessorException e
                && e.getErrorType() == BodyProcessorException.BodyProcessorErrorType.MISSING_MATCHING_BODY_PROCESSOR) {
            write(ctx, new ProblemException(415, "unsupported-media-type",
                    "The request content type is not supported by this operation"));
            return;
        }
        if (failure instanceof BadRequestException e) {
            // Contract validation feedback is about the client's own input and safe to return.
            write(ctx, new ProblemException(400, "invalid-request", e.getMessage()));
            return;
        }
        int status = statusHint;
        if (failure instanceof HttpException he) {
            status = he.getStatusCode();
        }
        if (status < 400 || status > 599) {
            status = 500;
        }
        String type = DEFAULT_TYPES.getOrDefault(status, status >= 500 ? "internal-error" : "invalid-request");
        if (status >= 500) {
            logger.error("Request failed: {} {}", ctx.request().method(), ctx.request().path(), failure);
        }
        write(ctx, new ProblemException(status, type, defaultDetail(status)));
    }

    private static String defaultDetail(int status) {
        return switch (status) {
            case 404 -> "No resource matches the request path";
            case 405 -> "The method is not allowed for this resource";
            case 413 -> "The request body exceeds the configured limit";
            case 415 -> "The request content type is not supported";
            case 503 -> "The service is temporarily unavailable";
            case 500 -> "An unexpected error occurred. Quote the correlationId when reporting it";
            default -> "The request could not be processed";
        };
    }

    public static void problem(RoutingContext ctx, int status, String type, String detail) {
        write(ctx, new ProblemException(status, type, detail));
    }

    private static void write(RoutingContext ctx, ProblemException p) {
        if (isEnded(ctx)) {
            return;
        }
        JsonObject body = new JsonObject()
                .put("type", TYPE_BASE + p.type())
                .put("title", TITLES.getOrDefault(p.type(), "Error"))
                .put("status", p.status());
        if (p.detail() != null) {
            body.put("detail", p.detail());
        }
        body.put("correlationId", RequestIdHandler.requestId(ctx));
        p.extensions().forEach(e -> body.put(e.getKey(), e.getValue()));
        HttpServerResponse response = ctx.response();
        p.headers().forEach(response::putHeader);
        if (p.status() == 503 && !response.headers().contains("Retry-After")) {
            response.putHeader("Retry-After", "1");
        }
        response.setStatusCode(p.status())
                .putHeader("Content-Type", PROBLEM_JSON)
                .end(body.encode());
    }

    private static void writeJson(RoutingContext ctx, Object result) {
        HttpServerResponse response = ctx.response();
        response.setStatusCode(200);
        if (result instanceof JsonObject json) {
            response.putHeader("Content-Type", "application/json").end(json.encode());
        } else if (result instanceof JsonArray array) {
            response.putHeader("Content-Type", "application/json").end(array.encode());
        } else if (result instanceof Buffer buffer) {
            response.end(buffer);
        } else if (result == null) {
            response.setStatusCode(204).end();
        } else {
            response.end(result.toString());
        }
    }

    private static boolean isEnded(RoutingContext ctx) {
        return ctx.response().ended() || ctx.response().closed();
    }
}
