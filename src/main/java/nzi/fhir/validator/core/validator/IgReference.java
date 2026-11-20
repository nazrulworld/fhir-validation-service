package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.npm.PackageVersionComparator;
import nzi.fhir.validator.core.npm.VersionSeries;
import nzi.fhir.validator.core.security.AccessPolicy;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.web.ProblemException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The {@code ig} parameter of a validation request (spec 004 FR-001, FR-002, data-model §2.2): {@code name#version},
 * {@code name#latest} or a bare {@code name}. Parsed before any lookup; resolved to a concrete {@link SnapshotKey}
 * through the in-memory catalog view (no database wait on the hot path). A version series such as {@code 1.2.x} is
 * rejected with 400 (spec 006 FR-018).
 *
 * @param version {@code null} when absent or {@code latest}: the highest included version (spec 007 FR-023)
 */
public record IgReference(String name, String version) implements IgRequest {
    public static final int MAX_LENGTH = 256;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+_-]*");

    /**
     * @throws ProblemException 400 {@code invalid-request} for anything but {@code name}, {@code name#version} or
     *                          {@code name#latest} within {@value #MAX_LENGTH} characters
     */
    public static IgReference parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) {
            throw invalid();
        }
        int hash = raw.indexOf('#');
        String name = hash < 0 ? raw : raw.substring(0, hash);
        String version = hash < 0 ? null : raw.substring(hash + 1);
        if (!NAME.matcher(name).matches()) {
            throw invalid();
        }
        if (version != null) {
            if (version.equalsIgnoreCase("latest")) {
                version = null;
            } else if (VersionSeries.isSeries(version)) {
                // Spec 006 FR-018: a series is resolved by include (POST /{version}/validator) and register only; validation needs the concrete
                // version, so `1.2.x` is a client error here, not a literal version that is answered 404.
                throw seriesRefused();
            } else if (!VERSION.matcher(version).matches()) {
                throw invalid();
            }
        }
        return new IgReference(name, version);
    }

    /** 400 for a version series in {@code ig}: validation needs the exact version a context was included with. */
    static ProblemException seriesRefused() {
        return new ProblemException(400, "invalid-request", "The ig parameter needs an exact version (for example "
                + "the version reported when it was included), not a version series");
    }

    /** The parsed value, or {@code null} when the parameter is absent. */
    public static IgReference parseOptional(String raw) {
        return raw == null ? null : parse(raw);
    }

    private static ProblemException invalid() {
        return new ProblemException(400, "invalid-request", "The ig parameter must be name#version, name#latest or name "
                + "(at most " + MAX_LENGTH + " characters; letters, digits, '.', '_', '-'), or two IGs as "
                + "name#version|name#version. URL-encode '#' as %23 and '|' as %7C");
    }

    /** {@code name#version} or {@code name} (for messages). */
    @Override
    public String label() {
        return version == null ? name : name + "#" + version;
    }

    @Override
    public ProblemException notStored() {
        return ProblemException.igNotStored(label());
    }

    /**
     * Only included contexts serve validation (spec 005 research R5, spec 007 FR-021 to FR-024): a versionless
     * reference becomes the highest <em>included</em> version the caller may use; a stored, usable version that isn't
     * included is refused with 400 (nothing is built on demand). An exact version the caller may not use is treated
     * exactly like one that isn't stored, and the declared FHIR versions are checked before "not included", so no 400
     * ever reveals a package the caller can't see. Spec 012: an exact version that has
     * no single-IG context but is the first IG of an included two-IG context resolves to that context (FR-012); a
     * versionless reference considers single-IG contexts only (FR-012a).
     */
    @Override
    public Optional<SnapshotKey> resolveIn(SupportedFhirVersion fhirVersion, PackageCatalogView view, Caller caller,
                                           Set<String> included, ContextAliases aliases) {
        String concrete = version;
        if (concrete == null) {
            concrete = highestIncluded(view, caller, included)
                    .or(() -> view.highestVisible(name, caller))
                    .orElse(null);
        }
        if (concrete == null) {
            return Optional.empty();
        }
        String key = name + "#" + concrete;
        if (!view.contains(key) || !AccessPolicy.canUse(caller, view.access(key))) {
            return Optional.empty();
        }
        if (!view.supports(key, fhirVersion)) {
            throw new ProblemException(400, "invalid-request", "IG package " + key + " is for FHIR "
                    + view.declaredFhirVersions(key).stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                    + ", not " + fhirVersion.name());
        }
        if (!included.contains(key)) {
            // FR-012: the first-IG alias, only for an exact version (FR-012a) and only without a single-IG context.
            Optional<String> combined = version == null ? Optional.empty() : aliases.target(key);
            if (combined.isPresent()) {
                return Optional.of(SnapshotKey.of(fhirVersion, combined.get()));
            }
            throw ProblemException.igNotIncluded(key, fhirVersion);
        }
        return Optional.of(SnapshotKey.of(fhirVersion, key));
    }

    /** The highest included version of {@code name} that is stored and {@code caller} may use. */
    private Optional<String> highestIncluded(PackageCatalogView view, Caller caller, Set<String> included) {
        String prefix = name + "#";
        List<String> versions = new ArrayList<>();
        for (String key : included) {
            // FR-012a (research R5): a two-IG key A#a|B#b also starts with "A#"; it is never a versionless match.
            if (key.startsWith(prefix) && !SnapshotKey.isCombined(key) && view.contains(key)
                    && AccessPolicy.canUse(caller, view.access(key))) {
                versions.add(key.substring(prefix.length()));
            }
        }
        return PackageVersionComparator.max(versions);
    }
}
