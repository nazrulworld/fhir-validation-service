package nzi.fhir.validator.core.terminology;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 007 FR-026, contracts §6: terminology meters, with safe labels only. */
class TerminologyMetricsTest {

    @Test
    void callsLookupsAndAllowlistAgeAreExported() {
        PrometheusMeterRegistry registry = ServiceMetrics.newRegistry();
        ServiceMetrics metrics = new ServiceMetrics(registry);
        TerminologyClients clients = new TerminologyClients(TxServerAllowlistSource.EMPTY, NetworkGuard.strict(), 1_000, 1);
        RegistryResolver resolver = new RegistryResolver("http://127.0.0.1:1/tx-reg", NetworkGuard.forTests(), 100,
                com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        TxRegistry txRegistry = TxRegistry.official(io.vertx.core.Vertx.vertx(), null, 3_600_000, 1_000);
        metrics.bindTerminology(txRegistry, clients, resolver);

        // A refused call (empty allowlist) and a failed lookup (nothing listens on port 1).
        clients.call("http://tx.fhir.org/r4", SupportedFhirVersion.R4, () -> "x", reason -> reason);
        clients.codeCacheHit(SupportedFhirVersion.R4);
        resolver.choose(SupportedFhirVersion.R4, "http://loinc.org", null, TxServerAllowlist.builtIn());

        String body = registry.scrape();
        assertTrue(body.contains("fv_tx_calls_total{fhir_version=\"R4\",outcome=\"refused\",server=\"tx.fhir.org\",} 1.0"), body);
        assertTrue(body.contains("fv_tx_routing_lookups_total{fhir_version=\"R4\",result=\"failed\",} 1.0"), body);
        assertTrue(body.contains("fv_tx_code_cache_hits_total{fhir_version=\"R4\",} 1.0"), body);
        assertTrue(body.contains("fv_tx_allowlist_age_seconds{fhir_version=\"R5\",source=\"built-in\",}"), body);
        assertFalse(body.contains("loinc"), "no code system in any label");
        txRegistry.stop();
    }
}
