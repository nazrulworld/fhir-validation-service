package nzi.fhir.validator.core.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Validator configuration rows ({@code fhir_validator_logs}): one per FHIR version, with a revision that increases
 * on every IG or profile change. Writes notify other instances through {@code pg_notify}, which PostgreSQL delivers
 * only when the surrounding transaction commits.
 */
public final class ConfigRevisionRepository {
    public static final String CHANNEL = "fv_config_changed";
    public static final String VALIDATOR_ID = "standard_validator";
    public static final String KIND_IG = "IG";
    public static final String KIND_PROFILE = "PROFILE";

    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".fhir_validator_logs";

    public record ConfigRow(long revision, List<String> included, List<String> resolved) {
    }

    private ConfigRevisionRepository() {
    }

    public static Future<Map<SupportedFhirVersion, ConfigRow>> current(SqlClient client) {
        return client.preparedQuery("SELECT fhir_version, revision, included_ig_packages, resolved_ig_packages FROM "
                        + TABLE + " WHERE validator_id = $1")
                .execute(Tuple.of(VALIDATOR_ID))
                .map(rows -> {
                    Map<SupportedFhirVersion, ConfigRow> result = new EnumMap<>(SupportedFhirVersion.class);
                    for (Row row : rows) {
                        SupportedFhirVersion.parseEnabled(row.getString(0))
                                .ifPresent(v -> result.put(v, toConfigRow(row)));
                    }
                    return result;
                });
    }

    /**
     * Locks and returns the configuration row of {@code version} (call inside a transaction).
     */
    public static Future<ConfigRow> lockForUpdate(SqlClient tx, SupportedFhirVersion version) {
        return tx.preparedQuery("SELECT fhir_version, revision, included_ig_packages, resolved_ig_packages FROM "
                        + TABLE + " WHERE validator_id = $1 AND fhir_version = $2 FOR UPDATE")
                .execute(Tuple.of(VALIDATOR_ID, version.name()))
                .compose(rows -> rows.size() == 0
                        ? Future.failedFuture(new IllegalStateException("No configuration row for " + version))
                        : Future.succeededFuture(toConfigRow(rows.iterator().next())));
    }

    /**
     * Stores a new IG set and bumps the revision; notifies on commit.
     */
    public static Future<Long> updateIgSet(SqlClient tx, SupportedFhirVersion version, List<String> included,
                                           List<String> resolved, String key) {
        return tx.preparedQuery("UPDATE " + TABLE + " SET included_ig_packages = $1, resolved_ig_packages = $2, "
                        + "revision = revision + 1, updated_at = now() WHERE validator_id = $3 AND fhir_version = $4 RETURNING revision")
                .execute(Tuple.of(included.toArray(new String[0]), resolved.toArray(new String[0]), VALIDATOR_ID, version.name()))
                .map(rows -> rows.iterator().next().getLong(0))
                .compose(revision -> notify(tx, version, revision, KIND_IG, key).map(revision));
    }

    /**
     * Bumps the revision after a profile change; notifies on commit.
     */
    public static Future<Long> bump(SqlClient tx, SupportedFhirVersion version, String kind, String key) {
        return tx.preparedQuery("UPDATE " + TABLE + " SET revision = revision + 1, updated_at = now() "
                        + "WHERE validator_id = $1 AND fhir_version = $2 RETURNING revision")
                .execute(Tuple.of(VALIDATOR_ID, version.name()))
                .compose(rows -> rows.size() == 0
                        ? Future.failedFuture(new IllegalStateException("No configuration row for " + version))
                        : Future.succeededFuture(rows.iterator().next().getLong(0)))
                .compose(revision -> notify(tx, version, revision, kind, key).map(revision));
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

    private static ConfigRow toConfigRow(Row row) {
        return new ConfigRow(row.getLong(1), array(row.getArrayOfStrings(2)), array(row.getArrayOfStrings(3)));
    }

    private static List<String> array(String[] values) {
        return values == null ? List.of() : Arrays.stream(values).sorted().toList();
    }
}
