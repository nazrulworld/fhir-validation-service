package nzi.fhir.validator.core.terminology;

import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;

import java.time.Duration;

/**
 * The process-wide terminology infrastructure every snapshot shares (spec 007, spec 010): the allowlist, the registry
 * resolver, the server client pool, HAPI's cache configuration for definitions, the one code-result cache of the
 * instance and the per-request terminology budget.
 *
 * @param chainCache      HAPI's cache of each snapshot's chain; since spec 010 it memoises definitions only (code
 *                        results live in {@code codeCache})
 * @param requestBudgetMs the terminology time budget of one validation request ({@code FV_TX_REQUEST_BUDGET_MS})
 * @param codeCache       the instance's code-result cache ({@code FV_TX_CACHE_SIZE}, {@code FV_TX_CACHE_TTL_MS})
 */
public record TerminologyServices(TxServerAllowlistSource allowlist, RegistryResolver resolver, TerminologyClients clients,
                                  ValidationSupportChain.CacheConfiguration chainCache, long requestBudgetMs,
                                  TerminologyCodeCache codeCache) {
    /** No network at all: an empty allowlist, so any non-NONE setting is refused. HAPI's default caches. */
    public static final TerminologyServices OFFLINE = new TerminologyServices(TxServerAllowlistSource.EMPTY,
            RegistryResolver.DISABLED, TerminologyClients.NONE, ValidationSupportChain.CacheConfiguration.defaultValues(),
            10_000, new TerminologyCodeCache(100, Duration.ofMinutes(10)));

    /** Infrastructure with a code cache of HAPI's default size (5000 entries, 10 minutes): tests. */
    public TerminologyServices(TxServerAllowlistSource allowlist, RegistryResolver resolver, TerminologyClients clients,
                               ValidationSupportChain.CacheConfiguration chainCache, long requestBudgetMs) {
        this(allowlist, resolver, clients, chainCache, requestBudgetMs, new TerminologyCodeCache(5_000, Duration.ofMinutes(10)));
    }
}
