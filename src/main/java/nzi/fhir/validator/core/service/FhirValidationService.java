package nzi.fhir.validator.core.service;

import ca.uhn.fhir.parser.DataFormatException;
import io.vertx.core.Future;
import nzi.fhir.validator.core.metrics.ServiceMetrics;
import nzi.fhir.validator.core.concurrency.AdmissionController;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.AccessPolicy;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.validator.IgReference;
import nzi.fhir.validator.core.validator.PinnedAccess;
import nzi.fhir.validator.core.validator.ResolvedContext;
import nzi.fhir.validator.web.ProblemException;
import nzi.fhir.validator.core.validator.SnapshotKey;
import nzi.fhir.validator.core.validator.ValidatorRegistry;
import nzi.fhir.validator.core.validator.ValidatorSnapshot;

import java.util.List;
import java.util.function.Function;

/**
 * Validates resources with the validator context the request chose: the IG package named by {@code ig}, or the
 * core-only base context (spec 004). Stateless: validators live in {@link ValidatorRegistry}. Work is admitted up to a
 * bound ({@link AdmissionController}) and runs on the {@code fv-validation} pool, unordered, so requests are validated
 * in parallel (fixes P1).
 * <p>
 * The admission slot is taken first and held while the {@code ig} reference is resolved and while the request waits
 * for an on-demand context build, so the number of waiting requests is bounded by {@code FV_VALIDATION_MAX_PENDING}
 * (constitution Principle VI, research R5). Every failure, {@code package-not-found} included, is counted once.
 *
 * @author Md Nazrul Islam
 */
public class FhirValidationService {
    private static final org.apache.logging.log4j.Logger logger =
            org.apache.logging.log4j.LogManager.getLogger(FhirValidationService.class);
    private volatile Function<SupportedFhirVersion, PinnedAccess> pinnedAccess = v -> PinnedAccess.EMPTY;

    private final ValidatorRegistry registry;
    private final Executors executors;
    private final AdmissionController admission;
    private final int maxBundleEntries;
    private volatile ServiceMetrics metrics;
    private volatile nzi.fhir.validator.core.npm.PackageCatalog catalog =
            new nzi.fhir.validator.core.npm.PackageCatalog(
                    () -> Future.succeededFuture(nzi.fhir.validator.core.npm.PackageCatalogView.EMPTY), System::currentTimeMillis);

    private record Done<T>(boolean valid, T value) {
    }

    public FhirValidationService(ValidatorRegistry registry, Executors executors, AdmissionController admission) {
        this(registry, executors, admission, Integer.MAX_VALUE);
    }

    public FhirValidationService(ValidatorRegistry registry, Executors executors, AdmissionController admission,
                                 int maxBundleEntries) {
        this.registry = registry;
        this.executors = executors;
        this.admission = admission;
        this.maxBundleEntries = maxBundleEntries;
    }

    public void setMetrics(ServiceMetrics metrics) {
        this.metrics = metrics;
    }

    /** The package catalog {@code ig} references are resolved against. */
    public void setCatalog(nzi.fhir.validator.core.npm.PackageCatalog catalog) {
        this.catalog = catalog;
    }

    /** The access of each version's included contexts (spec 005), read without a lock. */
    public void setPinnedAccess(Function<SupportedFhirVersion, PinnedAccess> pinnedAccess) {
        this.pinnedAccess = pinnedAccess;
    }

    /**
     * Validates against the base context.
     *
     * @param who the request's caller; there is deliberately no overload without one (spec 005: fail closed)
     * @return the outcome; fails with {@code DataFormatException} if the body is not parseable FHIR, with a 503
     * problem if the version's validator is not ready or the instance is overloaded
     */
    public Future<ValidatorSnapshot.Outcome> validate(Caller who, SupportedFhirVersion version, String body,
                                                      SupportedContentType contentType, List<String> profiles) {
        return validate(who, version, null, body, contentType, profiles, Function.identity());
    }

    /**
     * Validates and then applies {@code finisher} to the outcome, both on the validation worker, so rendering large
     * results never runs on the event loop (constitution Principle I).
     *
     * @param who the request's caller: only contexts it may use are served (spec 005 FR-010 to FR-013)
     * @param ig  the request's {@code ig} reference, or {@code null} for the base context
     */
    public <T> Future<T> validate(Caller who, SupportedFhirVersion version, IgReference ig, String body,
                                  SupportedContentType contentType, List<String> profiles,
                                  Function<ValidatorSnapshot.Outcome, T> finisher) {
        return validate(who, version, ig, body, contentType, profiles, nzi.fhir.validator.core.usage.UsageMeter.NONE, finisher);
    }

    /**
     * Like {@link #validate(Caller, SupportedFhirVersion, IgReference, String, SupportedContentType, List, Function)},
     * marking each phase on {@code meter} (spec 008 research R10): admission, context ready, and the CPU time, allocated
     * bytes and issue counts of the work on the validation worker.
     */
    public <T> Future<T> validate(Caller who, SupportedFhirVersion version, IgReference ig, String body,
                                  SupportedContentType contentType, List<String> profiles,
                                  nzi.fhir.validator.core.usage.UsageMeter meter,
                                  Function<ValidatorSnapshot.Outcome, T> finisher) {
        java.util.Objects.requireNonNull(who, "caller");
        long started = System.nanoTime();
        // Steps may complete on another context (a joined catalog refresh, a joined build): return to the caller's
        // context after each, so its request id stays on every log line (Principle VII).
        io.vertx.core.Context caller = io.vertx.core.Vertx.currentContext();
        java.util.concurrent.atomic.AtomicReference<Access> contextAccess = new java.util.concurrent.atomic.AtomicReference<>();
        return admission.admit(() -> {
                    meter.markAdmitted();
                    return onCaller(caller, resolve(who, version, ig))
                        .compose(key -> {
                            // Before any build is admitted, and from memory only (FR-013).
                            try {
                                contextAccess.set(checkAccess(who, key, ig));
                            } catch (ProblemException e) {
                                return Future.failedFuture(e);
                            }
                            return onCaller(caller, registry.snapshot(key));
                        })
                        .compose(snapshot -> {
                            meter.markContextReady();
                            return executors.validation().executeBlocking(() -> {
                                meter.workerStart();
                                int[] issues = {0, 0, 0, 0};
                                try {
                                    ValidatorSnapshot.Outcome outcome = snapshot.validate(body, contentType, profiles, maxBundleEntries)
                                            .withContextAccess(contextAccess.get());
                                    meter.ig(outcome.contextLabel());
                                    countIssues(outcome, issues);
                                    return new Done<>(outcome.result().isSuccessful(), finisher.apply(outcome));
                                } finally {
                                    meter.workerEnd(issues[0], issues[1], issues[2], issues[3]);
                                }
                            }, false);
                        });
                })
                .onComplete(ar -> {
                    String outcome = ar.succeeded()
                            ? (ar.result().valid() ? ServiceMetrics.OUTCOME_VALID : ServiceMetrics.OUTCOME_INVALID)
                            : failureOutcome(ar.cause());
                    ServiceMetrics m = metrics;
                    if (m != null) {
                        m.recordValidation(version, outcome, System.nanoTime() - started);
                        // Spec 008 FR-020: measured whatever the usage sink (an admission refusal stays "error").
                        m.recordValidationResources(version, outcome, meter.cpuNanos(), meter.allocatedBytes());
                    }
                    meter.outcome(outcome);
                    meter.workDone();
                })
                .map(Done::value);
    }

    /** {@code future}'s outcome, delivered on {@code caller} (as is when there is no caller or it is already there). */
    private static <T> Future<T> onCaller(io.vertx.core.Context caller, Future<T> future) {
        if (caller == null) {
            return future;
        }
        io.vertx.core.Promise<T> promise = io.vertx.core.Promise.promise();
        future.onComplete(ar -> {
            if (io.vertx.core.Vertx.currentContext() == caller) {
                promise.handle(ar);
            } else {
                caller.runOnContext(v -> promise.handle(ar));
            }
        });
        return promise.future();
    }

    private Future<SnapshotKey> resolve(Caller who, SupportedFhirVersion version, IgReference ig) {
        // Spec 007 FR-021: only included contexts serve a named IG; the included keys come from memory (no DB wait).
        return ig == null ? Future.succeededFuture(SnapshotKey.base(version))
                : ig.resolve(version, catalog, who, pinnedAccess.apply(version).asMap().keySet());
    }

    /**
     * The access rule for the context {@code key} (spec 005 FR-010, research R4, R5): the context and every package it
     * is built from must be usable by {@code who}. Reads only in-memory state and never takes the registry lock: the
     * packages of a built base or pinned context come from its snapshot, any other context's from the catalog view
     * (the same resolution its build uses). A refusal is indistinguishable from "not stored".
     *
     * @return the context's access (for FR-020)
     * @throws ProblemException 404 for an IG (or context) the caller may not use, 424 for a dependency
     */
    private Access checkAccess(Caller who, SnapshotKey key, IgReference ig) {
        PackageCatalogView view = catalog.current();
        if (key.isBase()) {
            return Access.systemShared(view.systemShortName());
        }
        PinnedAccess pinned = pinnedAccess.apply(key.fhirVersion());
        List<String> resolved = registry.peekResolvedPackages(key)
                .orElseGet(() -> ResolvedContext.forKey(key.igPackage(), view, pinned).packages());
        AccessPolicy.Decision decision = AccessPolicy.canUseContext(who, key, resolved, view, pinned);
        switch (decision) {
            case IG_NOT_VISIBLE -> {
                if (logger.isDebugEnabled()) {
                    logger.debug("Validator context {} is not visible to {}", key, who.label());
                }
                throw ProblemException.igNotStored(ig.label());
            }
            case DEPENDENCY_NOT_VISIBLE -> {
                if (logger.isDebugEnabled()) {
                    logger.debug("A dependency of validator context {} is not visible to {}", key, who.label());
                }
                throw ProblemException.dependencyUnavailable();
            }
            default -> {
                Access a = pinned.get(key.igPackage());
                return a != null ? a : view.access(key.igPackage());
            }
        }
    }

    /** Issue counts by severity: fatal, error, warning, information (on the worker, never the event loop). */
    private static void countIssues(ValidatorSnapshot.Outcome outcome, int[] counts) {
        for (ca.uhn.fhir.validation.SingleValidationMessage m : outcome.result().getMessages()) {
            if (m.getSeverity() == null) {
                continue;
            }
            switch (m.getSeverity()) {
                case FATAL -> counts[0]++;
                case ERROR -> counts[1]++;
                case WARNING -> counts[2]++;
                case INFORMATION -> counts[3]++;
                default -> {
                }
            }
        }
    }

    private static String failureOutcome(Throwable cause) {
        if (cause instanceof DataFormatException) {
            return ServiceMetrics.OUTCOME_UNPARSEABLE;
        }
        if (cause instanceof nzi.fhir.validator.web.ProblemException p
                && nzi.fhir.validator.web.ProblemException.TYPE_UNKNOWN_PROFILE.equals(p.type())) {
            return ServiceMetrics.OUTCOME_UNKNOWN_PROFILE;
        }
        // A missing dependency and a dependency the caller may not use record the same outcome as a missing package:
        // /metrics is unauthenticated, so no label may tell a refusal from "not stored" (spec 005 research R5).
        if (cause instanceof nzi.fhir.validator.web.ProblemException p
                && (ProblemException.TYPE_PACKAGE_NOT_FOUND.equals(p.type())
                || ProblemException.TYPE_DEPENDENCY_NOT_FOUND.equals(p.type()))) {
            return ServiceMetrics.OUTCOME_PACKAGE_NOT_FOUND;
        }
        return ServiceMetrics.OUTCOME_ERROR;
    }

    public ValidatorRegistry registry() {
        return registry;
    }

    public AdmissionController admission() {
        return admission;
    }
}
