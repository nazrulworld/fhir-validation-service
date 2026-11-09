package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.ParametersUtil;
import org.hl7.fhir.common.hapi.validation.support.RemoteTerminologyServiceValidationSupport;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * HAPI's remote terminology client for one server, extended to send the IG's own code systems (spec 007 FR-014,
 * clarification Q2, research R3). HAPI already sends the IG's ValueSet as the {@code valueSet} parameter when the chain
 * passes it; this adds one {@code tx-resource} parameter per CodeSystem the value set includes that a package of the
 * context defines, so the server can decide membership for IG-local systems mixed with LOINC or SNOMED CT.
 * <p>
 * One instance per (server, FHIR version) is shared by every snapshot, so the snapshot's package lookup is passed per
 * call through {@link #withPackageCodeSystems}, on the calling (validation worker) thread.
 */
public class IgAwareRemoteTerminologySupport extends RemoteTerminologyServiceValidationSupport {
    private static final ThreadLocal<Function<String, IBaseResource>> PACKAGE_CODE_SYSTEMS = new ThreadLocal<>();

    public IgAwareRemoteTerminologySupport(FhirContext ctx, String baseUrl, IRestfulClientFactory clientFactory) {
        super(ctx, baseUrl, clientFactory);
    }

    /**
     * Runs {@code call} with {@code lookup} as the source of package-defined CodeSystems (by canonical URL; returns
     * {@code null} for systems no package defines).
     */
    public static <T> T withPackageCodeSystems(Function<String, IBaseResource> lookup, java.util.function.Supplier<T> call) {
        Function<String, IBaseResource> previous = PACKAGE_CODE_SYSTEMS.get();
        PACKAGE_CODE_SYSTEMS.set(lookup);
        try {
            return call.get();
        } finally {
            if (previous == null) {
                PACKAGE_CODE_SYSTEMS.remove();
            } else {
                PACKAGE_CODE_SYSTEMS.set(previous);
            }
        }
    }

    /**
     * Checks a code against a value set the chain passes as a resource: one the context's packages (or the core
     * specification) define. HAPI would send only its URL, so the server would use its own copy, or answer "value set
     * not found" for an IG value set; this sends the definition itself as the {@code valueSet} parameter (clarification
     * Q2, research R3), with the IG's CodeSystems as {@code tx-resource}. A value set no package defines reaches the
     * server by URL only, through {@code validateCode(..., valueSetUrl)}.
     */
    @Override
    public CodeValidationResult validateCodeInValueSet(ca.uhn.fhir.context.support.ValidationSupportContext vsc,
                                                       ca.uhn.fhir.context.support.ConceptValidationOptions options,
                                                       String system, String code, String display, IBaseResource valueSet) {
        if (valueSet == null) {
            return super.validateCodeInValueSet(vsc, options, system, code, display, valueSet);
        }
        String codeSystem = system;
        if (org.apache.commons.lang3.StringUtils.isNotBlank(code) && org.apache.commons.lang3.StringUtils.isBlank(codeSystem)) {
            codeSystem = org.hl7.fhir.common.hapi.validation.support.ValidationSupportUtils.extractCodeSystemForCode(valueSet, code);
        }
        return invokeRemoteValidateCode(codeSystem, code, display, null, valueSet);
    }

    @Override
    protected IBaseParameters buildValidateCodeInputParameters(String codeSystem, String code, String display,
                                                               String valueSetUrl, IBaseResource valueSet) {
        IBaseParameters params = super.buildValidateCodeInputParameters(codeSystem, code, display, valueSetUrl, valueSet);
        Function<String, IBaseResource> lookup = PACKAGE_CODE_SYSTEMS.get();
        if (valueSet == null || lookup == null) {
            return params;
        }
        for (String system : includedSystems(getFhirContext(), valueSet)) {
            IBaseResource cs = lookup.apply(system);
            if (cs != null) {
                ParametersUtil.addParameterToParameters(getFhirContext(), params, "tx-resource", cs);
            }
        }
        return params;
    }

    /** The {@code compose.include.system} values of a ValueSet, in order, without duplicates. */
    static Set<String> includedSystems(FhirContext ctx, IBaseResource valueSet) {
        FhirTerser terser = ctx.newTerser();
        Set<String> systems = new LinkedHashSet<>();
        List<IBase> includes = terser.getValues(valueSet, "ValueSet.compose.include");
        for (IBase include : includes) {
            String system = terser.getSinglePrimitiveValueOrNull(include, "system");
            if (system != null) {
                systems.add(system);
            }
        }
        return systems;
    }
}
