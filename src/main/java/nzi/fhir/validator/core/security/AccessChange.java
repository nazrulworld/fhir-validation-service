package nzi.fhir.validator.core.security;

import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.org.Organisation;

import java.util.Optional;

/**
 * The body of an access change ({@code {shareable?, owner?}}, spec 005 FR-019, contracts §4-5). Parsed before the
 * change runs, but an invalid body is reported only after the 404 and 403 checks (check order 404 → 403 → 400), so the
 * answer to a caller who may not see the package or context never depends on its body.
 *
 * @param invalid curated text for 400 {@code invalid-request}, or {@code null} when the body is valid
 */
public record AccessChange(Optional<Boolean> shareable, Optional<String> owner, String invalid) {

    public static AccessChange of(Optional<Boolean> shareable, Optional<String> owner) {
        return new AccessChange(shareable, owner, shareable.isEmpty() && owner.isEmpty()
                ? "Give shareable, owner or both" : null);
    }

    public static AccessChange parse(JsonObject body) {
        if (body == null || body.isEmpty()) {
            return new AccessChange(Optional.empty(), Optional.empty(), "Give shareable, owner or both");
        }
        for (String field : body.fieldNames()) {
            if (!field.equals("shareable") && !field.equals("owner")) {
                return new AccessChange(Optional.empty(), Optional.empty(), "Unknown field '" + field + "'");
            }
        }
        Object shareable = body.getValue("shareable");
        Object owner = body.getValue("owner");
        if (shareable != null && !(shareable instanceof Boolean)) {
            return new AccessChange(Optional.empty(), Optional.empty(), "shareable must be true or false");
        }
        if (owner != null && !(owner instanceof String s && Organisation.NAME_SHORT_PATTERN.matcher(s).matches())) {
            return new AccessChange(Optional.empty(), Optional.empty(), "owner must be an organisation short name");
        }
        return of(Optional.ofNullable((Boolean) shareable), Optional.ofNullable((String) owner));
    }

    /** Whether the change transfers ownership (system organisation only). */
    public boolean transfers() {
        return owner.isPresent();
    }
}
