package nzi.fhir.validator.core.validator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 013 research R3: the instance-wide permit that lets a quiet re-check run with no other validation. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ValidationGateTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ValidationGate gate = new ValidationGate();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void sharedPermitsRunConcurrently() throws Exception {
        CountDownLatch bothInside = new CountDownLatch(2);
        List<Future<Boolean>> f = List.of(
                pool.submit(() -> gate.shared(() -> await(bothInside))),
                pool.submit(() -> gate.shared(() -> await(bothInside))));
        assertTrue(f.get(0).get(5, TimeUnit.SECONDS));
        assertTrue(f.get(1).get(5, TimeUnit.SECONDS));
    }

    private static boolean await(CountDownLatch latch) {
        latch.countDown();
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void anExclusiveRequestWaitsForRunningSharedPermitsAndLaterSharedOnesQueueBehindIt() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch sharedRunning = new CountDownLatch(1);
        CountDownLatch releaseShared = new CountDownLatch(1);
        Future<?> first = pool.submit(() -> gate.shared(() -> {
            sharedRunning.countDown();
            order.add("shared-1 start");
            try {
                releaseShared.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add("shared-1 end");
            return null;
        }));
        assertTrue(sharedRunning.await(5, TimeUnit.SECONDS));
        Future<Optional<String>> exclusive = pool.submit(() -> gate.exclusive(() -> {
            order.add("exclusive");
            return "done";
        }, TimeUnit.SECONDS.toNanos(5)));
        waitUntilQueued();
        Future<?> later = pool.submit(() -> gate.shared(() -> order.add("shared-2")));
        Thread.sleep(200);
        assertEquals(List.of("shared-1 start"), order, "neither the exclusive request nor the later shared one ran yet");
        releaseShared.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of("done"), exclusive.get(5, TimeUnit.SECONDS));
        later.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("shared-1 start", "shared-1 end", "exclusive", "shared-2"), order,
                "fair: the later shared permit queued behind the waiting exclusive one");
    }

    private void waitUntilQueued() throws InterruptedException {
        for (int i = 0; i < 100 && !gate.hasQueuedRequests(); i++) {
            Thread.sleep(10);
        }
        assertTrue(gate.hasQueuedRequests());
    }

    @Test
    void anExclusiveRequestGivesUpAfterItsTimeoutWithoutRunning() throws Exception {
        CountDownLatch sharedRunning = new CountDownLatch(1);
        CountDownLatch releaseShared = new CountDownLatch(1);
        pool.submit(() -> gate.shared(() -> {
            sharedRunning.countDown();
            try {
                releaseShared.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertTrue(sharedRunning.await(5, TimeUnit.SECONDS));
        boolean[] ran = {false};
        long t0 = System.nanoTime();
        Optional<Object> result = gate.exclusive(() -> {
            ran[0] = true;
            return "x";
        }, TimeUnit.MILLISECONDS.toNanos(300));
        long waitedMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(result.isEmpty());
        assertFalse(ran[0]);
        assertTrue(waitedMs >= 250 && waitedMs < 3_000, "waited " + waitedMs + " ms");
        // A shared permit is available again at once: the abandoned exclusive request holds nobody back.
        assertEquals("ok", pool.submit(() -> gate.shared(() -> "ok")).get(1, TimeUnit.SECONDS));
        releaseShared.countDown();
    }

    @Test
    void theWorksExceptionIsRethrownAndThePermitReleased() {
        RuntimeException boom = new IllegalStateException("boom");
        assertSame(boom, assertThrows(IllegalStateException.class, () -> gate.shared(() -> {
            throw boom;
        })));
        assertSame(boom, assertThrows(IllegalStateException.class, () -> gate.exclusive(() -> {
            throw boom;
        }, TimeUnit.SECONDS.toNanos(1))));
        assertEquals(Optional.of("free"), gate.exclusive(() -> "free", TimeUnit.SECONDS.toNanos(1)), "both permits were released");
    }

    /** Spec 013 T016: a thread holding a shared permit can't ask for the exclusive one (that would deadlock). */
    @Test
    void askingForTheExclusivePermitWhileHoldingASharedOneIsRefused() {
        assertThrows(IllegalStateException.class, () -> gate.shared(() -> gate.exclusive(() -> "x", TimeUnit.SECONDS.toNanos(1))));
        assertEquals(Optional.of("free"), gate.exclusive(() -> "free", TimeUnit.SECONDS.toNanos(1)));
    }

    @Test
    void theExclusiveHolderIsVisibleToTheWork() {
        assertFalse(gate.shared(gate::exclusiveHeldByCurrentThread));
        assertEquals(Optional.of(true), gate.exclusive(gate::exclusiveHeldByCurrentThread, TimeUnit.SECONDS.toNanos(1)));
    }
}
