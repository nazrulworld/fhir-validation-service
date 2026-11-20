package nzi.fhir.validator.core.validator;

import io.vertx.core.Future;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PackageCatalog;
import nzi.fhir.validator.core.npm.PackageCatalogView;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.web.ProblemException;

import java.util.Optional;
import java.util.Set;

/**
 * The {@code ig} value of a validation request (spec 004 data-model §2.2, spec 012 FR-010): one IG
 * ({@link IgReference}: {@code name#version}, {@code name#latest} or {@code name}) or two ({@link IgPair}:
 * {@code name#version|name#version}). Parsed before any lookup; resolved from memory to the included context it names.
 */
public sealed interface IgRequest permits IgReference, IgPair {

    /**
     * @throws nzi.fhir.validator.web.ProblemException 400 {@code invalid-request} for a malformed value
     */
    static IgRequest parse(String raw) {
        return raw != null && raw.indexOf(SnapshotKey.MEMBER_SEPARATOR) >= 0 ? IgPair.parse(raw) : IgReference.parse(raw);
    }

    /** The parsed value, or {@code null} when the parameter is absent. */
    static IgRequest parseOptional(String raw) {
        return raw == null ? null : parse(raw);
    }

    /** The request as given, for messages ({@code name#version}, {@code name}, or {@code A#a|B#b}). */
    String label();

    /**
     * The included context this request names, as {@code caller} may use it. A package the view doesn't know (or doesn't
     * let the caller see) triggers one rate-limited catalog refresh before the request is rejected with
     * {@link #notStored()} (spec 004 research R4), so the timing matches "not stored". 400 {@code invalid-request} for
     * another FHIR version or "not included".
     *
     * @param included the included keys of {@code fhirVersion}
     * @param aliases  their alias index
     */
    default Future<SnapshotKey> resolve(SupportedFhirVersion fhirVersion, PackageCatalog catalog, Caller caller,
                                        Set<String> included, ContextAliases aliases) {
        Optional<SnapshotKey> known;
        try {
            known = resolveIn(fhirVersion, catalog.current(), caller, included, aliases);
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
                        return resolveIn(fhirVersion, view, caller, included, aliases)
                                .map(Future::succeededFuture)
                                .orElseGet(() -> Future.failedFuture(notStored()));
                    } catch (ProblemException e) {
                        return Future.failedFuture(e);
                    }
                });
    }

    /** 404 {@code package-not-found} for this request, in its own wording (one IG or two). */
    ProblemException notStored();

    /** {@link #resolve} against one catalog view: empty when a package is not stored or not usable by {@code caller}. */
    Optional<SnapshotKey> resolveIn(SupportedFhirVersion fhirVersion, PackageCatalogView view, Caller caller,
                                    Set<String> included, ContextAliases aliases);
}
