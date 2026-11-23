package nzi.fhir.validator.core.throttle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec 014 (T022, research R3): concurrent checks never both take the last slot, and the two locks never deadlock. */
class ThrottleConcurrencyTest {

    @Test
    @Timeout(10)
    void exactlyTheCapIsAdmittedUnderContention() throws InterruptedException {
        UUID key = UUID.randomUUID();
        UUID key2 = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        ThrottleCaps caps = new ThrottleCaps(Map.of(key, 100, key2, 1_000_000), Map.of(org, 150), OptionalInt.empty(),
                OptionalInt.empty(), Instant.EPOCH);
        Throttle t = new Throttle(1_000, 1_000);
        long now = 1_700_000_000_000L;
        AtomicInteger keyAdmitted = new AtomicInteger();
        AtomicInteger key2Admitted = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            boolean second = i % 2 == 1;
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int n = 0; n < 1_000; n++) {
                    if (t.check(second ? key2 : key, org, caps, now).admitted()) {
                        (second ? key2Admitted : keyAdmitted).incrementAndGet();
                    }
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread th : threads) {
            th.join();
        }
        // The organisation's 150 is shared by both keys, so the first key may get fewer than its own 100.
        assertTrue(keyAdmitted.get() <= 100, "the key's cap: " + keyAdmitted.get());
        assertEquals(150, keyAdmitted.get() + key2Admitted.get(), "the organisation's cap");
        assertEquals(150, t.window(Throttle.ORG, org).count(now / 1000));
        assertEquals(keyAdmitted.get(), t.window(Throttle.KEY, key).count(now / 1000));
    }

    @Test
    @Timeout(10)
    void oneKeyUnderContentionGetsExactlyItsCap() throws InterruptedException {
        UUID key = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        ThrottleCaps caps = new ThrottleCaps(Map.of(key, 100), Map.of(org, 150), OptionalInt.empty(), OptionalInt.empty(),
                Instant.EPOCH);
        Throttle t = new Throttle(1_000, 1_000);
        long now = 1_700_000_000_000L;
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int n = 0; n < 1_000; n++) {
                    if (t.check(key, org, caps, now).admitted()) {
                        admitted.incrementAndGet();
                    }
                }
            });
            threads[i].start();
        }
        go.countDown();
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(100, admitted.get());
        assertEquals(100, t.window(Throttle.ORG, org).count(now / 1000), "the organisation counted exactly the admitted");
    }
}
