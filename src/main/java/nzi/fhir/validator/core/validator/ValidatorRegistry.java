package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.support.IValidationSupport;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The validator of every enabled FHIR version on this instance (data-model.md §2). A derived, rebuildable cache:
 * PostgreSQL holds the configuration, this class holds the snapshot built from it.
 * <p>
 * Per version it tracks the active snapshot, the highest database revision fully applied
 * ({@code appliedRevision}, advanced by profile refreshes and snapshot swaps) and the latest revision seen. A
 * rebuild happens only when the resolved package set changes. If a newer configuration cannot be applied, the
 * version becomes STALE: it answers 503 instead of serving outdated results (FR-002), and the build is retried with
 * backoff until it succeeds.
 */
public class ValidatorRegistry {
    private static final Logger logger = LogManager.getLogger(ValidatorRegistry.class);
    private static final long INITIAL_RETRY_MS = 1_000;
    private static final long MAX_RETRY_MS = 60_000;

    public enum Status { ABSENT, BUILDING, ACTIVE, STALE }

    private static final class Slot {
        ValidatorSnapshot snapshot;
        Status status = Status.ABSENT;
        String reason;
        long appliedRevision;
        long latestSeenRevision;
        long wantedRevision;
        List<String> wantedResolved;
        boolean building;
        boolean dirty;
        long retryDelayMs = INITIAL_RETRY_MS;
        long retryTimer = -1;
        final List<Promise<Void>> waiters = new ArrayList<>();
    }

    private final Vertx vertx;
    private final SnapshotBuilder builder;
    private final Map<SupportedFhirVersion, ProfileStore> profileStores;
    private final Map<SupportedFhirVersion, Slot> slots = new EnumMap<>(SupportedFhirVersion.class);
    private volatile java.util.function.Consumer<SupportedFhirVersion> buildFailureListener = v -> { };

    public void setBuildFailureListener(java.util.function.Consumer<SupportedFhirVersion> listener) {
        this.buildFailureListener = listener;
    }

    public ValidatorRegistry(Vertx vertx, SnapshotBuilder builder, Map<SupportedFhirVersion, ProfileStore> profileStores) {
        this.vertx = vertx;
        this.builder = builder;
        this.profileStores = profileStores;
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            slots.put(v, new Slot());
        }
    }

    private Slot slot(SupportedFhirVersion version) {
        Slot slot = slots.get(version);
        if (slot == null) {
            throw ProblemException.unsupportedVersion(version.name());
        }
        return slot;
    }

    /**
     * The snapshot to validate with. While a newer configuration is being built, the previous snapshot keeps
     * serving; a STALE or absent version answers 503.
     */
    public ValidatorSnapshot current(SupportedFhirVersion version) {
        Slot slot = slot(version);
        synchronized (slot) {
            if (slot.status == Status.STALE) {
                throw ProblemException.notReady("The validator for " + version + " is out of date and being rebuilt");
            }
            if (slot.snapshot == null) {
                throw ProblemException.notReady("The validator for " + version + " is not ready yet");
            }
            return slot.snapshot;
        }
    }

    public void noteSeen(SupportedFhirVersion version, long revision) {
        Slot slot = slot(version);
        synchronized (slot) {
            slot.latestSeenRevision = Math.max(slot.latestSeenRevision, revision);
        }
    }

    public long appliedRevision(SupportedFhirVersion version) {
        Slot slot = slot(version);
        synchronized (slot) {
            return slot.appliedRevision;
        }
    }

    /**
     * Applies database revision {@code revision} whose resolved package set is {@code resolved}. Completes when that
     * revision (or a newer one) is active, and fails if the build fails (the version is then STALE and retries).
     */
    public Future<Void> apply(SupportedFhirVersion version, long revision, List<String> resolved) {
        Slot slot = slot(version);
        Promise<Void> promise = Promise.promise();
        boolean startBuild;
        synchronized (slot) {
            slot.latestSeenRevision = Math.max(slot.latestSeenRevision, revision);
            if (revision < slot.wantedRevision) {
                // An older revision arrived late; a newer one is already wanted.
                promise.complete();
                return promise.future();
            }
            List<String> newResolved = List.copyOf(resolved);
            boolean sameAsBuilding = slot.building && newResolved.equals(slot.wantedResolved);
            slot.wantedRevision = revision;
            slot.wantedResolved = newResolved;
            if (slot.snapshot != null && slot.snapshot.resolvedPackages().equals(slot.wantedResolved) && !slot.building) {
                // Profile-only change (or nothing new): no rebuild needed.
                slot.appliedRevision = Math.max(slot.appliedRevision, revision);
                slot.status = Status.ACTIVE;
                slot.reason = null;
                promise.complete();
                return promise.future();
            }
            slot.waiters.add(promise);
            if (slot.building) {
                // The running build already targets this package set: just wait for it.
                slot.dirty = slot.dirty || !sameAsBuilding;
                startBuild = false;
            } else {
                slot.building = true;
                if (slot.status != Status.STALE) {
                    slot.status = slot.snapshot == null ? Status.BUILDING : Status.ACTIVE;
                }
                startBuild = true;
            }
        }
        if (startBuild) {
            build(version, slot);
        }
        return promise.future();
    }

    private void build(SupportedFhirVersion version, Slot slot) {
        long revision;
        List<String> resolved;
        synchronized (slot) {
            revision = slot.wantedRevision;
            resolved = slot.wantedResolved;
            slot.dirty = false;
        }
        builder.build(version, revision, resolved, profileStores.get(version)).onComplete(ar -> {
            List<Promise<Void>> done;
            boolean again;
            synchronized (slot) {
                if (ar.succeeded()) {
                    slot.snapshot = ar.result();
                    slot.appliedRevision = Math.max(slot.appliedRevision,
                            resolved.equals(slot.wantedResolved) ? slot.wantedRevision : revision);
                    slot.status = Status.ACTIVE;
                    slot.reason = null;
                    slot.retryDelayMs = INITIAL_RETRY_MS;
                } else {
                    slot.status = Status.STALE;
                    slot.reason = describe(ar.cause());
                    logger.error("Validator build for {} at revision {} failed", version, revision, ar.cause());
                    buildFailureListener.accept(version);
                }
                again = slot.dirty || (ar.succeeded() && !resolved.equals(slot.wantedResolved));
                if (again) {
                    done = List.of();
                } else {
                    slot.building = false;
                    done = new ArrayList<>(slot.waiters);
                    slot.waiters.clear();
                }
            }
            if (again) {
                build(version, slot);
                return;
            }
            if (ar.succeeded()) {
                done.forEach(Promise::complete);
            } else {
                done.forEach(p -> p.fail(ar.cause()));
                scheduleRetry(version, slot);
            }
        });
    }

    private void scheduleRetry(SupportedFhirVersion version, Slot slot) {
        long delay;
        long revision;
        List<String> resolved;
        synchronized (slot) {
            if (slot.retryTimer >= 0) {
                return;
            }
            delay = slot.retryDelayMs;
            slot.retryDelayMs = Math.min(slot.retryDelayMs * 2, MAX_RETRY_MS);
            revision = slot.wantedRevision;
            resolved = slot.wantedResolved;
            slot.retryTimer = vertx.setTimer(delay, id -> {
                synchronized (slot) {
                    slot.retryTimer = -1;
                }
                apply(version, revision, resolved);
            });
        }
        logger.info("Retrying validator build for {} in {} ms", version, delay);
    }

    /**
     * Stops serving {@code version} (503, readiness false) because a newer committed configuration could not be
     * applied (FR-002). A later successful {@link #apply} or build makes it ACTIVE again.
     *
     * @param reason curated text for {@code /health}; never an exception message
     */
    public void markStale(SupportedFhirVersion version, String reason) {
        Slot slot = slot(version);
        synchronized (slot) {
            if (slot.snapshot != null && slot.status != Status.STALE) {
                slot.status = Status.STALE;
                slot.reason = reason;
                logger.warn("Validator for {} is out of date: {}", version, reason);
            }
        }
    }

    /**
     * After a profile change: replaces the active snapshot with a re-assembled one (same packages, fresh HAPI
     * caches) so new and updated profiles take effect. Skipped if a rebuild replaced the snapshot meanwhile.
     */
    public Future<Void> refreshProfiles(SupportedFhirVersion version) {
        Slot slot = slot(version);
        ValidatorSnapshot current;
        synchronized (slot) {
            current = slot.snapshot;
        }
        if (current == null) {
            return Future.succeededFuture();
        }
        return builder.reassemble(current).map(fresh -> {
            synchronized (slot) {
                if (slot.snapshot == current) {
                    slot.snapshot = fresh;
                }
            }
            return null;
        });
    }

    /**
     * @return empty when every enabled version can serve requests (a snapshot exists and is not STALE)
     */
    public List<String> notReadyReasons() {
        List<String> reasons = new ArrayList<>();
        slots.forEach((version, slot) -> {
            synchronized (slot) {
                if (slot.status == Status.STALE) {
                    reasons.add(version + " validator is out of date: " + slot.reason);
                } else if (slot.snapshot == null) {
                    reasons.add(version + " validator is " + slot.status.name().toLowerCase());
                }
            }
        });
        return reasons;
    }

    public boolean isReady() {
        return notReadyReasons().isEmpty();
    }

    public JsonObject details() {
        JsonObject details = new JsonObject();
        slots.forEach((version, slot) -> {
            synchronized (slot) {
                JsonObject d = new JsonObject()
                        .put("state", slot.building && slot.status == Status.ACTIVE ? "ACTIVE (rebuilding)" : slot.status.name())
                        .put("appliedRevision", slot.appliedRevision)
                        .put("latestRevision", slot.latestSeenRevision)
                        .put("packages", slot.snapshot == null ? List.of() : slot.snapshot.resolvedPackages());
                if (slot.reason != null) {
                    d.put("reason", slot.reason);
                }
                details.put(version.name(), d);
            }
        });
        return details;
    }

    /**
     * The core and IG package supports of the active snapshot (empty when there is none): where custom profiles look
     * up base definitions when their snapshots are generated.
     */
    public List<IValidationSupport> activeSupports(SupportedFhirVersion version) {
        Slot slot = slot(version);
        synchronized (slot) {
            return slot.snapshot == null ? List.of() : slot.snapshot.reusableSupports();
        }
    }

    /** Package count and archive bytes of the active snapshot (0 when there is none). */
    public long snapshotPackages(SupportedFhirVersion version) {
        Slot slot = slot(version);
        synchronized (slot) {
            return slot.snapshot == null ? 0 : slot.snapshot.resolvedPackages().size();
        }
    }

    public long snapshotPackageBytes(SupportedFhirVersion version) {
        Slot slot = slot(version);
        synchronized (slot) {
            return slot.snapshot == null ? 0 : slot.snapshot.packageArchiveBytes();
        }
    }

    public Status status(SupportedFhirVersion version) {
        Slot slot = slot(version);
        synchronized (slot) {
            return slot.status;
        }
    }

    /**
     * Curated reason shown in {@code /health} and readiness: names a missing package, otherwise generic. Exception
     * classes and messages go to the log only (constitution Principle IV).
     */
    private static String describe(Throwable t) {
        if (t instanceof SnapshotBuilder.MissingPackageException) {
            return t.getMessage();
        }
        return "validator build failed (see the instance log)";
    }

    public void close() {
        slots.values().forEach(slot -> {
            synchronized (slot) {
                if (slot.retryTimer >= 0) {
                    vertx.cancelTimer(slot.retryTimer);
                    slot.retryTimer = -1;
                }
            }
        });
    }
}
