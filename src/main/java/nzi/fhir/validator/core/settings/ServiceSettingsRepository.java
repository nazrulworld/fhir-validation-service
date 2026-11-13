package nzi.fhir.validator.core.settings;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Service-wide settings shared by every instance ({@code service_settings}, V15, spec 009 data-model §2). The calls
 * take a {@link SqlClient} so they run inside the caller's transaction.
 */
public final class ServiceSettingsRepository {
    /** The access-control mode of the most recent start: {@code enabled} or {@code disabled} (spec 009 FR-024). */
    public static final String ACL_SECURITY = "acl_security";

    private static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".service_settings";

    private ServiceSettingsRepository() {
    }

    /** The value of {@code name}, row-locked until the transaction ends; empty when there is no row. Never inserts. */
    public static Future<Optional<String>> lockForUpdate(SqlClient tx, String name) {
        return tx.preparedQuery("SELECT value FROM " + TABLE + " WHERE name = $1 FOR UPDATE")
                .execute(Tuple.of(name))
                .map(rows -> rows.size() == 0 ? Optional.<String>empty() : Optional.of(rows.iterator().next().getString(0)));
    }

    /** Inserts {@code name = value} unless a row exists; whether a row was inserted. */
    public static Future<Boolean> insertIfAbsent(SqlClient tx, String name, String value) {
        return tx.preparedQuery("INSERT INTO " + TABLE + " (name, value) VALUES ($1, $2) ON CONFLICT (name) DO NOTHING")
                .execute(Tuple.of(name, value))
                .map(rows -> rows.rowCount() > 0);
    }

    /** Sets {@code name = value}, recording when and by which instance ({@code updatedBy}, may be null). */
    public static Future<Void> upsert(SqlClient tx, String name, String value, String updatedBy) {
        return tx.preparedQuery("INSERT INTO " + TABLE + " (name, value, updated_at, updated_by) VALUES ($1, $2, now(), $3) "
                        + "ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), updated_by = EXCLUDED.updated_by")
                .execute(Tuple.of(name, value, updatedBy))
                .mapEmpty();
    }
}
