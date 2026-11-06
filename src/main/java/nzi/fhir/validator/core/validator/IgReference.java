package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.web.ProblemException;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The {@code ig} parameter of a validation request (spec 004 FR-001, FR-002, data-model §2.2): {@code name#version},
 * {@code name#latest} or a bare {@code name}. Parsed before any lookup; resolved to a concrete {@link SnapshotKey}
 * through the in-memory catalog view (no database wait on the hot path).
 *
 * @param version {@code null} when absent or {@code latest}: the highest stored version
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
     * The validator context for this reference and {@code fhirVersion}: a versionless reference becomes the highest
     * stored version (the include rule, FR-002), then the declared FHIR versions are checked (FR-004). A package the
     * view doesn't know triggers one rate-limited catalog refresh before the request is rejected (research R4).
     *
     * @return fails with 404 {@code package-not-found} or 400 {@code invalid-request}
     */
    public Future<SnapshotKey> resolve(SupportedFhirVersion fhirVersion, PackageCatalog catalog) {
        Optional<SnapshotKey> known;
        try {
            known = resolveIn(fhirVersion, catalog.current());
        } catch (ProblemException e) {
            return Future.failedFuture(e);
        }
        if (known.isPresent()) {
            return Future.succeededFuture(known.get());
        }
        return catalog.refreshOnMiss()
                .recover(err -> Future.succeededFuture(catalog.current()))
                .compose(view -> resolveIn(fhirVersion, view)
                        .map(Future::succeededFuture)
                        .orElseGet(() -> Future.failedFuture(ProblemException.igNotStored(label()))));
    }

    /**
     * @return empty when the package (or, versionless, any version of it) is not in {@code view}
     * @throws ProblemException 400 when the resolved package does not support {@code fhirVersion}
     */
    Optional<SnapshotKey> resolveIn(SupportedFhirVersion fhirVersion, PackageCatalogView view) {
        String concrete = version != null ? version : view.highest(name).orElse(null);
        if (concrete == null || !view.contains(name + "#" + concrete)) {
            return Optional.empty();
        }
        String key = name + "#" + concrete;
        if (!view.supports(key, fhirVersion)) {
            throw new ProblemException(400, "invalid-request", "IG package " + key + " is for FHIR "
                    + view.declaredFhirVersions(key).stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                    + ", not " + fhirVersion.name());
        }
        return Optional.of(SnapshotKey.of(fhirVersion, key));
    }
}
