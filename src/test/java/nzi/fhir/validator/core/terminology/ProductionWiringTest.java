package nzi.fhir.validator.core.terminology;

import nzi.fhir.validator.core.config.ConfigLoader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec 007 FR-006, FR-011: nothing in production can change the registry address or relax the address check.
 * {@code TxRegistryIT} checks that {@link TxRegistry#official} uses the official address and the strict guard; this
 * test pins the building blocks (no public constructor or factory takes an address, no configuration variable).
 */
class ProductionWiringTest {

    @Test
    void theOfficialRegistryAddressIsAConstant() throws NoSuchFieldException {
        var field = TxRegistry.class.getField("OFFICIAL_BASE");
        assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
        assertEquals("https://ecosystem.hl7.org/tx-reg", TxRegistry.OFFICIAL_BASE);
    }

    @Test
    void onlyThePackagePrivateTestConstructorTakesAnAddress() {
        assertTrue(Arrays.stream(TxRegistry.class.getConstructors()).noneMatch(c -> true),
                "TxRegistry has no public constructor; production uses TxRegistry.official(...)");
        assertTrue(Arrays.stream(TxRegistry.class.getDeclaredConstructors())
                .filter(c -> Arrays.asList(c.getParameterTypes()).contains(String.class))
                .noneMatch(c -> Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())));
        assertTrue(Arrays.stream(TxRegistry.class.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()) && m.getReturnType() == TxRegistry.class)
                .allMatch(m -> Arrays.stream(m.getParameterTypes()).noneMatch(t -> t == String.class)),
                "no public factory takes an address");
    }

    @Test
    void theStrictGuardHasNoExemptionAndTheTestGuardIsNotPublic() throws NoSuchMethodException {
        assertFalse(NetworkGuard.strict().allowsLoopback());
        assertFalse(Modifier.isPublic(NetworkGuard.class.getDeclaredMethod("forTests").getModifiers()));
    }

    @Test
    void noConfigurationVariableNamesARegistryOrAnAllowedServer() {
        var config = ConfigLoader.load(Map.of("FV_PG_PASSWORD", "pw", "FV_TX_REGISTRY", "http://evil.example",
                "FV_TX_ALLOWED_SERVERS", "http://10.0.0.5"));
        assertFalse(config.toString().contains("evil.example"));
        assertFalse(config.toString().contains("10.0.0.5"));
    }
}
