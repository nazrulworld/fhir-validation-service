package nzi.fhir.validator.core.terminology;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only access to terminology infrastructure that production never exposes (research R14): an allowlist naming
 * local stub servers and clients whose guard allows loopback. Lives in the production package to reach its
 * package-private constructors; it is not part of the production build.
 */
public final class TestTerminology {
    private TestTerminology() {
    }

    /** A mutable allowlist source, so tests can add or remove servers between calls. */
    public static final class MutableAllowlist implements TxServerAllowlistSource {
        private final AtomicReference<TxServerAllowlist> current;

        MutableAllowlist(TxServerAllowlist initial) {
            current = new AtomicReference<>(initial);
        }

        @Override
        public TxServerAllowlist current() {
            return current.get();
        }

        /** Replaces {@code version}'s registry list with exactly {@code urls}. */
        public MutableAllowlist allow(SupportedFhirVersion version, List<String> urls) {
            current.set(current.get().withVersion(version, TxServerAllowlist.VersionList.of(
                    urls.stream().map(u -> new TxServerEntry(TerminologySettings.normaliseUrl(u), version, "stub", List.of())).toList(),
                    TxServerAllowlist.Source.REGISTRY, Instant.now(), null)));
            return this;
        }

        /** Points the default server (tx.fhir.org) of {@code version} at {@code url}. */
        public MutableAllowlist defaultServer(SupportedFhirVersion version, String url) {
            current.set(current.get().withDefault(version, url));
            return this;
        }
    }

    public static MutableAllowlist allowlist(SupportedFhirVersion version, String... urls) {
        return new MutableAllowlist(TxServerAllowlist.builtIn()).allow(version, List.of(urls));
    }

    /** Clients whose guard allows loopback (local stubs). */
    public static TerminologyClients clients(TxServerAllowlistSource allowlist, long timeoutMs) {
        return new TerminologyClients(allowlist, NetworkGuard.forTests(), timeoutMs, 10);
    }

    public static TerminologyServices services(TxServerAllowlistSource allowlist, TerminologyClients clients,
                                               RegistryResolver resolver, long requestBudgetMs) {
        return new TerminologyServices(allowlist, resolver, clients, ValidationSupportChain.CacheConfiguration.defaultValues()
                .setCacheSize(5_000).setCacheTimeout(Duration.ofMinutes(10)), requestBudgetMs);
    }

    /** A resolver against a stub registry at {@code base}, with an injectable clock. */
    public static RegistryResolver resolver(String base, com.github.benmanes.caffeine.cache.Ticker ticker) {
        return new RegistryResolver(base, NetworkGuard.forTests(), 2_000, ticker);
    }
}
