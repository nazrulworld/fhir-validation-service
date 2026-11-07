package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.validator.PinnedAccess;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Validator configuration rows ({@code fhir_validator_logs}): one per FHIR version, holding the set of included IG
 * packages ({@code name#version}, each its own pinned validator context since 3.0.0) and a revision that increases on
 * every include, exclude or access change. The access of each included context lives in
 * {@code fhir_validator_snapshots} (spec 005, research R1) and is written in the same transaction, with the version
 * row locked. Writes notify other instances through {@code pg_notify}, which PostgreSQL delivers only when the
 * surrounding transaction commits.
 */
public final class ConfigRevisionRepository {
    public static final String CHANNEL = "fv_config_changed";
    public static final String VALIDATOR_ID = "standard_validator";
    public static final String KIND_IG = "IG";
    public static final String KIND_IG_EXCLUDE = "IG_EXCLUDE";
    /** An included context's owner or sharing changed: swap access, never rebuild (spec 005, contracts §9). */
    public static final String KIND_IG_ACCESS = "IG_ACCESS";
    public static final String KIND_PACKAGE = nzi.fhir.validator.core.npm.PostgresPackageCacheManager.KIND_PACKAGE;

    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".fhir_validator_logs";
    private static final String SNAPSHOTS = DB_POSTGRES_SCHEMA_NAME + ".fhir_validator_snapshots";
    private static final String ORGANISATIONS = DB_POSTGRES_SCHEMA_NAME + ".organisations";

    /**
     * @param access the access of every included context
     */
    public record ConfigRow(long revision, List<String> included, PinnedAccess access) {
        public ConfigRow(long revision, List<String> included) {
            this(revision, included, PinnedAccess.of(included, Map.of(), Organisations.DEFAULT_SYSTEM_SHORT_NAME));
        }
    }

    /**
     * One row per (version, included context): the version's columns, then the context's access (null when it has no
     * row), then the system organisation's short name for contexts without one.
     */
    private static final String SELECT = "SELECT l.fhir_version, l.revision, l.included_ig_packages, s.ig_package, s.owner, "
            + "o.name_short, s.is_shareable, sys.name_short FROM " + TABLE + " l "
            + "CROSS JOIN (SELECT name_short FROM " + ORGANISATIONS + " WHERE id = '" + Organisations.SYSTEM_ID + "') sys "
            + "LEFT JOIN " + SNAPSHOTS + " s ON s.fhir_version = l.fhir_version AND s.ig_package = ANY(l.included_ig_packages) "
            + "LEFT JOIN " + ORGANISATIONS + " o ON o.id = s.owner "
            + "WHERE l.validator_id = $1";

    private ConfigRevisionRepository() {
    }

    public static Future<Map<SupportedFhirVersion, ConfigRow>> current(SqlClient client) {
        return client.preparedQuery(SELECT)
                .execute(Tuple.of(VALIDATOR_ID))
                .map(rows -> {
                    Map<SupportedFhirVersion, ConfigRow> result = new EnumMap<>(SupportedFhirVersion.class);
                    toConfigRows(rows).forEach((label, row) ->
                            SupportedFhirVersion.parseEnabled(label).ifPresent(v -> result.put(v, row)));
                    return result;
                });
    }

    /**
     * Locks and returns the configuration row of {@code version} (call inside a transaction).
     */
    public static Future<ConfigRow> lockForUpdate(SqlClient tx, SupportedFhirVersion version) {
        return tx.preparedQuery(SELECT + " AND l.fhir_version = $2 FOR UPDATE OF l")
                .execute(Tuple.of(VALIDATOR_ID, version.name()))
                .compose(rows -> {
                    ConfigRow row = toConfigRows(rows).get(version.name());
                    return row == null
                            ? Future.failedFuture(new IllegalStateException("No configuration row for " + version))
                            : Future.succeededFuture(row);
                });
    }

    /**
     * Stores a new included IG set and bumps the revision; notifies on commit. An included context is owned by the
     * system organisation and shareable.
     *
     * @param kind {@link #KIND_IG} (include) or {@link #KIND_IG_EXCLUDE}
     */
    public static Future<Long> updateIgSet(SqlClient tx, SupportedFhirVersion version, List<String> included,
                                           String key, String kind) {
        return updateIgSet(tx, version, included, key, kind, Organisations.SYSTEM_ID, true);
    }

    /**
     * Stores a new included IG set, keeps {@code fhir_validator_snapshots} in line with it (data-model §1.4) and bumps
     * the revision; notifies on commit.
     *
     * @param owner     owner of the context {@code key} when it is added
     * @param shareable sharing of the context {@code key} when it is added
     */
    public static Future<Long> updateIgSet(SqlClient tx, SupportedFhirVersion version, List<String> included,
                                           String key, String kind, UUID owner, boolean shareable) {
        String[] sorted = included.stream().sorted().toArray(String[]::new);
        Future<?> child = KIND_IG.equals(kind)
                ? tx.preparedQuery("INSERT INTO " + SNAPSHOTS + " (fhir_version, ig_package, owner, is_shareable) "
                        + "VALUES ($1, $2, $3, $4) ON CONFLICT DO NOTHING")
                .execute(Tuple.of(version.name(), key, owner, shareable))
                : Future.succeededFuture();
        return child
                // Rows whose context is no longer included (the excluded one, and any left by a 3.0 instance).
                .compose(v -> tx.preparedQuery("DELETE FROM " + SNAPSHOTS + " WHERE fhir_version = $1 AND NOT (ig_package = ANY($2))")
                        // (Object): Tuple.of(String, String[]) would spread the array as varargs.
                        .execute(Tuple.of(version.name(), (Object) sorted)))
                .compose(v -> tx.preparedQuery("UPDATE " + TABLE + " SET included_ig_packages = $1, "
                                + "revision = revision + 1, updated_at = now() WHERE validator_id = $2 AND fhir_version = $3 RETURNING revision")
                        .execute(Tuple.of(sorted, VALIDATOR_ID, version.name())))
                .map(rows -> rows.iterator().next().getLong(0))
                .compose(revision -> notify(tx, version, revision, kind, key).map(revision));
    }

    /**
     * Changes the owner and/or sharing of the included context {@code key}, bumps the revision and notifies
     * {@link #KIND_IG_ACCESS} (call inside a transaction, after {@link #lockForUpdate}). A context included by a 3.0
     * instance has no row yet: it is created with the migration defaults first.
     *
     * @return the new revision
     */
    public static Future<Long> changeSnapshotAccess(SqlClient tx, SupportedFhirVersion version, String key,
                                                    Optional<UUID> owner, Optional<Boolean> shareable) {
        return tx.preparedQuery("INSERT INTO " + SNAPSHOTS + " AS t (fhir_version, ig_package, owner, is_shareable) "
                                + "VALUES ($1, $2, coalesce($3, '" + Organisations.SYSTEM_ID + "'::uuid), coalesce($4, true)) "
                                + "ON CONFLICT (fhir_version, ig_package) DO UPDATE SET owner = coalesce($3, t.owner), "
                                + "is_shareable = coalesce($4, t.is_shareable), updated_at = now()")
                        .execute(Tuple.of(version.name(), key, owner.orElse(null), shareable.orElse(null)))
                .compose(v -> tx.preparedQuery("UPDATE " + TABLE + " SET revision = revision + 1, updated_at = now() "
                                + "WHERE validator_id = $1 AND fhir_version = $2 RETURNING revision")
                        .execute(Tuple.of(VALIDATOR_ID, version.name())))
                .map(rows -> rows.iterator().next().getLong(0))
                .compose(revision -> notify(tx, version, revision, KIND_IG_ACCESS, key).map(revision));
    }

    /** The shared package catalog revision (spec 004, research R4). */
    public static Future<Long> catalogRevision(SqlClient client) {
        return client.query("SELECT revision FROM " + DB_POSTGRES_SCHEMA_NAME + ".fhir_package_catalog WHERE id = 1")
                .execute()
                .map(rows -> rows.size() == 0 ? 0L : rows.iterator().next().getLong(0));
    }

    private static Future<Void> notify(SqlClient tx, SupportedFhirVersion version, long revision, String kind, String key) {
        String payload = new JsonObject()
                .put("fhirVersion", version.name())
                .put("revision", revision)
                .put("kind", kind)
                .put("key", key)
                .encode();
        return tx.preparedQuery("SELECT pg_notify($1, $2)").execute(Tuple.of(CHANNEL, payload)).mapEmpty();
    }

    /** Folds the joined rows into one {@link ConfigRow} per version label. */
    private static Map<String, ConfigRow> toConfigRows(RowSet<Row> rows) {
        record Partial(long revision, List<String> included, String systemShortName, Map<String, Access> access) {
        }
        Map<String, Partial> partial = new HashMap<>();
        for (Row row : rows) {
            Partial p = partial.computeIfAbsent(row.getString(0), label -> new Partial(row.getLong(1),
                    array(row.getArrayOfStrings(2)), row.getString(7), new HashMap<>()));
            String igPackage = row.getString(3);
            if (igPackage != null) {
                p.access().put(igPackage, new Access(row.getUUID(4), row.getString(5), row.getBoolean(6)));
            }
        }
        Map<String, ConfigRow> result = new HashMap<>();
        partial.forEach((label, p) -> result.put(label, new ConfigRow(p.revision(), p.included(),
                PinnedAccess.of(p.included(), p.access(), p.systemShortName()))));
        return result;
    }

    private static List<String> array(String[] values) {
        return values == null ? List.of() : Arrays.stream(values).sorted().toList();
    }
}
