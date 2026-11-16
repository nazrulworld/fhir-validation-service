package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 010 FR-017 to FR-019, research R7: one parse per package content, shared while used, released after. */
class SharedPackageDefinitionsTest {
    private static final SupportedFhirVersion R4 = SupportedFhirVersion.R4;

    private static SharedPackage.Key key(String id, int hash) {
        return new SharedPackage.Key(R4, id, new byte[]{(byte) hash, 1, 2});
    }

    private static SharedPackage pkg(SharedPackage.Key key) {
        return new SharedPackage(key, List.of(new Patient().setId("p")), 10);
    }

    @Test
    void theSameContentIsOneEntryAndOtherContentAnother() {
        SharedPackageDefinitions shared = new SharedPackageDefinitions();
        SharedPackage first = shared.getOrParse(key("a#1", 1), () -> pkg(key("a#1", 1)));
        assertSame(first, shared.getOrParse(key("a#1", 1), () -> fail("parsed again")));
        SharedPackage replaced = shared.getOrParse(key("a#1", 2), () -> pkg(key("a#1", 2)));
        assertNotSame(first, replaced, "different content under the same name#version is another entry (FR-017)");
        assertNotSame(first, shared.getOrParse(new SharedPackage.Key(SupportedFhirVersion.R5, "a#1", new byte[]{1, 1, 2}),
                () -> new SharedPackage(new SharedPackage.Key(SupportedFhirVersion.R5, "a#1", new byte[]{1, 1, 2}), List.of(), 1)));
        assertTrue(shared.contains(key("a#1", 1)));
        assertEquals(2, shared.entries(R4));
        assertEquals(20, shared.expandedBytes(R4));
        assertEquals(first, first, "keep the entries reachable until here");
        assertNotNull(replaced);
    }

    @Test
    void concurrentBuildsParseOnce() throws Exception {
        SharedPackageDefinitions shared = new SharedPackageDefinitions();
        AtomicInteger parses = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8); // stands in for the package workers
        try {
            List<Future<SharedPackage>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return shared.getOrParse(key("big#1", 1), () -> {
                        parses.incrementAndGet();
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return pkg(key("big#1", 1));
                    });
                }));
            }
            start.countDown();
            SharedPackage first = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<SharedPackage> f : results) {
                assertSame(first, f.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, parses.get(), "at most one parse (FR-018)");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedParseStoresNothingAndParsesNeverOverlap() throws Exception {
        SharedPackageDefinitions shared = new SharedPackageDefinitions();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    try {
                        return shared.getOrParse(key("bad#1", 1), () -> {
                            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                            attempts.incrementAndGet();
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            running.decrementAndGet();
                            throw new IllegalStateException("corrupt package");
                        });
                    } catch (IllegalStateException e) {
                        return e;
                    }
                }));
            }
            for (Future<Object> f : results) {
                assertInstanceOf(IllegalStateException.class, f.get(10, TimeUnit.SECONDS), "every build fails");
            }
            assertEquals(1, maxRunning.get(), "parses never overlap; each waiter retries in turn (FR-018)");
            assertEquals(4, attempts.get());
            assertFalse(shared.contains(key("bad#1", 1)), "nothing half-parsed is kept");
            SharedPackage ok = shared.getOrParse(key("bad#1", 1), () -> pkg(key("bad#1", 1)));
            assertSame(ok, shared.getOrParse(key("bad#1", 1), () -> fail("parsed again")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anEntryIsReleasedOnceNoContextHoldsIt() throws Exception {
        SharedPackageDefinitions shared = new SharedPackageDefinitions();
        shared.getOrParse(key("gone#1", 1), () -> pkg(key("gone#1", 1)));
        long deadline = System.currentTimeMillis() + 10_000;
        while (shared.contains(key("gone#1", 1)) && System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(50);
        }
        assertFalse(shared.contains(key("gone#1", 1)), "released after the last strong reference (FR-019)");
        assertEquals(0, shared.entries(R4));
    }
}
