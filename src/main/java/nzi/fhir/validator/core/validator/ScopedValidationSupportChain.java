package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.terminology.TerminologyBudget;
import nzi.fhir.validator.core.terminology.TerminologyCodeCache;
import nzi.fhir.validator.core.terminology.TerminologyScope;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * The chain of every snapshot (spec 010 research R3). It keeps HAPI's caching of definitions (FR-014), but answers
 * the three code operations from the instance's {@link TerminologyCodeCache}, keyed by snapshot and terminology scope
 * (FR-012): HAPI's own key has neither, so one cached chain shared by requests with different servers would serve
 * one server's answer for another.
 * <p>
 * On a cache miss the module loop runs uncached. It is a copy of HAPI 8.12.1's {@code ValidationSupportChain} loop
 * ({@code validateCode}, {@code validateCodeInValueSet}, {@code lookupCode}, including
 * {@code generateResultForUnknownCodeSystem}); {@code ScopedChainLoopTest} fails when a HAPI upgrade changes it.
 * The request module is skipped entirely in mode {@code none}, so such a request is answered exactly as by 1.0.0's
 * chain without a terminology module (also for {@code isCodeSystemSupported}/{@code isValueSetSupported}, which HAPI
 * would otherwise answer from its per-module cache).
 */
public class ScopedValidationSupportChain extends ValidationSupportChain {
    private final long snapshotId;
    private final SupportedFhirVersion version;
    private final TerminologyCodeCache codeCache;
    private final IValidationSupport requestModule;
    private final List<IValidationSupport> modules;
    private final nzi.fhir.validator.core.terminology.TerminologyClients clients;

    /**
     * @param cache         HAPI's cache configuration, for definitions
     * @param requestModule the module (as placed in {@code modules}) skipped in mode {@code none}; may be {@code null}
     */
    public ScopedValidationSupportChain(CacheConfiguration cache, List<IValidationSupport> modules, long snapshotId,
                                        SupportedFhirVersion version, TerminologyCodeCache codeCache,
                                        IValidationSupport requestModule) {
        this(cache, modules, snapshotId, version, codeCache, requestModule, null);
    }

    /**
     * @param clients told about code-cache hits of requests with a terminology server (the spec 007 metric
     *                {@code fv.tx.code.cache.hits}); may be {@code null}
     */
    public ScopedValidationSupportChain(CacheConfiguration cache, List<IValidationSupport> modules, long snapshotId,
                                        SupportedFhirVersion version, TerminologyCodeCache codeCache,
                                        IValidationSupport requestModule,
                                        nzi.fhir.validator.core.terminology.TerminologyClients clients) {
        super(cache, modules);
        this.clients = clients;
        this.snapshotId = snapshotId;
        this.version = version;
        this.codeCache = codeCache;
        this.requestModule = requestModule;
        this.modules = List.copyOf(modules);
    }

    public long snapshotId() {
        return snapshotId;
    }

    /** Whether {@code module} takes part in this request (the request module only outside mode {@code none}). */
    private boolean active(IValidationSupport module) {
        return module != requestModule || !TerminologyScope.current().offline();
    }

    @Override
    public boolean isCodeSystemSupported(ValidationSupportContext ctx, String system) {
        if (!TerminologyScope.current().offline() || requestModule == null) {
            return super.isCodeSystemSupported(ctx, system);
        }
        for (IValidationSupport next : modules) {
            if (active(next) && next.isCodeSystemSupported(ctx, system)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isValueSetSupported(ValidationSupportContext ctx, String valueSetUrl) {
        if (!TerminologyScope.current().offline() || requestModule == null) {
            return super.isValueSetSupported(ctx, valueSetUrl);
        }
        for (IValidationSupport next : modules) {
            if (active(next) && next.isValueSetSupported(ctx, valueSetUrl)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CodeValidationResult validateCode(ValidationSupportContext ctx, ConceptValidationOptions options, String system,
                                             String code, String display, String valueSetUrl) {
        TerminologyCodeCache.Key key = key(TerminologyCodeCache.Op.VALIDATE_CODE, system, code, display, valueSetUrl, options);
        TerminologyCodeCache.Hit<CodeValidationResult> hit = codeCache.get(key);
        if (hit != null) {
            countHit();
            return hit.value();
        }
        boolean degradedBefore = TerminologyBudget.degraded();
        CodeValidationResult result = loopValidateCode(ctx, options, system, code, display, valueSetUrl);
        store(key, result, degradedBefore);
        return result;
    }

    @Override
    public CodeValidationResult validateCodeInValueSet(ValidationSupportContext ctx, ConceptValidationOptions options,
                                                       String system, String code, String display, IBaseResource valueSet) {
        String url = CommonCodeSystemsTerminologyService.getValueSetUrl(getFhirContext(), valueSet);
        TerminologyCodeCache.Key key = isNotBlank(url)
                ? key(TerminologyCodeCache.Op.VALIDATE_CODE_IN_VS, system, code, display, url, options) : null;
        if (key != null) {
            TerminologyCodeCache.Hit<CodeValidationResult> hit = codeCache.get(key);
            if (hit != null) {
                countHit();
                return hit.value();
            }
        }
        boolean degradedBefore = TerminologyBudget.degraded();
        CodeValidationResult result = loopValidateCodeInValueSet(ctx, options, system, code, display, valueSet, url);
        if (key != null) {
            store(key, result, degradedBefore);
        }
        return result;
    }

    @Override
    public LookupCodeResult lookupCode(ValidationSupportContext ctx, LookupCodeRequest request) {
        TerminologyCodeCache.Key key = key(TerminologyCodeCache.Op.LOOKUP_CODE, request.getSystem(), request.getCode(),
                request.getDisplayLanguage(), null, null);
        TerminologyCodeCache.Hit<LookupCodeResult> hit = request.getPropertyNames() == null || request.getPropertyNames().isEmpty()
                ? codeCache.get(key) : null;
        if (hit != null) {
            countHit();
            return hit.value();
        }
        boolean degradedBefore = TerminologyBudget.degraded();
        LookupCodeResult result = loopLookupCode(ctx, request);
        if (request.getPropertyNames() == null || request.getPropertyNames().isEmpty()) {
            store(key, result, degradedBefore);
        }
        return result;
    }

    /** Spec 007 FR-026: a code check of a request with a terminology server answered from the cache. */
    private void countHit() {
        if (clients != null && !TerminologyScope.current().offline()) {
            clients.codeCacheHit(version);
        }
    }

    /**
     * Stores {@code result} unless the call degraded the request (spec 010 FR-012): that also covers a lookup that
     * answered {@code null} because a server was unavailable, which carries no "not checked" marker.
     */
    private void store(TerminologyCodeCache.Key key, Object result, boolean degradedBefore) {
        if (!degradedBefore && TerminologyBudget.degraded()) {
            return;
        }
        codeCache.put(key, result);
    }

    private TerminologyCodeCache.Key key(TerminologyCodeCache.Op op, String system, String code, String display,
                                         String valueSetKey, ConceptValidationOptions options) {
        String optionsKey = options == null ? "" : (options.isInferSystem() ? "i" : "-") + (options.isValidateDisplay() ? "d" : "-");
        return new TerminologyCodeCache.Key(snapshotId, TerminologyScope.current().scopeId(), op, version, system, code,
                display, valueSetKey, optionsKey);
    }

    // ---- HAPI 8.12.1 module loops, uncached (ValidationSupportChain lines 811-1000). Keep in step with ScopedChainLoopTest.

    private CodeValidationResult loopValidateCode(ValidationSupportContext ctx, ConceptValidationOptions options,
                                                  String system, String code, String display, String valueSetUrl) {
        for (IValidationSupport next : modules) {
            if (!active(next)) {
                continue;
            }
            if ((isBlank(valueSetUrl) && next.isCodeSystemSupported(ctx, system))
                    || (isNotBlank(valueSetUrl) && next.isValueSetSupported(ctx, valueSetUrl))) {
                CodeValidationResult outcome = next.validateCode(ctx, options, system, code, display, valueSetUrl);
                if (outcome != null) {
                    return outcome;
                }
            }
        }
        return resultForUnknownCodeSystem(system, code);
    }

    private CodeValidationResult loopValidateCodeInValueSet(ValidationSupportContext ctx, ConceptValidationOptions options,
                                                            String system, String code, String display,
                                                            IBaseResource valueSet, String url) {
        for (IValidationSupport next : modules) {
            if (!active(next)) {
                continue;
            }
            if (isBlank(url) || next.isValueSetSupported(ctx, url)) {
                CodeValidationResult outcome = next.validateCodeInValueSet(ctx, options, system, code, display, valueSet);
                if (outcome != null) {
                    return outcome;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private LookupCodeResult loopLookupCode(ValidationSupportContext ctx, LookupCodeRequest request) {
        String system = request.getSystem();
        for (IValidationSupport next : modules) {
            if (!active(next)) {
                continue;
            }
            if (next.isCodeSystemSupported(ctx, system)) {
                LookupCodeResult result = next.lookupCode(ctx, request);
                if (result == null) {
                    result = next.lookupCode(ctx, system, request.getCode(), request.getDisplayLanguage());
                }
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /** HAPI 8.12.1 {@code generateResultForUnknownCodeSystem}. */
    private CodeValidationResult resultForUnknownCodeSystem(String system, String code) {
        if (system == null) {
            return null;
        }
        if (fetchCodeSystem(system) != null) {
            return null;
        }
        CodeValidationResult result = new CodeValidationResult();
        result.setSeverity(IssueSeverity.ERROR);
        String message = "CodeSystem is unknown and can't be validated: %s for '%s#%s'".formatted(system, system, code);
        result.setMessage(message);
        result.addIssue(new CodeValidationIssue(message, IssueSeverity.ERROR, CodeValidationIssueCode.NOT_FOUND,
                CodeValidationIssueCoding.NOT_FOUND));
        return result;
    }
}
