package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.LookupCodeRequest;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.ValueSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec 007 FR-014, research R2: only the terminology operations reach the delegate; definitions never do. */
class TerminologyOnlySupportTest {

    @Test
    void forwardsOnlyTheTerminologyOperations() {
        IValidationSupport delegate = mock(IValidationSupport.class);
        when(delegate.getFhirContext()).thenReturn(FhirContext.forR4Cached());
        when(delegate.fetchCodeSystem(any())).thenReturn(new CodeSystem());
        when(delegate.fetchValueSet(any())).thenReturn(new ValueSet());
        when(delegate.fetchStructureDefinition(any())).thenReturn(new org.hl7.fhir.r4.model.StructureDefinition());
        when(delegate.isCodeSystemSupported(any(), any())).thenReturn(true);
        when(delegate.isValueSetSupported(any(), any())).thenReturn(true);
        IValidationSupport.CodeValidationResult ok = new IValidationSupport.CodeValidationResult().setCode("x");
        when(delegate.validateCode(any(), any(), any(), any(), any(), any())).thenReturn(ok);
        when(delegate.validateCodeInValueSet(any(), any(), any(), any(), any(), any())).thenReturn(ok);

        TerminologyOnlySupport only = new TerminologyOnlySupport(delegate);
        ValidationSupportContext vsc = new ValidationSupportContext(only);
        assertTrue(only.isCodeSystemSupported(vsc, "http://loinc.org"));
        assertTrue(only.isValueSetSupported(vsc, "http://x/vs"));
        assertSame(ok, only.validateCode(vsc, new ConceptValidationOptions(), "s", "c", null, null));
        assertSame(ok, only.validateCodeInValueSet(vsc, new ConceptValidationOptions(), "s", "c", null, new ValueSet()));
        only.lookupCode(vsc, new LookupCodeRequest("s", "c"));
        verify(delegate).lookupCode(any(), any(LookupCodeRequest.class));

        assertNull(only.fetchCodeSystem("http://loinc.org"));
        assertNull(only.fetchValueSet("http://x/vs"));
        assertNull(only.fetchStructureDefinition("http://x/sd"));
        assertNull(only.fetchAllConformanceResources());
        assertNull(only.translateConcept(null));
        // The interface default: the value set is looked up through the chain (here: nothing) and never expanded remotely.
        assertThrows(ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException.class,
                () -> only.expandValueSet(vsc, null, "http://x/vs"));
        verify(delegate, never()).expandValueSet(any(), any(), any(String.class));
        verify(delegate, never()).expandValueSet(any(), any(), any(org.hl7.fhir.instance.model.api.IBaseResource.class));
        verify(delegate, never()).fetchCodeSystem(any());
        verify(delegate, never()).fetchValueSet(any());
        verify(delegate, never()).fetchStructureDefinition(any());
        verify(delegate, never()).fetchAllConformanceResources();
    }
}
