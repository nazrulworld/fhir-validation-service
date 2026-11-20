package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.IValidationContext;
import ca.uhn.fhir.validation.IValidatorModule;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.FhirContextLoader;
import nzi.fhir.validator.core.terminology.TerminologyScope;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 013 US1 (FR-002, FR-003, edge cases "Terminology" and "bundle entry"): a snapshot re-checks a validation whose
 * result reports a regex timeout and answers with one attempt's complete result.
 */
class ValidatorSnapshotRecheckTest {

    /** Adds, on call {@code n} (1-based), the messages {@code messagesForCall} returns; records the terminology scope. */
    static final class ScriptedModule implements IValidatorModule {
        final IntFunction<List<SingleValidationMessage>> messagesForCall;
        final List<Object> scopes = new ArrayList<>();
        int calls;

        ScriptedModule(IntFunction<List<SingleValidationMessage>> messagesForCall) {
            this.messagesForCall = messagesForCall;
        }

        @Override
        public void validateResource(IValidationContext<IBaseResource> ctx) {
            calls++;
            scopes.add(TerminologyScope.isOpen() ? TerminologyScope.current() : null);
            messagesForCall.apply(calls).forEach(ctx::addValidationMessage);
        }
    }

    static SingleValidationMessage timeout() {
        return RegexTimeoutIssueTest.message("REGEX_MATCH_TIMED_OUT", "The regex '\\S*' could not be executed", ResultSeverityEnum.ERROR);
    }

    static SingleValidationMessage issue(String text) {
        return RegexTimeoutIssueTest.message("test-issue", text, ResultSeverityEnum.ERROR);
    }

    /** A module that reports a regex timeout on its first {@code n} calls and nothing after. */
    static ScriptedModule timeoutsOnFirst(int n) {
        return new ScriptedModule(call -> call <= n ? List.of(timeout()) : List.of());
    }

    static ValidatorSnapshot snapshotWith(IValidatorModule module, RegexRecheck recheck) {
        FhirContext ctx = FhirContextLoader.getInstance().getContext(SupportedFhirVersion.R4);
        FhirValidator validator = ctx.newValidator();
        validator.registerValidatorModule(module);
        ValidationSupportChain chain = new ValidationSupportChain(new ca.uhn.fhir.context.support.DefaultProfileValidationSupport(ctx));
        ValidatorSnapshot snapshot = new ValidatorSnapshot(SupportedFhirVersion.R4, 1, List.of(), ctx, validator, chain);
        snapshot.setRegexRecheck(recheck);
        return snapshot;
    }

    private static final String PATIENT = new JsonObject().put("resourceType", "Patient").put("active", true).encode();

    private RegexRecheck recheck;

    @BeforeEach
    void setUp() {
        RegexRecheck.resetSharedStateForTests();
        recheck = new RegexRecheck(30_000, RegexRecheck.Listener.NONE);
    }

    private static long timeouts(ValidatorSnapshot.Outcome outcome) {
        return outcome.result().getMessages().stream().filter(RegexTimeoutIssue::is).count();
    }

    @Test
    void oneLoadInducedTimeoutIsGoneAfterThePlainRecheck() {
        ScriptedModule module = timeoutsOnFirst(1);
        ValidatorSnapshot.Outcome outcome = snapshotWith(module, recheck).validate(PATIENT, SupportedContentType.JSON, List.of());
        assertEquals(2, module.calls);
        assertEquals(0, timeouts(outcome));
        assertTrue(outcome.result().isSuccessful());
    }

    @Test
    void twoTimeoutsAreGoneAfterTheQuietRecheck() {
        ScriptedModule module = timeoutsOnFirst(2);
        ValidatorSnapshot.Outcome outcome = snapshotWith(module, recheck).validate(PATIENT, SupportedContentType.JSON, List.of());
        assertEquals(3, module.calls);
        assertTrue(outcome.result().isSuccessful());
    }

    @Test
    void aPersistentTimeoutIsReportedAsAnError() {
        ScriptedModule module = timeoutsOnFirst(3);
        ValidatorSnapshot.Outcome outcome = snapshotWith(module, recheck).validate(PATIENT, SupportedContentType.JSON, List.of());
        assertEquals(3, module.calls);
        assertEquals(1, timeouts(outcome));
        assertFalse(outcome.result().isSuccessful(), "the timeout stays an error (FR-005)");
    }

    @Test
    void everyAttemptRunsInTheRequestsOneTerminologyScope() {
        ScriptedModule module = timeoutsOnFirst(2);
        snapshotWith(module, recheck).validate(PATIENT, SupportedContentType.JSON, List.of());
        assertEquals(3, module.scopes.size());
        assertNotNull(module.scopes.get(0));
        assertSame(module.scopes.get(0), module.scopes.get(1));
        assertSame(module.scopes.get(0), module.scopes.get(2), "one scope and one budget for all attempts");
    }

    @Test
    void aBundleIsValidatedAgainWholeAndIssuesNeverMix() {
        JsonArray entries = new JsonArray();
        for (int i = 0; i < 3; i++) {
            entries.add(new JsonObject().put("fullUrl", "urn:uuid:0000000" + i + "-0000-0000-0000-000000000000")
                    .put("resource", new JsonObject().put("resourceType", "Patient").put("active", true)));
        }
        String bundle = new JsonObject().put("resourceType", "Bundle").put("type", "collection").put("entry", entries).encode();
        ScriptedModule module = new ScriptedModule(call -> call == 1
                ? List.of(issue("attempt 1, entry 0"), timeout(), issue("attempt 1, entry 2"))
                : List.of(issue("attempt " + call + ", entry 0"), issue("attempt " + call + ", entry 2")));
        ValidatorSnapshot.Outcome outcome = snapshotWith(module, recheck).validate(bundle, SupportedContentType.JSON, List.of());
        assertEquals(2, module.calls, "the whole request, once more");
        assertEquals(List.of("attempt 2, entry 0", "attempt 2, entry 2"),
                outcome.result().getMessages().stream().map(SingleValidationMessage::getMessage).toList());
    }

    /** Spec 013 T021: a resolved re-check is counted through the instance's own metrics. */
    @Test
    void aResolvedRecheckIsCountedThroughTheInstancesMetrics() {
        nzi.fhir.validator.core.metrics.ServiceMetrics metrics = new nzi.fhir.validator.core.metrics.ServiceMetrics(
                nzi.fhir.validator.core.metrics.ServiceMetrics.newRegistry());
        RegexRecheck counted = new RegexRecheck(30_000, metrics.regexRecheckListener());
        snapshotWith(timeoutsOnFirst(1), counted).validate(PATIENT, SupportedContentType.JSON, List.of());
        String scrape = metrics.scrape();
        assertTrue(scrape.lines().anyMatch(l -> l.startsWith("fv_validation_regex_timeouts_total{")
                && l.contains("outcome=\"resolved\"") && l.contains("reason=\"\"") && l.endsWith(" 1.0")), scrape);
        assertTrue(scrape.lines().anyMatch(l -> l.startsWith("fv_validation_regex_rechecks_total{")
                && l.contains("kind=\"plain\"") && l.endsWith(" 1.0")), scrape);
        assertTrue(scrape.lines().noneMatch(l -> l.startsWith("fv_validation_regex_rechecks_total{") && l.contains("kind=\"quiet\"")
                && !l.endsWith(" 0.0")), "no quiet re-check");
    }
}

