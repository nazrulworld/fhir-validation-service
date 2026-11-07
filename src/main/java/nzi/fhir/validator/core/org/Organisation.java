package nzi.fhir.validator.core.org;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An organisation (spec 005, data-model §1.1): owns API keys, IG packages and included validator contexts. JSON uses
 * camelCase field names (contracts/cli.md). Optional fields are {@code null} when not set; {@code addressLines} is
 * never {@code null}.
 */
public record Organisation(UUID id, String nameShort, String nameLong, Organisations.Type type, boolean active,
                           List<String> addressLines, String postCode, String country, String email, String phone,
                           String website, String contactPersonName, String contactPersonMobile,
                           String contactPersonEmail, Instant createdAt, Instant updatedAt) {

    public static final String NAME_SHORT = "nameShort";
    public static final String NAME_LONG = "nameLong";
    public static final String TYPE = "type";
    public static final String ADDRESS_LINES = "addressLines";
    public static final String POST_CODE = "postCode";
    public static final String COUNTRY = "country";
    public static final String EMAIL = "email";
    public static final String PHONE = "phone";
    public static final String WEBSITE = "website";
    public static final String CONTACT_PERSON_NAME = "contactPersonName";
    public static final String CONTACT_PERSON_MOBILE = "contactPersonMobile";
    public static final String CONTACT_PERSON_EMAIL = "contactPersonEmail";

    /** Fields an operator may set (create, update, system-organisation configuration). */
    public static final List<String> INPUT_FIELDS = List.of(NAME_SHORT, NAME_LONG, TYPE, ADDRESS_LINES, POST_CODE,
            COUNTRY, EMAIL, PHONE, WEBSITE, CONTACT_PERSON_NAME, CONTACT_PERSON_MOBILE, CONTACT_PERSON_EMAIL);

    /** Fields that appear in output only; ignored when read from a file. */
    public static final List<String> OUTPUT_ONLY_FIELDS = List.of("id", "active", "createdAt", "updatedAt", "keys");

    public static final Pattern NAME_SHORT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,32}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[+0-9 ()-]{3,32}$");
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Z]{2}$");
    /** An address shape check, not RFC 5322: one {@code @}, a dotted domain, no whitespace. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    static final int MAX_NAME_LONG = 256;
    static final int MAX_ADDRESS_LINES = 6;
    static final int MAX_ADDRESS_LINE = 128;
    static final int MAX_POST_CODE = 16;
    static final int MAX_EMAIL = 254;
    static final int MAX_WEBSITE = 256;
    static final int MAX_CONTACT_NAME = 128;

    public Organisation {
        addressLines = addressLines == null ? List.of() : List.copyOf(addressLines);
    }

    /** An organisation with only the given input fields (the rest empty), for building patches and new rows. */
    public static Organisation empty() {
        return new Organisation(null, null, null, null, true, List.of(), null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * Checks every rule of data-model §1.1.
     *
     * @throws IllegalArgumentException naming the offending field
     */
    public Organisation validate() {
        validateFields(INPUT_FIELDS);
        return this;
    }

    /**
     * Checks the rules of {@code fields} only (an update or the system-organisation configuration changes a subset).
     *
     * @throws IllegalArgumentException naming the offending field
     */
    public void validateFields(java.util.Collection<String> fields) {
        for (String field : fields) {
            switch (field) {
                case NAME_SHORT -> {
                    if (nameShort == null || !NAME_SHORT_PATTERN.matcher(nameShort).matches()) {
                        throw invalid(field, "must be 2-32 characters of letters, digits, '_' or '-'");
                    }
                }
                case NAME_LONG -> {
                    if (nameLong == null || nameLong.isBlank() || nameLong.length() > MAX_NAME_LONG
                            || !nameLong.equals(nameLong.strip())) {
                        throw invalid(field, "must be 1-" + MAX_NAME_LONG + " characters without leading or trailing spaces");
                    }
                }
                case TYPE -> {
                    if (type == null) {
                        throw invalid(field, "is required (COMMERCIAL or NON_COMMERCIAL)");
                    }
                }
                case ADDRESS_LINES -> {
                    if (addressLines.size() > MAX_ADDRESS_LINES) {
                        throw invalid(field, "must have at most " + MAX_ADDRESS_LINES + " lines");
                    }
                    for (String line : addressLines) {
                        if (line == null || line.isBlank() || line.length() > MAX_ADDRESS_LINE) {
                            throw invalid(field, "lines must be 1-" + MAX_ADDRESS_LINE + " characters");
                        }
                    }
                }
                case POST_CODE -> maxLength(field, postCode, MAX_POST_CODE);
                case COUNTRY -> {
                    if (country != null && (!COUNTRY_PATTERN.matcher(country).matches() || !ISO_COUNTRIES.contains(country))) {
                        throw invalid(field, "must be an ISO 3166-1 alpha-2 code such as DK");
                    }
                }
                case EMAIL -> email(field, email);
                case CONTACT_PERSON_EMAIL -> email(field, contactPersonEmail);
                case PHONE -> phone(field, phone);
                case CONTACT_PERSON_MOBILE -> phone(field, contactPersonMobile);
                case WEBSITE -> website(field, website);
                case CONTACT_PERSON_NAME -> maxLength(field, contactPersonName, MAX_CONTACT_NAME);
                default -> throw new IllegalArgumentException("Unknown organisation field '" + field + "'");
            }
        }
    }

    private static void maxLength(String field, String value, int max) {
        if (value != null && (value.isBlank() || value.length() > max)) {
            throw invalid(field, "must be 1-" + max + " characters");
        }
    }

    private static void email(String field, String value) {
        if (value != null && (value.length() > MAX_EMAIL || !EMAIL_PATTERN.matcher(value).matches())) {
            throw invalid(field, "must be an email address of at most " + MAX_EMAIL + " characters");
        }
    }

    private static void phone(String field, String value) {
        if (value != null && !PHONE_PATTERN.matcher(value).matches()) {
            throw invalid(field, "must be 3-32 characters of digits, spaces, '+', '(', ')' or '-'");
        }
    }

    private static void website(String field, String value) {
        if (value == null) {
            return;
        }
        boolean ok = value.length() <= MAX_WEBSITE;
        if (ok) {
            try {
                URI uri = new URI(value);
                ok = uri.isAbsolute() && uri.getHost() != null
                        && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
            } catch (URISyntaxException e) {
                ok = false;
            }
        }
        if (!ok) {
            throw invalid(field, "must be an absolute http or https URL of at most " + MAX_WEBSITE + " characters");
        }
    }

    private static IllegalArgumentException invalid(String field, String rule) {
        return new IllegalArgumentException("Invalid " + field + ": " + rule);
    }

    public boolean isSystem() {
        return type == Organisations.Type.SYSTEM;
    }

    /** Output form (contracts/cli.md): every field, {@code null} for unset optional ones. */
    public JsonObject toJson() {
        return new JsonObject()
                .put("id", id == null ? null : id.toString())
                .put(NAME_SHORT, nameShort)
                .put(NAME_LONG, nameLong)
                .put(TYPE, type == null ? null : type.name())
                .put("active", active)
                .put(ADDRESS_LINES, new JsonArray(new ArrayList<>(addressLines)))
                .put(POST_CODE, postCode)
                .put(COUNTRY, country)
                .put(EMAIL, email)
                .put(PHONE, phone)
                .put(WEBSITE, website)
                .put(CONTACT_PERSON_NAME, contactPersonName)
                .put(CONTACT_PERSON_MOBILE, contactPersonMobile)
                .put(CONTACT_PERSON_EMAIL, contactPersonEmail)
                .put("createdAt", createdAt == null ? null : createdAt.toString())
                .put("updatedAt", updatedAt == null ? null : updatedAt.toString());
    }

    /** An input document and the input fields it set. */
    public record Input(Organisation organisation, Set<String> fields) {
    }

    /**
     * Reads an input document (a CLI {@code --file}). Output-only fields are ignored.
     *
     * @throws IllegalArgumentException for an unknown field or a value of the wrong JSON type
     */
    public static Input fromJson(JsonObject json) {
        Set<String> fields = new LinkedHashSet<>();
        for (String name : json.fieldNames()) {
            if (INPUT_FIELDS.contains(name)) {
                fields.add(name);
            } else if (!OUTPUT_ONLY_FIELDS.contains(name)) {
                throw new IllegalArgumentException("Unknown organisation field '" + name + "'");
            }
        }
        Organisation o = empty();
        for (String field : fields) {
            Object value = json.getValue(field);
            o = field.equals(ADDRESS_LINES) ? o.withAddressLines(lines(value)) : o.with(field, text(field, value));
        }
        return new Input(o, fields);
    }

    private static String text(String field, Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("Invalid " + field + ": must be a string");
    }

    private static List<String> lines(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof JsonArray array)) {
            throw new IllegalArgumentException("Invalid " + ADDRESS_LINES + ": must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object line : array) {
            if (!(line instanceof String s)) {
                throw new IllegalArgumentException("Invalid " + ADDRESS_LINES + ": must be an array of strings");
            }
            out.add(s);
        }
        return out;
    }

    /**
     * A copy with one text field replaced ({@code null} or empty clears an optional field).
     *
     * @throws IllegalArgumentException for an unknown field or an unknown type value
     */
    public Organisation with(String field, String value) {
        String v = value == null || value.isEmpty() ? null : value;
        return switch (field) {
            case NAME_SHORT -> new Organisation(id, v, nameLong, type, active, addressLines, postCode, country, email, phone,
                    website, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case NAME_LONG -> new Organisation(id, nameShort, v, type, active, addressLines, postCode, country, email, phone,
                    website, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case TYPE -> new Organisation(id, nameShort, nameLong, parseType(v), active, addressLines, postCode, country, email,
                    phone, website, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case POST_CODE -> new Organisation(id, nameShort, nameLong, type, active, addressLines, v, country, email, phone,
                    website, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case COUNTRY -> new Organisation(id, nameShort, nameLong, type, active, addressLines, postCode, v, email, phone,
                    website, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case EMAIL -> new Organisation(id, nameShort, nameLong, type, active, addressLines, postCode, country, v, phone,
                    website, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case PHONE -> new Organisation(id, nameShort, nameLong, type, active, addressLines, postCode, country, email, v,
                    website, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case WEBSITE -> new Organisation(id, nameShort, nameLong, type, active, addressLines, postCode, country, email,
                    phone, v, contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case CONTACT_PERSON_NAME -> new Organisation(id, nameShort, nameLong, type, active, addressLines, postCode, country,
                    email, phone, website, v, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
            case CONTACT_PERSON_MOBILE -> new Organisation(id, nameShort, nameLong, type, active, addressLines, postCode,
                    country, email, phone, website, contactPersonName, v, contactPersonEmail, createdAt, updatedAt);
            case CONTACT_PERSON_EMAIL -> new Organisation(id, nameShort, nameLong, type, active, addressLines, postCode,
                    country, email, phone, website, contactPersonName, contactPersonMobile, v, createdAt, updatedAt);
            case ADDRESS_LINES -> withAddressLines(v == null ? List.of() : List.of(v.split("\\|", -1)));
            default -> throw new IllegalArgumentException("Unknown organisation field '" + field + "'");
        };
    }

    public Organisation withAddressLines(List<String> lines) {
        return new Organisation(id, nameShort, nameLong, type, active, lines, postCode, country, email, phone, website,
                contactPersonName, contactPersonMobile, contactPersonEmail, createdAt, updatedAt);
    }

    /** The value of an input field (address lines as a {@code List}). */
    public Object get(String field) {
        return switch (field) {
            case NAME_SHORT -> nameShort;
            case NAME_LONG -> nameLong;
            case TYPE -> type == null ? null : type.name();
            case ADDRESS_LINES -> addressLines;
            case POST_CODE -> postCode;
            case COUNTRY -> country;
            case EMAIL -> email;
            case PHONE -> phone;
            case WEBSITE -> website;
            case CONTACT_PERSON_NAME -> contactPersonName;
            case CONTACT_PERSON_MOBILE -> contactPersonMobile;
            case CONTACT_PERSON_EMAIL -> contactPersonEmail;
            default -> throw new IllegalArgumentException("Unknown organisation field '" + field + "'");
        };
    }

    private static Organisations.Type parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Organisations.Type.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + TYPE + ": must be COMMERCIAL or NON_COMMERCIAL");
        }
    }
}
