package nzi.fhir.validator.web;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.handler.CorsHandler;
import nzi.fhir.validator.core.config.ApplicationConfig;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CORS restricted to explicitly configured origins ({@code FV_CORS_ALLOWED_ORIGINS}). No origins configured means no
 * CORS handler at all; a wildcard is only possible in the dev profile.
 */
public final class CorsConfig {

    private CorsConfig() {
    }

    public static Optional<CorsHandler> create(ApplicationConfig config) {
        List<String> origins = config.corsAllowedOrigins();
        if (origins.isEmpty()) {
            return Optional.empty();
        }
        CorsHandler handler = origins.contains("*") ? CorsHandler.create() : CorsHandler.create().addOrigins(origins);
        return Optional.of(handler
                .allowedMethods(Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.OPTIONS))
                .allowedHeaders(Set.of("Content-Type", "Authorization", "Accept", RequestIdHandler.HEADER))
                .exposedHeaders(Set.of(RequestIdHandler.HEADER, "Retry-After")));
    }
}
