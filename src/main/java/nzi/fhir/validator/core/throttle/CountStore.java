package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;

import java.util.List;
import java.util.UUID;

/**
 * Where instances share their request counts (spec 014, research R4): PostgreSQL in production
 * ({@link ThrottleCountsRepository}), an in-memory table in the fleet simulation test.
 */
public interface CountStore {

    /** One instance's (or, read back, every other instance's) count of one subject in one second. */
    record CountRow(char scope, UUID subject, long second, int count) {
    }

    /** Writes this instance's absolute counts (a repeated write is harmless). */
    Future<Void> flush(UUID instance, List<CountRow> rows);

    /** Records that this instance is alive; returns how many other instances were seen in the last 5 seconds. */
    Future<Integer> heartbeat(UUID instance, String name);

    /** The other instances' totals per subject and second, from {@code sinceSecond} on. */
    Future<List<CountRow>> read(UUID instance, long sinceSecond);

    /** Deletes counts older than {@code beforeSecond}, and instances not seen for an hour. */
    Future<Void> cleanup(long beforeSecond);

    /** Removes this instance's heartbeat (graceful stop). */
    Future<Void> remove(UUID instance);
}
