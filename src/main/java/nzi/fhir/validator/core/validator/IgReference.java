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
public record IgReference(String name, String version) {
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
                // Spec 006 FR-018: a series is resolved by include-ig and register only; validation needs the concrete
                // version, so `1.2.x` is a client error here, not a literal version that is answered 404.
                throw new ProblemException(400, "invalid-request", "The ig parameter needs an exact version (for example "
                        + "the version reported by include-ig), not a version series");
            } else if (!VERSION.matcher(version).matches()) {
                throw invalid();
            }
        }
        return new IgReference(name, version);
    }

    /** The parsed value, or {@code null} when the parameter is absent. */
    public static IgReference parseOptional(String raw) {
        return raw == null ? null : parse(raw);
    }

    private static ProblemException invalid() {
        return new ProblemException(400, "invalid-request", "The ig parameter must be name#version, name#latest or name "
                + "(at most " + MAX_LENGTH + " characters; letters, digits, '.', '_', '-'; one IG per request). "
                + "URL-encode '#' as %23");
    }

    /** {@code name#version} or {@code name} (for messages). */
    public String label() {
        return version == null ? name : name + "#" + version;
    }

    /**
     * The validator context for this reference and {@code fhirVersion}, as {@code caller} may use it (spec 005 research
     * R5, spec 007 FR-021 to FR-023). Only included contexts serve validation: a versionless reference becomes the
     * highest <em>included</em> version the caller may use; a stored, usable version that isn't included is refused
     * with 400 (it would otherwise be built on demand, which 4.0.0 removed). An exact version the caller may not use is
     * treated exactly like one that isn't stored, and the declared FHIR versions are checked before "not included"
     * (FR-024), so no 400 ever reveals a package the caller can't see. A package the view doesn't know (or doesn't let
     * the caller see) triggers one rate-limited catalog refresh before the request is rejected (spec 004 research R4),
     * so the timing matches "not stored".
     *
     * @param included the {@code name#version} keys included for {@code fhirVersion} (pinned contexts)
     * @return fails with 404 {@code package-not-found} or 400 {@code invalid-request}
     */
    public Future<SnapshotKey> resolve(SupportedFhirVersion fhirVersion, PackageCatalog catalog, Caller caller,
                                       Set<String> included) {
        Optional<SnapshotKey> known;
        try {
            known = resolveIn(fhirVersion, catalog.current(), caller, included);
        } catch (ProblemException e) {
            return Future.failedFuture(e);
        }
        if (known.isPresent()) {
            return Future.succeededFuture(known.get());
        }
        return catalog.refreshOnMiss()
                .recover(err -> Future.succeededFuture(catalog.current()))
                .compose(view -> {
                    try {
                        return resolveIn(fhirVersion, view, caller, included)
                                .map(Future::succeededFuture)
                                .orElseGet(() -> Future.failedFuture(ProblemException.igNotStored(label())));
                    } catch (ProblemException e) {
                        return Future.failedFuture(e);
                    }
                });
    }

    /**
     * @param included the {@code name#version} keys included for {@code fhirVersion}
     * @return empty when the package (or, versionless, any version of it) is not in {@code view} or {@code caller}
     * may not use it
     * @throws ProblemException 400 when the resolved package does not support {@code fhirVersion}, or (spec 007
     *                          FR-022) when it is stored and usable but not included
     */
    Optional<SnapshotKey> resolveIn(SupportedFhirVersion fhirVersion, PackageCatalogView view, Caller caller,
                                    Set<String> included) {
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
            throw ProblemException.igNotIncluded(key, fhirVersion);
        }
        return Optional.of(SnapshotKey.of(fhirVersion, key));
    }

    /** The highest included version of {@code name} that is stored and {@code caller} may use. */
    private Optional<String> highestIncluded(PackageCatalogView view, Caller caller, Set<String> included) {
        String prefix = name + "#";
        List<String> versions = new ArrayList<>();
        for (String key : included) {
            if (key.startsWith(prefix) && view.contains(key) && AccessPolicy.canUse(caller, view.access(key))) {
                versions.add(key.substring(prefix.length()));
            }
        }
        return PackageVersionComparator.max(versions);
    }
}
