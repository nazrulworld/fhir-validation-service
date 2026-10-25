package nzi.fhir.validator.web.endpoint;

import io.vertx.ext.web.openapi.RouterBuilder;
import nzi.fhir.validator.core.metrics.ServiceMetrics;

/**
 * Prometheus scrape endpoint ({@code GET /metrics}).
 */
public class MetricsApi {
    private static final String PROMETHEUS_TEXT = "text/plain; version=0.0.4; charset=utf-8";

    public MetricsApi(RouterBuilder routerBuilder, ServiceMetrics metrics) {
        routerBuilder.operation("metricsApiScrape")
                .handler(ctx -> ctx.response()
                        .putHeader("Content-Type", PROMETHEUS_TEXT)
                        .end(metrics.scrape()));
    }
}
