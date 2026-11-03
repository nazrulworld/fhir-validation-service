package nzi.fhir.validator.core.model;

import io.vertx.core.http.HttpServerRequest;
import nzi.fhir.validator.web.ProblemException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Options of one validation request, taken from the query string.
 *
 * @author Md Nazrul Islam
 */
public class ValidationRequestOptions {
    /** Fixed upper bound on distinct requested profiles (FR-006a). */
    public static final int MAX_PROFILES = 10;
    /** Fixed upper bound on the length of one requested profile URL (FR-006a). */
    public static final int MAX_PROFILE_URL_LENGTH = 1024;

    private final List<String> profilesToValidate;

    public ValidationRequestOptions(List<String> profilesToValidate) {
        this.profilesToValidate = List.copyOf(profilesToValidate);
    }

    /**
     * @throws ProblemException 400 {@code invalid-request} when the profile list exceeds the fixed limits
     */
    public static ValidationRequestOptions fromRequest(HttpServerRequest request) {
        return new ValidationRequestOptions(parseProfiles(request.getParam("profile")));
    }

    /**
     * Splits on commas, trims, drops empty entries and removes duplicates (first occurrence wins), then enforces
     * "at most 10 entries after parsing, each at most 1024 characters" (FR-006a).
     *
     * @return an immutable list in request order
     * @throws ProblemException 400 {@code invalid-request}; the URLs are not echoed
     */
    public static List<String> parseProfiles(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> profiles = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String profile = part.trim();
            if (profile.isEmpty()) {
                continue;
            }
            if (profile.length() > MAX_PROFILE_URL_LENGTH) {
                throw ProblemException.profileLimits();
            }
            profiles.add(profile);
            if (profiles.size() > MAX_PROFILES) {
                throw ProblemException.profileLimits();
            }
        }
        return List.copyOf(profiles);
    }

    public List<String> getProfilesToValidate() {
        return profilesToValidate;
    }
}
