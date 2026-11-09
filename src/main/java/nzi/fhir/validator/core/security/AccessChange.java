package nzi.fhir.validator.core.security;

import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.org.Organisation;
import nzi.fhir.validator.core.terminology.TerminologySettings;
import nzi.fhir.validator.web.ProblemException;

import java.util.Optional;

/**
 * The body of a change to an included context or a stored package ({@code {shareable?, owner?, terminology?}}, spec 005
 * FR-019, contracts §4-5; {@code terminology} since spec 007, included contexts only). Parsed before the change runs,
 * but an invalid body is reported only after the 404 and 403 checks (check order 404 → 403 → 400), so the answer to a
 * caller who may not see the package or context never depends on its body.
 *
 * @param terminology the new terminology setting of an included context (spec 007 FR-003)
 * @param invalid     curated text for 400 {@code invalid-request}, or {@code null} when the body is valid
 */
public record AccessChange(Optional<Boolean> shareable, Optional<String> owner, Optional<TerminologySettings> terminology,
                           String invalid) {
    private static final String NOTHING = "Give shareable, owner, terminology or any of them";

    public AccessChange(Optional<Boolean> shareable, Optional<String> owner, String invalid) {
        this(shareable, owner, Optional.empty(), invalid);
    }

    public static AccessChange of(Optional<Boolean> shareable, Optional<String> owner) {
        return of(shareable, owner, Optional.empty());
    }

    public static AccessChange of(Optional<Boolean> shareable, Optional<String> owner, Optional<TerminologySettings> terminology) {
        return new AccessChange(shareable, owner, terminology,
                shareable.isEmpty() && owner.isEmpty() && terminology.isEmpty() ? NOTHING : null);
    }

    public static AccessChange parse(JsonObject body) {
        if (body == null || body.isEmpty()) {
            return invalid(NOTHING);
        }
        for (String field : body.fieldNames()) {
            if (!field.equals("shareable") && !field.equals("owner") && !field.equals("terminology")) {
                return invalid("Unknown field '" + field + "'");
            }
        }
        Object shareable = body.getValue("shareable");
        Object owner = body.getValue("owner");
        Object terminology = body.getValue("terminology");
        if (shareable != null && !(shareable instanceof Boolean)) {
            return invalid("shareable must be true or false");
        }
        if (owner != null && !(owner instanceof String s && Organisation.NAME_SHORT_PATTERN.matcher(s).matches())) {
            return invalid("owner must be an organisation short name");
        }
        Optional<TerminologySettings> settings = Optional.empty();
        if (terminology != null) {
            if (!(terminology instanceof JsonObject json)) {
                return invalid("terminology must be an object with a mode");
            }
            try {
                settings = Optional.of(TerminologySettings.parse(json));
            } catch (ProblemException e) {
                return invalid(e.detail());
            }
        }
        return of(Optional.ofNullable((Boolean) shareable), Optional.ofNullable((String) owner), settings);
    }

    private static AccessChange invalid(String text) {
        return new AccessChange(Optional.empty(), Optional.empty(), Optional.empty(), text);
    }

    /** Whether the change transfers ownership (system organisation only). */
    public boolean transfers() {
        return owner.isPresent();
    }

    /** Whether the change touches sharing or ownership (as opposed to only the terminology setting). */
    public boolean changesAccess() {
        return shareable.isPresent() || owner.isPresent();
    }
}
