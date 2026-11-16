package nzi.fhir.validator.core.terminology;

import ca.uhn.fhir.context.support.IValidationSupport;
import com.github.benmanes.caffeine.cache.Ticker;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 010 FR-012, FR-013, research R4, data-model §6. */
class TerminologyCodeCacheTest {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private static TerminologyCodeCache.Key key(long snapshot, String scope, String code) {
        return new TerminologyCodeCache.Key(snapshot, scope, TerminologyCodeCache.Op.VALIDATE_CODE, R4, "http://loinc.org",
                code, null, null, "--");
    }

    private static IValidationSupport.CodeValidationResult ok(String code) {
        return new IValidationSupport.CodeValidationResult().setCode(code);
    }

    @Test
    void everyKeyPartSeparatesEntries() {
        TerminologyCodeCache cache = new TerminologyCodeCache(100, Duration.ofMinutes(10));
        cache.put(key(1, "server:https://a", "1"), ok("1"));
        assertNotNull(cache.get(key(1, "server:https://a", "1")));
        assertNull(cache.get(key(2, "server:https://a", "1")), "another snapshot never hits");
        assertNull(cache.get(key(1, "server:https://b", "1")), "another server never hits");
        assertNull(cache.get(key(1, "server:https://a", "2")));
        TerminologyCodeCache.Key base = key(1, "s", "c");
        cache.put(base, ok("c"));
        assertNull(cache.get(new TerminologyCodeCache.Key(1, "s", TerminologyCodeCache.Op.LOOKUP_CODE, R4, base.system(), "c", null, null, "--")));
        assertNull(cache.get(new TerminologyCodeCache.Key(1, "s", base.op(), SupportedFhirVersion.R5, base.system(), "c", null, null, "--")));
        assertNull(cache.get(new TerminologyCodeCache.Key(1, "s", base.op(), R4, "http://snomed.info/sct", "c", null, null, "--")));
        assertNull(cache.get(new TerminologyCodeCache.Key(1, "s", base.op(), R4, base.system(), "c", "Display", null, "--")));
        assertNull(cache.get(new TerminologyCodeCache.Key(1, "s", base.op(), R4, base.system(), "c", null, "http://vs", "--")));
        assertNull(cache.get(new TerminologyCodeCache.Key(1, "s", base.op(), R4, base.system(), "c", null, null, "i-")));
    }

    @Test
    void emptyAnswersAreCachedButNotCheckedAnswersNever() {
        TerminologyCodeCache cache = new TerminologyCodeCache(100, Duration.ofMinutes(10));
        cache.put(key(1, "none", "empty"), null);
        TerminologyCodeCache.Hit<Object> hit = cache.get(key(1, "none", "empty"));
        assertNotNull(hit);
        assertNull(hit.value());
        cache.put(key(1, "server:https://a", "x"),
                TerminologyClients.notChecked(TerminologyClients.Reasons.UNAVAILABLE, "http://loinc.org", "x"));
        assertNull(cache.get(key(1, "server:https://a", "x")), "a not-checked answer is never stored (FR-012)");
    }

    @Test
    void boundedBySizeAndLifetime() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        TerminologyCodeCache cache = new TerminologyCodeCache(10, Duration.ofMinutes(10), ticker);
        for (int i = 0; i < 100; i++) {
            cache.put(key(1, "none", "c" + i), ok("c" + i));
        }
        cache.cleanUp();
        assertTrue(cache.estimatedSize() <= 10, "maximumSize honoured");
        cache.put(key(1, "none", "late"), ok("late"));
        nanos.addAndGet(Duration.ofMinutes(11).toNanos());
        assertNull(cache.get(key(1, "none", "late")), "expireAfterWrite honoured");
    }

    @Test
    void invalidationRemovesOnlyItsOwnEntries() {
        TerminologyCodeCache cache = new TerminologyCodeCache(1000, Duration.ofMinutes(10));
        long[] invalidated = {0};
        cache.setListener(new TerminologyCodeCache.Listener() {
            @Override
            public void invalidated(String reason, long entries) {
                invalidated[0] += entries;
            }
        });
        cache.put(key(1, "server:https://a", "1"), ok("1"));
        cache.put(key(2, "server:https://a", "1"), ok("1"));
        cache.put(key(1, "server:https://b", "1"), ok("1"));
        cache.put(key(1, "ecosystem:-", "1"), ok("1"));
        cache.put(new TerminologyCodeCache.Key(4, "ecosystem:-", TerminologyCodeCache.Op.VALIDATE_CODE, SupportedFhirVersion.R5,
                "http://loinc.org", "1", null, null, "--"), ok("1"));

        cache.invalidateScope("server:https://a");
        assertNull(cache.get(key(1, "server:https://a", "1")));
        assertNull(cache.get(key(2, "server:https://a", "1")), "across all snapshots");
        assertNotNull(cache.get(key(1, "server:https://b", "1")));

        cache.invalidateSnapshot(1);
        assertNull(cache.get(key(1, "server:https://b", "1")));

        cache.put(key(3, "ecosystem:-", "1"), ok("1"));
        cache.put(key(3, "server:https://b", "1"), ok("1"));
        cache.invalidateEcosystem(SupportedFhirVersion.R4);
        assertNull(cache.get(key(3, "ecosystem:-", "1")));
        assertNotNull(cache.get(key(3, "server:https://b", "1")));
        assertNotNull(cache.get(new TerminologyCodeCache.Key(4, "ecosystem:-", TerminologyCodeCache.Op.VALIDATE_CODE,
                SupportedFhirVersion.R5, "http://loinc.org", "1", null, null, "--")), "other versions keep theirs");
        assertTrue(invalidated[0] >= 5);
    }
}
