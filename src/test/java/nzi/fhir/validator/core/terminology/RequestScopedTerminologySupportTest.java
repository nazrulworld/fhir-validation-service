package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Spec 010 research R1, R2: the one module of a snapshot routes with the request's scope. */
class RequestScopedTerminologySupportTest {
    private static final FhirContext CTX = FhirContext.forR4Cached();
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;
    private static final String LOINC = "http://loinc.org";
    private static final String A = "https://a.example.org/fhir";

    /** Records every call and answers it as unavailable, so nothing reaches the network. */
    static final class CountingClients extends TerminologyClients {
        final List<String> servers = new ArrayList<>();

        CountingClients() {
            super(TxServerAllowlistSource.EMPTY, NetworkGuard.strict(), 1_000, 1);
        }

        @Override
        public <T> T call(String url, SupportedFhirVersion version, Supplier<T> work, Function<String, T> degraded) {
            servers.add(url);
            return degraded.apply(Reasons.UNAVAILABLE);
        }
    }

    @AfterEach
    void close() {
        TerminologyScope.close();
    }

    private static ValidationSupportContext context(IValidationSupport module) {
        return new ValidationSupportContext(new ValidationSupportChain(new DefaultProfileValidationSupport(CTX), module));
    }

    private static IValidationSupport.CodeValidationResult check(RequestScopedTerminologySupport module) {
        return module.validateCode(context(module), new ConceptValidationOptions(), LOINC, "1234-5", null, null);
    }

    @Test
    void noneScopeDeclinesEveryCallWithoutContactingAServer() {
        CountingClients clients = new CountingClients();
        RequestScopedTerminologySupport module = new RequestScopedTerminologySupport(CTX, R4, clients);
        TerminologyScope.open(EffectiveTerminology.resolve(new TerminologySettings(TerminologyMode.SERVER, A),
                TerminologyOverride.ABSENT), TerminologyRouter.server(A));
        check(module);
        TerminologyScope.close();
        TerminologyScope.openNone();
        ValidationSupportContext vsc = context(module);
        assertNull(module.validateCode(vsc, new ConceptValidationOptions(), LOINC, "1234-5", null, null));
        assertNull(module.validateCodeInValueSet(vsc, new ConceptValidationOptions(), LOINC, "1234-5", null,
                new org.hl7.fhir.r4.model.ValueSet()));
        assertNull(module.lookupCode(vsc, new LookupCodeRequest(LOINC, "1234-5")));
        assertEquals(List.of(A), clients.servers, "only the server-scope call reached a client");
    }

    @Test
    void noScopeOpenBehavesAsNone() {
        CountingClients clients = new CountingClients();
        RequestScopedTerminologySupport module = new RequestScopedTerminologySupport(CTX, R4, clients);
        assertNull(check(module));
        assertTrue(clients.servers.isEmpty());
    }

    @Test
    void serverScopeRoutesToItsServer() {
        CountingClients clients = new CountingClients();
        RequestScopedTerminologySupport module = new RequestScopedTerminologySupport(CTX, R4, clients);
        TerminologyScope.open(EffectiveTerminology.resolve(TerminologySettings.NONE, new TerminologyOverride(null, A)),
                TerminologyRouter.server(A));
        IValidationSupport.CodeValidationResult r = check(module);
        assertEquals(List.of(A), clients.servers);
        assertEquals(IValidationSupport.IssueSeverity.WARNING, r.getSeverity(), "an unavailable server is a warning");
    }

    @Test
    void ecosystemScopeAsksTheResolverWithItsDefaultServer() {
        CountingClients clients = new CountingClients();
        RegistryResolver resolver = mock(RegistryResolver.class);
        when(resolver.choose(eq(R4), eq(LOINC), eq(A), any())).thenReturn(Optional.of("https://loinc.example.org/fhir"));
        RequestScopedTerminologySupport module = new RequestScopedTerminologySupport(CTX, R4, clients);
        EffectiveTerminology eco = EffectiveTerminology.resolve(TerminologySettings.NONE,
                new TerminologyOverride(TerminologyMode.ECOSYSTEM, A));
        TerminologyScope.open(eco, TerminologyRouter.of(eco, R4, resolver, TxServerAllowlistSource.EMPTY));
        check(module);
        verify(resolver).choose(eq(R4), eq(LOINC), eq(A), any());
        assertEquals(List.of("https://loinc.example.org/fhir"), clients.servers);
    }

    @Test
    void deniedScopeAnswersNotCheckedWithoutACall() {
        CountingClients clients = new CountingClients();
        RequestScopedTerminologySupport module = new RequestScopedTerminologySupport(CTX, R4, clients);
        EffectiveTerminology denied = EffectiveTerminology.resolve(new TerminologySettings(TerminologyMode.SERVER, A),
                TerminologyOverride.ABSENT).asDenied();
        TerminologyScope.open(denied, TerminologyRouter.of(denied, R4, RegistryResolver.DISABLED, TxServerAllowlistSource.EMPTY));
        IValidationSupport.CodeValidationResult r = check(module);
        assertTrue(clients.servers.isEmpty());
        assertEquals(TerminologyClients.Reasons.text(TerminologyClients.Reasons.NOT_PERMITTED, LOINC, "1234-5"), r.getMessage());
    }

    @Test
    void scopesDoNotNest() {
        TerminologyScope.openNone();
        assertThrows(IllegalStateException.class, TerminologyScope::openNone);
    }

    @Test
    void supportChecksDoNotDependOnTheScope() {
        RequestScopedTerminologySupport module = new RequestScopedTerminologySupport(CTX, R4, new CountingClients());
        ValidationSupportContext vsc = context(module);
        TerminologyScope.openNone();
        boolean offline = module.isCodeSystemSupported(vsc, LOINC);
        TerminologyScope.close();
        TerminologyScope.open(EffectiveTerminology.resolve(TerminologySettings.NONE, new TerminologyOverride(null, A)),
                TerminologyRouter.server(A));
        assertEquals(offline, module.isCodeSystemSupported(vsc, LOINC), "HAPI caches this per module");
        assertTrue(offline);
    }
}
