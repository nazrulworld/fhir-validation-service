package nzi.fhir.validator.core.usage;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Column cuts of a usage record (spec 012 data-model §3: {@code validation_usage.ig} is VARCHAR(256) since V17).
 */
class UsageRecordTest {

    private static UsageRecord withIg(String ig) {
        return new UsageRecord("req", Instant.EPOCH, null, null, null, "R4", ig, 0, "json", 0, "valid", 200, 0, 0, 0,
                0, null, 0, 0, 0, 0, -1, -1);
    }

    @Test
    void aTwoIgKeyOf256CharactersIsKeptWhole() {
        String key = "a".repeat(120) + "#1.0.0|" + "b".repeat(123) + "#2.0.0";
        assertEquals(256, key.length());
        assertEquals(key, withIg(key).ig());
    }

    @Test
    void longerValuesAreCutTo256() {
        assertEquals(256, withIg("x".repeat(400)).ig().length());
    }
}
