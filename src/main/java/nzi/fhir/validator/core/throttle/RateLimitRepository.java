package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * The stored caps of keys and organisations, for the {@code limit} command line (spec 014 FR-013, data-model §1).
 * Reads and writes {@code rate_limit_per_minute}; the two defaults live in {@code service_settings}
 * ({@link nzi.fhir.validator.core.settings.ServiceSettingsRepository}).
 */
public final class RateLimitRepository {
    private static final String KEYS = DB_POSTGRES_SCHEMA_NAME + ".api_keys";
    private static final String ORGS = DB_POSTGRES_SCHEMA_NAME + ".organisations";

    /** A key or organisation whose cap is read or changed: its id, name, owner (keys), and cap ({@code null}: none). */
    public record Target(UUID id, String name, UUID owner, Integer perMinute) {
    }

    /** A key as {@code limit list} shows it. */
    public record KeyRow(UUID id, String name, String role, UUID owner, boolean revoked, Integer perMinute) {
    }

    /** An organisation as {@code limit list} shows it. */
    public record OrgRow(UUID id, String name, Integer perMinute) {
    }

    private RateLimitRepository() {
    }

    /** The key {@code name}, row-locked until the transaction ends. */
    public static Future<Optional<Target>> lockKey(SqlClient tx, String name) {
        return tx.preparedQuery("SELECT id, name, owner, rate_limit_per_minute FROM " + KEYS + " WHERE name = $1 FOR UPDATE")
                .execute(Tuple.of(name)).map(rows -> rows.size() == 0 ? Optional.empty() : Optional.of(target(rows.iterator().next())));
    }

    /** The organisation with short name {@code name} (any case), row-locked until the transaction ends. */
    public static Future<Optional<Target>> lockOrganisation(SqlClient tx, String name) {
        return tx.preparedQuery("SELECT id, name_short, id, rate_limit_per_minute FROM " + ORGS
                        + " WHERE lower(name_short) = lower($1) FOR UPDATE")
                .execute(Tuple.of(name)).map(rows -> rows.size() == 0 ? Optional.empty() : Optional.of(target(rows.iterator().next())));
    }

    private static Target target(Row row) {
        return new Target(row.getUUID(0), row.getString(1), row.getUUID(2), row.getInteger(3));
    }

    /** Sets (or with {@code null} removes) the cap of key {@code id}. */
    public static Future<Void> setKey(SqlClient tx, UUID id, Integer perMinute) {
        return tx.preparedQuery("UPDATE " + KEYS + " SET rate_limit_per_minute = $2 WHERE id = $1")
                .execute(Tuple.of(id, perMinute)).mapEmpty();
    }

    /** Sets (or with {@code null} removes) the cap of organisation {@code id}. */
    public static Future<Void> setOrganisation(SqlClient tx, UUID id, Integer perMinute) {
        return tx.preparedQuery("UPDATE " + ORGS + " SET rate_limit_per_minute = $2, updated_at = now() WHERE id = $1")
                .execute(Tuple.of(id, perMinute)).mapEmpty();
    }

    /** Every organisation, or the one named {@code name}, ordered by short name. */
    public static Future<List<OrgRow>> organisations(SqlClient client, String name) {
        String sql = "SELECT id, name_short, rate_limit_per_minute FROM " + ORGS
                + (name == null ? "" : " WHERE lower(name_short) = lower($1)") + " ORDER BY lower(name_short)";
        Future<io.vertx.sqlclient.RowSet<Row>> rows = name == null ? client.query(sql).execute()
                : client.preparedQuery(sql).execute(Tuple.of(name));
        return rows.map(rs -> {
            List<OrgRow> out = new ArrayList<>();
            rs.forEach(r -> out.add(new OrgRow(r.getUUID(0), r.getString(1), r.getInteger(2))));
            return out;
        });
    }

    /** Every key, ordered by name. */
    public static Future<List<KeyRow>> keys(SqlClient client) {
        return client.query("SELECT id, name, role, owner, revoked_at IS NOT NULL, rate_limit_per_minute FROM " + KEYS
                + " ORDER BY name").execute().map(rs -> {
            List<KeyRow> out = new ArrayList<>();
            rs.forEach(r -> out.add(new KeyRow(r.getUUID(0), r.getString(1), r.getString(2), r.getUUID(3), r.getBoolean(4),
                    r.getInteger(5))));
            return out;
        });
    }
}
