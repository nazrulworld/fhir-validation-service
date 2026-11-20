package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.npm.VersionSeries;
import nzi.fhir.validator.core.security.AccessPolicy;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.web.ProblemException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Two IGs named in a validation request's {@code ig}: {@code A#a|B#b}, both exact versions (spec 012 FR-010, FR-011,
 * research R5). Resolution is from memory: the included key {@code A#a|B#b} first, then the alias {@code A#a|B#b} of
 * an included {@code B#b|A#a}; no context is ever built for a request.
 */
public record IgPair(IgReference first, IgReference second) implements IgRequest {

    /**
     * @throws ProblemException 400 {@code invalid-request} unless the value is two exact {@code name#version} values
     *                          of different packages joined by one {@code |}, at most {@value IgReference#MAX_LENGTH}
     *                          characters in all
     */
    public static IgPair parse(String raw) {
        if (raw == null || raw.length() > IgReference.MAX_LENGTH) {
            throw invalid();
        }
        int sep = raw.indexOf(SnapshotKey.MEMBER_SEPARATOR);
        if (sep <= 0 || sep == raw.length() - 1 || raw.indexOf(SnapshotKey.MEMBER_SEPARATOR, sep + 1) >= 0) {
            throw invalid();
        }
        IgReference first = member(raw.substring(0, sep));
        IgReference second = member(raw.substring(sep + 1));
        if (first.name().equals(second.name())) {
            throw new ProblemException(400, "invalid-request", "The two IGs in the ig parameter must be different packages");
        }
        return new IgPair(first, second);
    }

    private static IgReference member(String raw) {
        // A version series keeps its own explanation; anything else malformed gets the pair form's.
        int hash = raw.indexOf('#');
        if (hash >= 0 && VersionSeries.isSeries(raw.substring(hash + 1))) {
            throw IgReference.seriesRefused();
        }
        IgReference ref;
        try {
            ref = IgReference.parse(raw);
        } catch (ProblemException e) {
            throw invalid();
        }
        if (ref.version() == null) {
            throw invalid();
        }
        return ref;
    }

    private static ProblemException invalid() {
        return new ProblemException(400, "invalid-request", "Two IGs in the ig parameter must be name#version|name#version, "
                + "both with exact versions and at most " + IgReference.MAX_LENGTH + " characters in all. URL-encode '#' "
                + "as %23 and '|' as %7C");
    }

    /** {@code A#a|B#b} as requested. */
    @Override
    public String label() {
        return first.label() + SnapshotKey.MEMBER_SEPARATOR + second.label();
    }

    @Override
    public ProblemException notStored() {
        return new ProblemException(404, ProblemException.TYPE_PACKAGE_NOT_FOUND, "IG packages " + label()
                + " are not stored. Register or upload them first via /igs/register or /igs/upload");
    }

    /**
     * Each IG in request order: not stored or not usable by {@code caller} is a miss (the same answer whichever IG it
     * is), another FHIR version is 400. Only then the lookup: key, reversed alias, else 400 "not included".
     */
    @Override
    public Optional<SnapshotKey> resolveIn(SupportedFhirVersion fhirVersion, PackageCatalogView view, Caller caller,
                                           Set<String> included, ContextAliases aliases) {
        List<String> keys = List.of(first.label(), second.label());
        for (String key : keys) {
            if (!view.contains(key) || !AccessPolicy.canUse(caller, view.access(key))) {
                return Optional.empty();
            }
        }
        for (String key : keys) {
            if (!view.supports(key, fhirVersion)) {
                throw new ProblemException(400, "invalid-request", "IG package " + key + " is for FHIR "
                        + view.declaredFhirVersions(key).stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                        + ", not " + fhirVersion.name());
            }
        }
        String requested = label();
        if (included.contains(requested)) {
            return Optional.of(SnapshotKey.of(fhirVersion, requested));
        }
        Optional<String> alias = aliases.target(requested);
        if (alias.isPresent()) {
            return Optional.of(SnapshotKey.of(fhirVersion, alias.get()));
        }
        throw new ProblemException(400, "invalid-request", "IG packages " + requested + " are not included for "
                + fhirVersion + "; include them first (POST /" + fhirVersion + "/validator with igPackages)");
    }
}
