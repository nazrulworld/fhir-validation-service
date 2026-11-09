package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.Optional;
import java.util.Set;

/**
 * The terminology module of a context in mode {@code server} or {@code ecosystem}, or one that may not contact any
 * server (spec 007 research R2, R4). It answers only codes that can't be decided locally: a code system a package of
 * the context defines with {@code content = complete}, or one {@link CommonCodeSystemsTerminologyService} supports, is
 * <em>declined</em> (null/false), so it is always checked in-process (FR-013), whatever the module order. Everything
 * else goes to the server {@link #route} chooses, through {@link TerminologyClients#call}; with no server it is
 * answered "not checked" (a warning).
 */
public abstract class RoutedTerminologySupport implements IValidationSupport {
    protected final FhirContext ctx;
    protected final SupportedFhirVersion version;
    protected final TerminologyClients clients;
    private final CommonCodeSystemsTerminologyService common;

    protected RoutedTerminologySupport(FhirContext ctx, SupportedFhirVersion version, TerminologyClients clients) {
        this.ctx = ctx;
        this.version = version;
        this.clients = clients;
        this.common = new CommonCodeSystemsTerminologyService(ctx);
    }

    /**
     * The server for codes of {@code system} (may be null for a value set check without a system), or empty when none
     * may be contacted.
     */
    protected abstract Optional<String> route(String system);

    @Override
    public FhirContext getFhirContext() {
        return ctx;
    }

    /** Whether codes of {@code system} are decided in-process (FR-013). */
    protected boolean decidedLocally(ValidationSupportContext vsc, String system) {
        if (system == null) {
            return false;
        }
        if (common.isCodeSystemSupported(vsc, system)) {
            return true;
        }
        IBaseResource cs = packageCodeSystem(vsc, system);
        return cs != null && "complete".equals(ctx.newTerser().getSinglePrimitiveValueOrNull(cs, "content"));
    }

    /** A CodeSystem a package of the context defines (never fetched from a server: modules don't supply definitions). */
    private static IBaseResource packageCodeSystem(ValidationSupportContext vsc, String system) {
        try {
            return vsc.getRootValidationSupport().fetchCodeSystem(system);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first included system of {@code valueSet} that isn't decided locally, if any. */
    private Optional<String> firstRemoteSystem(ValidationSupportContext vsc, IBaseResource valueSet) {
        if (valueSet == null) {
            return Optional.empty();
        }
        Set<String> systems = IgAwareRemoteTerminologySupport.includedSystems(ctx, valueSet);
        return systems.stream().filter(s -> !decidedLocally(vsc, s)).findFirst();
    }

    @Override
    public boolean isCodeSystemSupported(ValidationSupportContext vsc, String system) {
        return system != null && !decidedLocally(vsc, system);
    }

    @Override
    public boolean isValueSetSupported(ValidationSupportContext vsc, String valueSetUrl) {
        if (valueSetUrl == null) {
            return false;
        }
        IBaseResource vs = vsc.getRootValidationSupport().fetchValueSet(valueSetUrl);
        // A value set no package defines is answered from the server's copy; a package one only when part of it is
        // remote (only dispatch: nothing is fetched).
        return vs == null || firstRemoteSystem(vsc, vs).isPresent();
    }

    @Override
    public CodeValidationResult validateCode(ValidationSupportContext vsc, ConceptValidationOptions options, String system,
                                             String code, String display, String valueSetUrl) {
        if (system == null || decidedLocally(vsc, system)) {
            return null;
        }
        Optional<String> server = route(system);
        if (server.isEmpty()) {
            TerminologyBudget.markDegraded();
            return TerminologyClients.notChecked(TerminologyClients.Reasons.NOT_PERMITTED, system, code);
        }
        return clients.call(server.get(), version,
                () -> withPackages(vsc, () -> clients.client(ctx, version, server.get())
                        .validateCode(vsc, options, system, code, display, valueSetUrl)),
                reason -> TerminologyClients.notChecked(reason, system, code));
    }

    @Override
    public CodeValidationResult validateCodeInValueSet(ValidationSupportContext vsc, ConceptValidationOptions options,
                                                       String system, String code, String display, IBaseResource valueSet) {
        String routing;
        if (system != null) {
            if (decidedLocally(vsc, system)) {
                return null;
            }
            routing = system;
        } else {
            Optional<String> remote = firstRemoteSystem(vsc, valueSet);
            if (remote.isEmpty()) {
                return null;
            }
            routing = remote.get();
        }
        Optional<String> server = route(routing);
        if (server.isEmpty()) {
            TerminologyBudget.markDegraded();
            return TerminologyClients.notChecked(TerminologyClients.Reasons.NOT_PERMITTED, routing, code);
        }
        return clients.call(server.get(), version,
                () -> withPackages(vsc, () -> clients.client(ctx, version, server.get())
                        .validateCodeInValueSet(vsc, options, system, code, display, valueSet)),
                reason -> TerminologyClients.notChecked(reason, routing, code));
    }

    @Override
    public LookupCodeResult lookupCode(ValidationSupportContext vsc, LookupCodeRequest request) {
        String system = request.getSystem();
        if (system == null || decidedLocally(vsc, system)) {
            return null;
        }
        Optional<String> server = route(system);
        if (server.isEmpty()) {
            TerminologyBudget.markDegraded();
            return null;
        }
        return clients.call(server.get(), version,
                () -> clients.client(ctx, version, server.get()).lookupCode(vsc, request),
                reason -> null);
    }

    private static <T> T withPackages(ValidationSupportContext vsc, java.util.function.Supplier<T> call) {
        return IgAwareRemoteTerminologySupport.withPackageCodeSystems(system -> {
            IBaseResource cs = packageCodeSystem(vsc, system);
            // Only definitions a package of the context carries; core definitions are known to every server.
            return cs;
        }, call);
    }
}
