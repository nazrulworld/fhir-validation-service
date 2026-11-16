package nzi.fhir.validator.core.model;

import io.vertx.core.http.HttpServerRequest;
import nzi.fhir.validator.core.terminology.TerminologyMode;
import nzi.fhir.validator.core.terminology.TerminologyOverride;
import nzi.fhir.validator.core.terminology.TerminologySettings;
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
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger(ValidationRequestOptions.class);
    /** Fixed upper bound on distinct requested profiles (FR-006a). */
    public static final int MAX_PROFILES = 10;
    /** Fixed upper bound on the length of one requested profile URL (FR-006a). */
    public static final int MAX_PROFILE_URL_LENGTH = 1024;

    private final List<String> profilesToValidate;
    private final TerminologyOverride terminology;

    public ValidationRequestOptions(List<String> profilesToValidate) {
        this(profilesToValidate, TerminologyOverride.ABSENT);
    }

    public ValidationRequestOptions(List<String> profilesToValidate, TerminologyOverride terminology) {
        this.profilesToValidate = List.copyOf(profilesToValidate);
        this.terminology = terminology == null ? TerminologyOverride.ABSENT : terminology;
    }

    /**
     * @throws ProblemException 400 {@code invalid-request} when the profile list exceeds the fixed limits
     */
    public static ValidationRequestOptions fromRequest(HttpServerRequest request) {
        return new ValidationRequestOptions(parseProfiles(request.getParam("profile")));
    }

    /**
     * The request's {@code tx} and {@code txMode} (spec 010 FR-005, FR-007, contracts §2), from the query string.
     *
     * @throws ProblemException 400 {@code invalid-request} with a fixed text; the address is never echoed
     */
    public static TerminologyOverride terminologyFromRequest(HttpServerRequest request) {
        return parseTerminology(request.params().getAll("tx"), request.params().getAll("txMode"));
    }

    /**
     * Checks, in this order: each parameter at most once; not empty after trimming; {@code txMode} one of
     * {@code none}, {@code server}, {@code ecosystem} (case-sensitive); {@code tx} at most 512 characters and a plain
     * http(s) base URL, normalised like a saved setting; no {@code tx} with {@code txMode=none}. The raw {@code tx} is
     * logged at DEBUG only.
     */
    public static TerminologyOverride parseTerminology(List<String> tx, List<String> txMode) {
        if ((tx != null && tx.size() > 1) || (txMode != null && txMode.size() > 1)) {
            throw invalid("Give tx and txMode at most once each");
        }
        String rawTx = tx == null || tx.isEmpty() ? null : tx.get(0).trim();
        String rawMode = txMode == null || txMode.isEmpty() ? null : txMode.get(0).trim();
        if ((rawTx != null && rawTx.isEmpty()) || (rawMode != null && rawMode.isEmpty())) {
            throw invalid("tx and txMode must not be empty");
        }
        TerminologyMode mode = null;
        if (rawMode != null) {
            try {
                mode = TerminologyMode.fromWire(rawMode);
            } catch (IllegalArgumentException e) {
                throw invalid("txMode must be none, server or ecosystem");
            }
        }
        String server = null;
        if (rawTx != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Validation requested with tx={}", rawTx);
            }
            if (rawTx.length() > TerminologySettings.MAX_SERVER_LENGTH) {
                throw invalid("tx must be at most " + TerminologySettings.MAX_SERVER_LENGTH + " characters");
            }
            try {
                server = TerminologySettings.normaliseUrl(rawTx);
            } catch (IllegalArgumentException e) {
                throw invalid("tx must be an http or https base URL without query, fragment or user info");
            }
        }
        if (mode == TerminologyMode.NONE && server != null) {
            throw invalid("tx is not allowed with txMode=none");
        }
        return new TerminologyOverride(mode, server);
    }

    private static ProblemException invalid(String detail) {
        return new ProblemException(400, "invalid-request", detail);
    }

    /** The request's {@code tx}/{@code txMode} (spec 010). */
    public TerminologyOverride getTerminology() {
        return terminology;
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
