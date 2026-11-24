package nzi.fhir.validator.core.throttle;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.settings.ServiceSettingsRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Reads every stored cap in one query (spec 014, research R8): the keys and organisations with a cap of their own, and
 * the two default rows of {@code service_settings}. Only rows with a cap are read, so the snapshot stays small.
 */
public final class ThrottleCapsRepository {
    static final String LOAD = "SELECT 'K', id, rate_limit_per_minute FROM " + DB_POSTGRES_SCHEMA_NAME + ".api_keys "
            + "WHERE rate_limit_per_minute IS NOT NULL "
            + "UNION ALL SELECT 'O', id, rate_limit_per_minute FROM " + DB_POSTGRES_SCHEMA_NAME + ".organisations "
            + "WHERE rate_limit_per_minute IS NOT NULL "
            // The WHERE runs before the select list, so value::int only sees the two throttle rows (the baseline's service_settings_throttle_chk).
            + "UNION ALL SELECT name, NULL::uuid, value::int FROM " + DB_POSTGRES_SCHEMA_NAME + ".service_settings "
            + "WHERE name = ANY($1)";

    private ThrottleCapsRepository() {
    }

    public static Future<ThrottleCaps> load(SqlClient client) {
        String[] names = {ServiceSettingsRepository.THROTTLE_DEFAULT_KEY, ServiceSettingsRepository.THROTTLE_DEFAULT_ORG};
        return client.preparedQuery(LOAD).execute(Tuple.of((Object) names)).map(rows -> {
            Map<UUID, Integer> keys = new HashMap<>();
            Map<UUID, Integer> orgs = new HashMap<>();
            OptionalInt defaultKey = OptionalInt.empty();
            OptionalInt defaultOrg = OptionalInt.empty();
            for (Row row : rows) {
                String kind = row.getString(0);
                int cap = row.getInteger(2);
                switch (kind) {
                    case "K" -> keys.put(row.getUUID(1), cap);
                    case "O" -> orgs.put(row.getUUID(1), cap);
                    case ServiceSettingsRepository.THROTTLE_DEFAULT_KEY -> defaultKey = OptionalInt.of(cap);
                    case ServiceSettingsRepository.THROTTLE_DEFAULT_ORG -> defaultOrg = OptionalInt.of(cap);
                    default -> {
                    }
                }
            }
            return new ThrottleCaps(keys, orgs, defaultKey, defaultOrg, Instant.now());
        });
    }
}
