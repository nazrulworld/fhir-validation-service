package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.terminology.TerminologyBudget;
import nzi.fhir.validator.core.terminology.TerminologyCodeCache;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 010 research R3: {@link ScopedValidationSupportChain} copies HAPI 8.12.1's code-check loop (module order, the
 * support checks, the deprecated lookup fallback and {@code generateResultForUnknownCodeSystem}). Its uncached answers
 * must equal those of a plain HAPI chain with caching disabled. When a HAPI upgrade changes that loop this test fails:
 * then re-copy the loop from the new {@code ValidationSupportChain} and update the version in its javadoc.
 */
class ScopedChainLoopTest {
    private static final FhirContext CTX = FhirContext.forR4Cached();
    private static final String LOCAL_CS = "http://example.org/fhir/CodeSystem/local";
    private static final String LOCAL_VS = "http://example.org/fhir/ValueSet/local";
    private static final String STUB_CS = "http://example.org/fhir/CodeSystem/stub";

    @AfterEach
    void closeBudget() {
        TerminologyBudget.close();
    }

    /** Answers {@link #STUB_CS} only: "known" is valid, anything else an error; lookups find "known". */
    static final class StubModule implements IValidationSupport {
        final AtomicInteger lookups = new AtomicInteger();
        boolean degradeLookups;

        @Override
        public FhirContext getFhirContext() {
            return CTX;
        }

        @Override
        public boolean isCodeSystemSupported(ValidationSupportContext ctx, String system) {
            return STUB_CS.equals(system);
        }

        @Override
        public CodeValidationResult validateCode(ValidationSupportContext ctx, ConceptValidationOptions options, String system,
                                                 String code, String display, String valueSetUrl) {
            if (!STUB_CS.equals(system)) {
                return null;
            }
            return "known".equals(code) ? new CodeValidationResult().setCode(code).setDisplay("Known")
                    : new CodeValidationResult().setSeverity(IssueSeverity.ERROR).setMessage("stub: unknown code " + code);
        }

        @Override
        public LookupCodeResult lookupCode(ValidationSupportContext ctx, LookupCodeRequest request) {
            lookups.incrementAndGet();
            if (degradeLookups) {
                TerminologyBudget.markDegraded();
                return null;
            }
            return "known".equals(request.getCode())
                    ? new LookupCodeResult().setFound(true).setSearchedForCode("known").setSearchedForSystem(STUB_CS) : null;
        }

        @Override
        public String getName() {
            return "Stub";
        }
    }

    private static List<IValidationSupport> modules(StubModule stub) {
        PrePopulatedValidationSupport local = new PrePopulatedValidationSupport(CTX);
        CodeSystem cs = new CodeSystem();
        cs.setUrl(LOCAL_CS);
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setContent(CodeSystem.CodeSystemContentMode.COMPLETE);
        cs.addConcept().setCode("a").setDisplay("Alpha");
        cs.addConcept().setCode("b").setDisplay("Beta");
        local.addCodeSystem(cs);
        ValueSet vs = new ValueSet();
        vs.setUrl(LOCAL_VS);
        vs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        vs.getCompose().addInclude().setSystem(LOCAL_CS).addConcept().setCode("a");
        local.addValueSet(vs);
        List<IValidationSupport> modules = new ArrayList<>();
        modules.add(new DefaultProfileValidationSupport(CTX));
        modules.add(local);
        modules.add(stub);
        modules.add(new InMemoryTerminologyServerValidationSupport(CTX));
        modules.add(new CommonCodeSystemsTerminologyService(CTX));
        return modules;
    }

    private static ScopedValidationSupportChain scoped(List<IValidationSupport> modules, TerminologyCodeCache cache) {
        return new ScopedValidationSupportChain(ValidationSupportChain.CacheConfiguration.defaultValues(), modules, 1,
                SupportedFhirVersion.R4, cache, null);
    }

    private static void assertSameResult(IValidationSupport.CodeValidationResult expected,
                                         IValidationSupport.CodeValidationResult actual, String label) {
        if (expected == null || actual == null) {
            assertEquals(expected == null, actual == null, label + ": null-ness");
            return;
        }
        assertEquals(expected.getCode(), actual.getCode(), label + ": code");
        assertEquals(expected.getDisplay(), actual.getDisplay(), label + ": display");
        assertEquals(expected.getSeverity(), actual.getSeverity(), label + ": severity");
        assertEquals(expected.getMessage(), actual.getMessage(), label + ": message");
        assertEquals(expected.getIssues().size(), actual.getIssues().size(), label + ": issues");
    }

    @Test
    void uncachedLoopAnswersLikeHapi() {
        StubModule stub = new StubModule();
        List<IValidationSupport> modules = modules(stub);
        ValidationSupportChain hapi = new ValidationSupportChain(ValidationSupportChain.CacheConfiguration.disabled(), modules);
        ScopedValidationSupportChain ours = scoped(modules, new TerminologyCodeCache(1000, Duration.ofMinutes(10)));
        ValidationSupportContext hctx = new ValidationSupportContext(hapi);
        ValidationSupportContext octx = new ValidationSupportContext(ours);
        ConceptValidationOptions options = new ConceptValidationOptions();
        IBaseResource vs = hapi.fetchValueSet(LOCAL_VS);
        ValueSet noUrl = ((ValueSet) vs).copy();
        noUrl.setUrl(null);
        String[][] codes = {{LOCAL_CS, "a"}, {LOCAL_CS, "zz"}, {STUB_CS, "known"}, {STUB_CS, "nope"},
                {"http://unknown.example.org/cs", "x"}, {"urn:ietf:bcp:13", "text/plain"}, {null, "a"}};
        for (int round = 0; round < 2; round++) { // the second round answers from our cache
            for (String[] sc : codes) {
                String label = sc[0] + "#" + sc[1] + " round " + round;
                assertSameResult(hapi.validateCode(hctx, options, sc[0], sc[1], null, null),
                        ours.validateCode(octx, options, sc[0], sc[1], null, null), "validateCode " + label);
                assertSameResult(hapi.validateCode(hctx, options, sc[0], sc[1], null, LOCAL_VS),
                        ours.validateCode(octx, options, sc[0], sc[1], null, LOCAL_VS), "validateCode+vs " + label);
                assertSameResult(hapi.validateCodeInValueSet(hctx, options, sc[0], sc[1], null, vs),
                        ours.validateCodeInValueSet(octx, options, sc[0], sc[1], null, vs), "inValueSet " + label);
                assertSameResult(hapi.validateCodeInValueSet(hctx, options, sc[0], sc[1], null, noUrl),
                        ours.validateCodeInValueSet(octx, options, sc[0], sc[1], null, noUrl), "inValueSet no url " + label);
                if (sc[0] != null) {
                    IValidationSupport.LookupCodeResult h = hapi.lookupCode(hctx, new LookupCodeRequest(sc[0], sc[1]));
                    IValidationSupport.LookupCodeResult o = ours.lookupCode(octx, new LookupCodeRequest(sc[0], sc[1]));
                    assertEquals(h == null, o == null, "lookup " + label);
                    if (h != null) {
                        assertEquals(h.isFound(), o.isFound(), "lookup found " + label);
                    }
                }
            }
        }
    }

    @Test
    void aCallThatDegradedTheRequestIsNotCached() {
        StubModule stub = new StubModule();
        stub.degradeLookups = true;
        ScopedValidationSupportChain ours = scoped(modules(stub), new TerminologyCodeCache(1000, Duration.ofMinutes(10)));
        ValidationSupportContext ctx = new ValidationSupportContext(ours);
        TerminologyBudget.open(10_000);
        assertNull(ours.lookupCode(ctx, new LookupCodeRequest(STUB_CS, "known")));
        TerminologyBudget.close();
        TerminologyBudget.open(10_000);
        ours.lookupCode(ctx, new LookupCodeRequest(STUB_CS, "known"));
        assertEquals(2, stub.lookups.get(), "a lookup that degraded the request stored nothing (FR-012)");
        stub.degradeLookups = false;
        TerminologyBudget.close();
        TerminologyBudget.open(10_000);
        ours.lookupCode(ctx, new LookupCodeRequest(STUB_CS, "known"));
        ours.lookupCode(ctx, new LookupCodeRequest(STUB_CS, "known"));
        assertEquals(3, stub.lookups.get(), "a healthy answer is cached");
    }
}
