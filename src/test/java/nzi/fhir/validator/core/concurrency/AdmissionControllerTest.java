package nzi.fhir.validator.core.concurrency;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdmissionControllerTest {

    @Test
    void rejectsBeyondLimitWithRetryAfterAndReleasesSlots() {
        AdmissionController admission = new AdmissionController(2);
        Promise<String> p1 = Promise.promise();
        Promise<String> p2 = Promise.promise();
        Future<String> f1 = admission.admit(p1::future);
        Future<String> f2 = admission.admit(p2::future);
        assertEquals(2, admission.pending());

        Future<String> rejected = admission.admit(() -> Future.succeededFuture("never"));
        assertTrue(rejected.failed());
        ProblemException e = (ProblemException) rejected.cause();
        assertEquals(503, e.status());
        assertEquals("overloaded", e.type());
        assertEquals("1", e.headers().get("Retry-After"));

        p1.complete("ok");
        p2.fail(new IllegalStateException("boom"));
        assertTrue(f1.succeeded());
        assertTrue(f2.failed());
        assertEquals(0, admission.pending(), "slots are released after success and failure");
        assertTrue(admission.admit(() -> Future.succeededFuture("again")).succeeded());
    }

    @Test
    void releasesSlotWhenSupplierThrows() {
        AdmissionController admission = new AdmissionController(1);
        Future<String> f = admission.admit(() -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(f.failed());
        assertEquals(0, admission.pending());
    }
}
