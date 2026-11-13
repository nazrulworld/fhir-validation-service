package nzi.fhir.validator.core.org;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.web.ProblemException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Organisations ({@code organisations}, spec 005, research R2). Organisations are never deleted (FR-005); the system
 * organisation can't be created, deactivated or retyped (FR-003). A short-name change also bumps the package catalog
 * revision and notifies {@code PACKAGE} in the same transaction, so every instance refreshes the owner short names it
 * holds in memory (research R8).
 */
public class OrganisationRepository {
    public static final String TABLE = DB_POSTGRES_SCHEMA_NAME + ".organisations";
    private static final String NAME_SHORT_UNIQUE = "organisations_name_short_uq";
    private static final String COLUMNS = "id, name_short, name_long, type, is_active, address_lines, post_code, country, "
            + "email, phone, website, contact_person_name, contact_person_mobile, contact_person_email, created_at, updated_at";

    /** Input field → column. */
    private static final Map<String, String> COLUMN = new LinkedHashMap<>();

    static {
        COLUMN.put(Organisation.NAME_SHORT, "name_short");
        COLUMN.put(Organisation.NAME_LONG, "name_long");
        COLUMN.put(Organisation.TYPE, "type");
        COLUMN.put(Organisation.ADDRESS_LINES, "address_lines");
        COLUMN.put(Organisation.POST_CODE, "post_code");
        COLUMN.put(Organisation.COUNTRY, "country");
        COLUMN.put(Organisation.EMAIL, "email");
        COLUMN.put(Organisation.PHONE, "phone");
        COLUMN.put(Organisation.WEBSITE, "website");
        COLUMN.put(Organisation.CONTACT_PERSON_NAME, "contact_person_name");
        COLUMN.put(Organisation.CONTACT_PERSON_MOBILE, "contact_person_mobile");
        COLUMN.put(Organisation.CONTACT_PERSON_EMAIL, "contact_person_email");
    }

    private final Pool pool;

    public OrganisationRepository(Pool pool) {
        this.pool = pool;
    }

    /**
     * Makes sure the system and trial organisations exist with their well-known ids (spec 008 FR-025): each is inserted
     * when missing, and an existing row is never changed ({@code FV_SYSTEM_ORG_*} is applied afterwards). Safe when
     * several instances start at once.
     *
     * @return fails with {@link nzi.fhir.validator.core.StartupException} when another organisation already uses a
     * well-known short name
     */
    public Future<Void> ensureWellKnown() {
        return ensure(nzi.fhir.validator.core.Constant.SYSTEM_ORG_UUID, nzi.fhir.validator.core.Constant.SYSTEM_ORG_SHORT_NAME,
                nzi.fhir.validator.core.Constant.SYSTEM_ORG_LONG_NAME, nzi.fhir.validator.core.Constant.SYSTEM_ORG_TYPE)
                .compose(v -> ensure(nzi.fhir.validator.core.Constant.TRIAL_ORG_UUID,
                        nzi.fhir.validator.core.Constant.TRIAL_ORG_SHORT_NAME, nzi.fhir.validator.core.Constant.TRIAL_ORG_LONG_NAME,
                        nzi.fhir.validator.core.Constant.TRIAL_ORG_TYPE));
    }

    private Future<Void> ensure(UUID id, String nameShort, String nameLong, String type) {
        return pool.preparedQuery("INSERT INTO " + TABLE + " (id, name_short, name_long, type, is_active) "
                        + "VALUES ($1, $2, $3, $4, true) ON CONFLICT (id) DO NOTHING")
                .execute(Tuple.of(id, nameShort, nameLong, type))
                .<Void>mapEmpty()
                .recover(err -> {
                    if (isShortNameTaken(err)) {
                        return Future.failedFuture(new nzi.fhir.validator.core.StartupException("An organisation named '"
                                + nameShort + "' already exists with another id; rename it before starting "
                                + "(the well-known '" + nameShort + "' organisation has id " + id + ")"));
                    }
                    return Future.failedFuture(err);
                });
    }

    /**
     * Creates an organisation (always active).
     *
     * @return fails with 400 for an invalid field, 409 for type {@code SYSTEM} or a short name already used in any case
     */
    public Future<Organisation> create(Organisation o) {
        try {
            o.validate();
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(invalid(e));
        }
        if (o.isSystem()) {
            return Future.failedFuture(new ProblemException(409, ProblemException.TYPE_CONFLICT,
                    "There is exactly one system organisation; it can't be created"));
        }
        List<String> fields = new ArrayList<>(COLUMN.keySet());
        String sql = "INSERT INTO " + TABLE + " (" + String.join(", ", COLUMN.values()) + ") VALUES ("
                + placeholders(fields.size(), 1) + ") RETURNING " + COLUMNS;
        return pool.preparedQuery(sql).execute(values(o, fields))
                .map(rows -> toOrganisation(rows.iterator().next()))
                .recover(OrganisationRepository::mapConflict);
    }

    /**
     * Changes only {@code fields} of the organisation {@code id} and sets {@code updated_at}.
     *
     * @return the updated organisation; fails with 404 when it doesn't exist, 400 for an invalid field, 409 for a type
     * change to or from {@code SYSTEM} or a short name already used
     */
    public Future<Organisation> update(UUID id, Organisation patch, Set<String> fields) {
        if (fields.isEmpty()) {
            return Future.failedFuture(new ProblemException(400, "invalid-request", "Nothing to update"));
        }
        try {
            patch.validateFields(fields);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(invalid(e));
        }
        boolean typeChange = fields.contains(Organisation.TYPE);
        if (typeChange && (patch.isSystem() || Organisations.SYSTEM_ID.equals(id))) {
            return Future.failedFuture(new ProblemException(409, ProblemException.TYPE_CONFLICT,
                    "The type of the system organisation can't be changed, and no other organisation can become it"));
        }
        List<String> ordered = COLUMN.keySet().stream().filter(fields::contains).toList();
        StringBuilder set = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            set.append(COLUMN.get(ordered.get(i))).append(" = $").append(i + 2).append(", ");
        }
        String sql = "UPDATE " + TABLE + " SET " + set + "updated_at = now() WHERE id = $1 RETURNING " + COLUMNS;
        Tuple params = Tuple.of(id);
        Tuple fieldValues = values(patch, ordered);
        for (int i = 0; i < fieldValues.size(); i++) {
            params.addValue(fieldValues.getValue(i));
        }
        boolean rename = fields.contains(Organisation.NAME_SHORT);
        return pool.withTransaction(tx -> tx.preparedQuery(sql).execute(params).compose(rows -> {
                    if (rows.size() == 0) {
                        return Future.failedFuture(notFound());
                    }
                    Organisation updated = toOrganisation(rows.iterator().next());
                    // Owner short names are held in every instance's catalog view: refresh them (research R8).
                    return rename ? PostgresPackageCacheManager.bumpCatalog(tx, null).map(updated) : Future.succeededFuture(updated);
                }))
                .recover(OrganisationRepository::mapConflict);
    }

    /**
     * Activates or deactivates an organisation. Its keys follow within the auth-cache window (FR-014).
     *
     * @return fails with 404 when it doesn't exist, 409 when deactivating the system organisation
     */
    public Future<Organisation> setActive(UUID id, boolean active) {
        if (!active && Organisations.SYSTEM_ID.equals(id)) {
            return Future.failedFuture(new ProblemException(409, ProblemException.TYPE_CONFLICT,
                    "The system organisation can't be deactivated"));
        }
        return pool.preparedQuery("UPDATE " + TABLE + " SET is_active = $2, updated_at = now() WHERE id = $1 RETURNING " + COLUMNS)
                .execute(Tuple.of(id, active))
                .compose(rows -> rows.size() == 0 ? Future.failedFuture(notFound())
                        : Future.succeededFuture(toOrganisation(rows.iterator().next())));
    }

    public Future<Optional<Organisation>> findById(UUID id) {
        return findById(pool, id);
    }

    public static Future<Optional<Organisation>> findById(SqlClient client, UUID id) {
        return client.preparedQuery("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.size() == 0 ? Optional.empty() : Optional.of(toOrganisation(rows.iterator().next())));
    }

    /** Case-insensitive, like the uniqueness rule. */
    public Future<Optional<Organisation>> findByShortName(String nameShort) {
        return findByShortName(pool, nameShort);
    }

    public static Future<Optional<Organisation>> findByShortName(SqlClient client, String nameShort) {
        if (nameShort == null) {
            return Future.succeededFuture(Optional.empty());
        }
        return client.preparedQuery("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE lower(name_short) = lower($1)")
                .execute(Tuple.of(nameShort))
                .map(rows -> rows.size() == 0 ? Optional.empty() : Optional.of(toOrganisation(rows.iterator().next())));
    }

    /** All organisations, or only active or inactive ones, ordered by short name. */
    public Future<List<Organisation>> list(Optional<Boolean> active) {
        String where = active.isPresent() ? " WHERE is_active = $1" : "";
        Tuple params = active.map(Tuple::of).orElseGet(Tuple::tuple);
        return pool.preparedQuery("SELECT " + COLUMNS + " FROM " + TABLE + where + " ORDER BY lower(name_short)")
                .execute(params)
                .map(rows -> {
                    List<Organisation> out = new ArrayList<>();
                    rows.forEach(r -> out.add(toOrganisation(r)));
                    return out;
                });
    }

    /**
     * Applies the configured system-organisation fields ({@code FV_SYSTEM_ORG_*}, FR-004): only the given fields
     * change. Type and active state can't be configured.
     *
     * @param fields input field name → value ({@code String}, or a {@code List<String>} for address lines)
     * @return the system organisation after the change
     */
    public Future<Organisation> applySystemConfig(Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return findById(Organisations.SYSTEM_ID).map(o -> o.orElseThrow(OrganisationRepository::notFound));
        }
        Organisation patch = Organisation.empty();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (Organisation.TYPE.equals(e.getKey())) {
                return Future.failedFuture(new IllegalArgumentException("The system organisation's type can't be configured"));
            }
            patch = e.getValue() instanceof List<?> lines
                    ? patch.withAddressLines(lines.stream().map(String::valueOf).toList())
                    : patch.with(e.getKey(), (String) e.getValue());
        }
        return update(Organisations.SYSTEM_ID, patch, fields.keySet());
    }

    /** A unique violation of the short name (shared by {@link #ensureWellKnown} and {@link #mapConflict}). */
    private static boolean isShortNameTaken(Throwable err) {
        return nzi.fhir.validator.core.PgErrors.isUniqueViolation(err, NAME_SHORT_UNIQUE);
    }

    // --- mapping ------------------------------------------------------------------------------------------------

    static Organisation toOrganisation(Row r) {
        String[] lines = r.getArrayOfStrings("address_lines");
        OffsetDateTime created = r.getOffsetDateTime("created_at");
        OffsetDateTime updated = r.getOffsetDateTime("updated_at");
        String country = r.getString("country");
        return new Organisation(r.getUUID("id"), r.getString("name_short"), r.getString("name_long"),
                Organisations.Type.valueOf(r.getString("type")), r.getBoolean("is_active"),
                lines == null ? List.of() : List.of(lines), r.getString("post_code"), country == null ? null : country.trim(),
                r.getString("email"), r.getString("phone"), r.getString("website"), r.getString("contact_person_name"),
                r.getString("contact_person_mobile"), r.getString("contact_person_email"),
                created == null ? null : created.toInstant(), updated == null ? null : updated.toInstant());
    }

    private static Tuple values(Organisation o, List<String> fields) {
        Tuple t = Tuple.tuple();
        for (String field : fields) {
            Object v = o.get(field);
            t.addValue(v instanceof List<?> lines ? lines.toArray(String[]::new) : v);
        }
        return t;
    }

    private static String placeholders(int count, int first) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "" : ", ").append('$').append(first + i);
        }
        return sb.toString();
    }

    private static <T> Future<T> mapConflict(Throwable err) {
        if (isShortNameTaken(err)) {
            return Future.failedFuture(new ProblemException(409, ProblemException.TYPE_CONFLICT,
                    "An organisation with this short name already exists (names are compared ignoring case)"));
        }
        return Future.failedFuture(err);
    }

    private static ProblemException invalid(IllegalArgumentException e) {
        return new ProblemException(400, "invalid-request", e.getMessage());
    }

    public static ProblemException notFound() {
        return new ProblemException(404, "not-found", "Organisation not found");
    }
}
