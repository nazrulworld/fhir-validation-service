package nzi.fhir.validator.core.terminology;

import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;

/**
 * The process-wide terminology infrastructure every snapshot build shares (spec 007, plan): the allowlist, the
 * registry resolver, the server client pool, the per-snapshot code cache configuration and the per-request
 * terminology budget.
 *
 * @param requestBudgetMs the terminology time budget of one validation request ({@code FV_TX_REQUEST_BUDGET_MS})
 */
public record TerminologyServices(TxServerAllowlistSource allowlist, RegistryResolver resolver, TerminologyClients clients,
                                  ValidationSupportChain.CacheConfiguration chainCache, long requestBudgetMs) {
    /** No network at all: an empty allowlist, so any non-NONE setting is refused. HAPI's default code cache. */
    public static final TerminologyServices OFFLINE = new TerminologyServices(TxServerAllowlistSource.EMPTY,
            RegistryResolver.DISABLED, TerminologyClients.NONE, ValidationSupportChain.CacheConfiguration.defaultValues(),
            10_000);
}
