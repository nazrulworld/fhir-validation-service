package nzi.fhir.validator.core.terminology;

import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 010 FR-006, data-model §4: every row of the resolution table, against each kind of context default. */
class EffectiveTerminologyTest {
    private static final String A = "https://a.example.org/fhir";
    private static final String B = "https://b.example.org/fhir";
    private static final TerminologySettings D_NONE = TerminologySettings.NONE;
    private static final TerminologySettings D_SERVER = new TerminologySettings(TerminologyMode.SERVER, A);
    private static final TerminologySettings D_ECO = new TerminologySettings(TerminologyMode.ECOSYSTEM, null);
    private static final TerminologySettings D_ECO_A = new TerminologySettings(TerminologyMode.ECOSYSTEM, A);

    private static TerminologySettings resolve(TerminologySettings d, TerminologyMode mode, String tx) {
        EffectiveTerminology e = EffectiveTerminology.resolve(d, new TerminologyOverride(mode, tx));
        assertEquals(mode == null && tx == null ? EffectiveTerminology.Source.CONTEXT : EffectiveTerminology.Source.REQUEST,
                e.source());
        assertFalse(e.denied());
        return e.settings();
    }

    private static TerminologySettings s(TerminologyMode m, String server) {
        return new TerminologySettings(m, server);
    }

    @Test
    void absentParametersUseTheDefault() {
        for (TerminologySettings d : new TerminologySettings[]{D_NONE, D_SERVER, D_ECO, D_ECO_A}) {
            assertEquals(d, resolve(d, null, null));
        }
        assertEquals(D_NONE, EffectiveTerminology.resolve(null, TerminologyOverride.ABSENT).settings());
    }

    @Test
    void onlyTxKeepsTheModeOfAServerOrEcosystemDefault() {
        assertEquals(s(TerminologyMode.SERVER, B), resolve(D_NONE, null, B));
        assertEquals(s(TerminologyMode.SERVER, B), resolve(D_SERVER, null, B));
        assertEquals(s(TerminologyMode.ECOSYSTEM, B), resolve(D_ECO, null, B));
        assertEquals(s(TerminologyMode.ECOSYSTEM, B), resolve(D_ECO_A, null, B));
    }

    @Test
    void serverModeWithAndWithoutTx() {
        for (TerminologySettings d : new TerminologySettings[]{D_NONE, D_SERVER, D_ECO, D_ECO_A}) {
            assertEquals(s(TerminologyMode.SERVER, B), resolve(d, TerminologyMode.SERVER, B));
        }
        assertEquals(s(TerminologyMode.SERVER, A), resolve(D_SERVER, TerminologyMode.SERVER, null));
        assertEquals(s(TerminologyMode.SERVER, A), resolve(D_ECO_A, TerminologyMode.SERVER, null));
        for (TerminologySettings d : new TerminologySettings[]{D_NONE, D_ECO}) {
            ProblemException e = assertThrows(ProblemException.class,
                    () -> EffectiveTerminology.resolve(d, new TerminologyOverride(TerminologyMode.SERVER, null)));
            assertEquals(400, e.status());
            assertEquals("invalid-request", e.type());
            assertEquals("txMode=server needs a tx server; this context has none", e.detail());
        }
    }

    @Test
    void ecosystemModeWithAndWithoutTx() {
        for (TerminologySettings d : new TerminologySettings[]{D_NONE, D_SERVER, D_ECO, D_ECO_A}) {
            assertEquals(s(TerminologyMode.ECOSYSTEM, B), resolve(d, TerminologyMode.ECOSYSTEM, B));
        }
        assertEquals(s(TerminologyMode.ECOSYSTEM, null), resolve(D_NONE, TerminologyMode.ECOSYSTEM, null));
        assertEquals(s(TerminologyMode.ECOSYSTEM, null), resolve(D_SERVER, TerminologyMode.ECOSYSTEM, null));
        assertEquals(s(TerminologyMode.ECOSYSTEM, null), resolve(D_ECO, TerminologyMode.ECOSYSTEM, null));
        assertEquals(s(TerminologyMode.ECOSYSTEM, A), resolve(D_ECO_A, TerminologyMode.ECOSYSTEM, null));
    }

    @Test
    void noneModeIsOfflineAndRefusesTx() {
        for (TerminologySettings d : new TerminologySettings[]{D_NONE, D_SERVER, D_ECO, D_ECO_A}) {
            assertEquals(D_NONE, resolve(d, TerminologyMode.NONE, null));
        }
        assertThrows(IllegalArgumentException.class, () -> new TerminologyOverride(TerminologyMode.NONE, B));
    }

    @Test
    void scopeIdsSeparateServersAndModes() {
        assertEquals("none", EffectiveTerminology.NONE.scopeId());
        assertEquals("server:" + A, EffectiveTerminology.resolve(D_SERVER, TerminologyOverride.ABSENT).scopeId());
        assertEquals("ecosystem:-", EffectiveTerminology.resolve(D_ECO, TerminologyOverride.ABSENT).scopeId());
        assertEquals("ecosystem:" + A, EffectiveTerminology.resolve(D_ECO_A, TerminologyOverride.ABSENT).scopeId());
        assertEquals("denied", EffectiveTerminology.resolve(D_SERVER, TerminologyOverride.ABSENT).asDenied().scopeId());
    }

    @Test
    void reportedServerNeverNamesTheRegistryDefault() {
        assertNull(EffectiveTerminology.NONE.reportedServer());
        assertEquals(A, EffectiveTerminology.resolve(D_SERVER, TerminologyOverride.ABSENT).reportedServer());
        assertNull(EffectiveTerminology.resolve(D_ECO, TerminologyOverride.ABSENT).reportedServer());
        assertEquals(A, EffectiveTerminology.resolve(D_ECO_A, TerminologyOverride.ABSENT).reportedServer());
    }
}
