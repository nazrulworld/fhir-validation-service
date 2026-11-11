package nzi.fhir.validator.core.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 008 FR-007 and review regressions: the last-used throttle is bounded and a shared key isn't written per request. */
class ApiKeyRepositoryTouchTest {
    private final ApiKeyRepository repo = new ApiKeyRepository(null);

    @Test
    void sameAddressIsWrittenAtMostOncePerMinute() {
        UUID k = UUID.randomUUID();
        assertTrue(repo.shouldTouch(k, "10.0.0.1", 0));
        assertFalse(repo.shouldTouch(k, "10.0.0.1", 59_999));
        assertTrue(repo.shouldTouch(k, "10.0.0.1", 60_000));
    }

    @Test
    void aNewAddressIsWrittenAtOnceButAtMostOncePerSecond() {
        UUID k = UUID.randomUUID();
        assertTrue(repo.shouldTouch(k, "10.0.0.1", 0));
        assertFalse(repo.shouldTouch(k, "10.0.0.2", 500), "a shared key used from many addresses isn't written per request");
        assertTrue(repo.shouldTouch(k, "10.0.0.3", 1_000), "a new address shows up within a second");
        assertFalse(repo.shouldTouch(k, "10.0.0.3", 1_500));
        // A thousand requests a second from changing addresses: about one write per second, not a thousand.
        int writes = 0;
        for (int i = 0; i < 1_000; i++) {
            if (repo.shouldTouch(k, "10.0.1." + (i % 250), 2_000 + i)) {
                writes++;
            }
        }
        assertEquals(1, writes);
    }

    @Test
    void theThrottleIsBounded() {
        for (int i = 0; i < ApiKeyRepository.MAX_TOUCHED_KEYS + 500; i++) {
            repo.shouldTouch(UUID.randomUUID(), "10.0.0.1", i);
        }
        assertTrue(repo.touchedKeys() <= ApiKeyRepository.MAX_TOUCHED_KEYS, "holds " + repo.touchedKeys());
    }

    /** Code review regression: an unknown address is never a move and never replaces the known one. */
    @Test
    void anUnknownAddressIsNotAMove() {
        UUID k = UUID.randomUUID();
        assertTrue(repo.shouldTouch(k, "10.0.0.1", 0));
        assertFalse(repo.shouldTouch(k, null, 1_500), "no immediate write for an unknown address");
        assertTrue(repo.shouldTouch(k, null, 60_000), "the minute throttle still records the use");
        assertFalse(repo.shouldTouch(k, "10.0.0.1", 61_500), "the known address was kept, so this isn't a move");
    }
}
