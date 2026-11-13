package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.StartupException;
import nzi.fhir.validator.core.enums.ServedVersions;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.core.settings.ServiceSettingsRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Startup reconciliation of stored validator contexts with the served FHIR versions (spec 009 FR-010 to FR-014,
 * research R5). In one transaction it marks every active context of a version that isn't served inactive (reason
 * {@code fhir-version-disabled}), marks every context that is inactive only for that reason active again when its
 * version is served, and bumps the revision of each version it touched, which notifies the other instances on commit.
 * Idempotent: concurrent starters serialise on the row locks and the later ones find nothing to change.
 */
public final class ContextActivation {
    private static final Logger logger = LogManager.getLogger(ContextActivation.class);

    private static final String SNAPSHOTS = DB_POSTGRES_SCHEMA_NAME + ".fhir_validator_snapshots";
    private static final String ORGANISATIONS = DB_POSTGRES_SCHEMA_NAME + ".organisations";

    /** Active contexts of versions not in {@code $1} (the served version names) become inactive. */
    static final String DEACTIVATE_SQL = "UPDATE " + SNAPSHOTS + " s SET is_active = false, inactive_reason = '"
            + ConfigRevisionRepository.INACTIVE_FHIR_VERSION_DISABLED + "', updated_at = now() FROM " + ORGANISATIONS + " o "
            + "WHERE o.id = s.owner AND s.is_active AND NOT (s.fhir_version = ANY($1)) "
            + "RETURNING s.fhir_version, s.ig_package, o.name_short";

    /**
     * Contexts inactive <em>only</em> because their version wasn't served become active again when it is (FR-014); a
     * context made inactive for any other reason is never touched.
     */
    static final String REACTIVATE_SQL = "UPDATE " + SNAPSHOTS + " s SET is_active = true, inactive_reason = NULL, "
            + "updated_at = now() FROM " + ORGANISATIONS + " o WHERE o.id = s.owner AND s.inactive_reason = '"
            + ConfigRevisionRepository.INACTIVE_FHIR_VERSION_DISABLED + "' AND s.fhir_version = ANY($1) "
            + "RETURNING s.fhir_version, s.ig_package, o.name_short";

    /** One context whose state changed. */
    public record Changed(String fhirVersion, String igPackage, String owner) {
    }

    /** What one reconciliation changed. */
    public record Result(List<Changed> deactivated, List<Changed> reactivated) {
    }

    private ContextActivation() {
    }

    /**
     * Reconciles stored contexts with {@code served} in one transaction, after the served-set guard (FR-033): the
     * transaction first locks the {@value ServiceSettingsRepository#FHIR_VERSIONS} setting. A start whose served set
     * differs from the one stored by the previous start is refused (nothing changes) unless {@code confirmed}
     * ({@code FV_FHIR_VERSIONS_CHANGE_CONFIRMED}), so one instance with a stale setting can't switch contexts off for the
     * whole fleet. No stored set (a fresh or just-upgraded database) needs no confirmation. Afterwards it logs one WARN
     * per deactivated context and a WARN count, one INFO per reactivated context and an INFO count (FR-012); nothing
     * when nothing changed.
     *
     * @return what changed; fails with {@link StartupException} when the guard refuses the start or the transaction
     * fails (nothing changed, FR-011)
     */
    public static Future<Result> reconcile(Pool pool, ServedVersions served, boolean confirmed) {
        List<Runnable> afterCommit = new ArrayList<>();
        return pool.withTransaction(tx -> reconcile(tx, served, confirmed, afterCommit))
                .recover(ContextActivation::asStartupFailure)
                .onSuccess(r -> afterCommit.forEach(Runnable::run));
    }

    /**
     * The same reconciliation inside the caller's transaction {@code tx} (T069): startup runs it in the access-control
     * step's transaction, so a start refused by either guard (FR-029, FR-033) changes nothing at all. Its log lines are
     * added to {@code afterCommit}, for the caller to run once the transaction has committed.
     */
    public static Future<Result> reconcile(SqlClient tx, ServedVersions served, boolean confirmed, List<Runnable> afterCommit) {
        String[] names = served.asList().stream().map(Enum::name).toArray(String[]::new);
        return guard(tx, served, confirmed, afterCommit)
                .compose(v -> run(tx, names))
                .onSuccess(r -> afterCommit.add(() -> log(r)))
                .recover(ContextActivation::asStartupFailure);
    }

    private static <T> Future<T> asStartupFailure(Throwable err) {
        return Future.failedFuture(err instanceof StartupException ? err : new StartupException(
                "validator context activation could not be stored: " + err.getMessage(), err));
    }

    /** The served set as stored: version names joined with commas in declaration order ({@code R4,R5}). */
    static String stored(ServedVersions served) {
        return String.join(",", served.asList().stream().map(Enum::name).toList());
    }

    /** FR-033: compares with the stored served set (locking it) and stores the new one. */
    private static Future<Void> guard(SqlClient tx, ServedVersions served, boolean confirmed, List<Runnable> afterCommit) {
        String setting = ServiceSettingsRepository.FHIR_VERSIONS;
        String value = stored(served);
        return ServiceSettingsRepository.lockForUpdate(tx, setting)
                .compose(stored -> stored.isPresent() ? Future.succeededFuture(stored)
                        : ServiceSettingsRepository.insertIfAbsent(tx, setting, value).compose(inserted -> inserted
                        ? Future.succeededFuture(java.util.Optional.<String>empty())
                        : ServiceSettingsRepository.lockForUpdate(tx, setting)))
                .compose(previous -> {
                    if (previous.isPresent() && !previous.get().equals(value)) {
                        if (!confirmed) {
                            return Future.failedFuture(new StartupException("FHIR version change refused: the stored served "
                                    + "versions are " + previous.get() + ", this instance is configured " + value + " ("
                                    + ServedVersions.VARIABLE + "). To change them, set FV_FHIR_VERSIONS_CHANGE_CONFIRMED=true "
                                    + "on the instances being changed (see docs/upgrade-4.2.md)"));
                        }
                        afterCommit.add(() -> logger.warn("Served FHIR versions change from {} to {} "
                                + "(FV_FHIR_VERSIONS_CHANGE_CONFIRMED)", previous.get(), value));
                    } else if (previous.isPresent() && confirmed) {
                        afterCommit.add(() -> logger.warn("FV_FHIR_VERSIONS_CHANGE_CONFIRMED is set but the served versions "
                                + "are unchanged ({}); remove it so an accidental change is refused", value));
                    }
                    return ServiceSettingsRepository.upsert(tx, setting, value, ServiceSettingsRepository.instanceName());
                });
    }

    /**
     * Locks every version row of the configuration first (code review of PR #72): exclude and PATCH lock a version row
     * and then its context rows, so taking the locks in the same order here can't deadlock with them.
     */
    private static Future<Result> run(SqlClient tx, String[] served) {
        List<Changed> deactivated = new ArrayList<>();
        List<Changed> reactivated = new ArrayList<>();
        return tx.preparedQuery("SELECT fhir_version FROM " + DB_POSTGRES_SCHEMA_NAME + ".fhir_validator_logs WHERE "
                        + "validator_id = $1 ORDER BY fhir_version FOR UPDATE")
                .execute(Tuple.of(ConfigRevisionRepository.VALIDATOR_ID))
                .compose(locked -> tx.preparedQuery(DEACTIVATE_SQL).execute(Tuple.of((Object) served)))
                .compose(rows -> {
                    collect(rows, deactivated);
                    return tx.preparedQuery(REACTIVATE_SQL).execute(Tuple.of((Object) served));
                })
                .compose(rows -> {
                    collect(rows, reactivated);
                    Set<SupportedFhirVersion> touched = new LinkedHashSet<>();
                    deactivated.forEach(c -> touched.add(SupportedFhirVersion.valueOf(c.fhirVersion())));
                    reactivated.forEach(c -> touched.add(SupportedFhirVersion.valueOf(c.fhirVersion())));
                    Future<Void> bumps = Future.succeededFuture();
                    for (SupportedFhirVersion version : touched) {
                        bumps = bumps.compose(v -> ConfigRevisionRepository.bumpForActivation(tx, version).mapEmpty());
                    }
                    return bumps;
                })
                .map(v -> new Result(sorted(deactivated), sorted(reactivated)));
    }

    private static void collect(RowSet<Row> rows, List<Changed> into) {
        for (Row r : rows) {
            into.add(new Changed(r.getString(0), r.getString(1), r.getString(2)));
        }
    }

    private static List<Changed> sorted(List<Changed> changed) {
        return changed.stream().sorted(java.util.Comparator.comparing(Changed::fhirVersion).thenComparing(Changed::igPackage))
                .toList();
    }

    private static void log(Result result) {
        for (Changed c : result.deactivated()) {
            logger.warn("Validator context deactivated: {} {} (owner {}): FHIR version {} is not enabled ({})",
                    c.fhirVersion(), c.igPackage(), c.owner(), c.fhirVersion(), ServedVersions.VARIABLE);
        }
        if (!result.deactivated().isEmpty()) {
            logger.warn("{} validator context(s) deactivated because their FHIR version is not enabled",
                    result.deactivated().size());
        }
        for (Changed c : result.reactivated()) {
            logger.info("Validator context reactivated: {} {} (owner {})", c.fhirVersion(), c.igPackage(), c.owner());
        }
        if (!result.reactivated().isEmpty()) {
            logger.info("{} validator context(s) reactivated", result.reactivated().size());
        }
    }
}
