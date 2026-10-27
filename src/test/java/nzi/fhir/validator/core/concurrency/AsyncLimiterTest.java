package nzi.fhir.validator.core.concurrency;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncLimiterTest {

    @Test
    void runsAtMostPermitsTasksAndStartsQueuedOnesInOrder() {
        AsyncLimiter limiter = new AsyncLimiter(1);
        List<Promise<String>> work = new ArrayList<>();
        List<Integer> started = new ArrayList<>();
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int n = i;
            results.add(limiter.run(() -> {
                started.add(n);
                Promise<String> p = Promise.promise();
                work.add(p);
                return p.future();
            }));
        }
        assertEquals(List.of(0), started, "only one task may run");
        work.get(0).fail("boom");
        assertEquals(List.of(0, 1), started, "a failure releases the permit");
        work.get(1).complete("b");
        work.get(2).complete("c");
        assertEquals(List.of(0, 1, 2), started);
        assertTrue(results.get(0).failed());
        assertEquals("c", results.get(2).result());
    }

    @Test
    void aThrowingTaskReleasesItsPermit() {
        AsyncLimiter limiter = new AsyncLimiter(1);
        AtomicInteger ran = new AtomicInteger();
        Future<Object> thrown = limiter.run(() -> {
            throw new IllegalStateException("x");
        });
        Future<Integer> next = limiter.run(() -> Future.succeededFuture(ran.incrementAndGet()));
        assertTrue(thrown.failed());
        assertEquals(1, next.result());
    }

    @Test
    void buildsKeepOnePackageWorkerFree() {
        assertEquals(1, nzi.fhir.validator.core.validator.SnapshotBuilder.maxConcurrentBuilds(1));
        assertEquals(1, nzi.fhir.validator.core.validator.SnapshotBuilder.maxConcurrentBuilds(2));
        assertEquals(3, nzi.fhir.validator.core.validator.SnapshotBuilder.maxConcurrentBuilds(4));
    }
}
