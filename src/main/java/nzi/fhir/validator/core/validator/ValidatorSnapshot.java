package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import ca.uhn.fhir.util.BundleUtil;
import nzi.fhir.validator.web.ProblemException;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.util.FhirTerser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable validator for one FHIR version, built from exactly one resolved IG package set, for its whole life:
 * nothing is added to it after it is built, so the only profiles it knows are those of the core specification and its
 * packages (spec 003). Thread-safe; shared by all requests until a newer configuration replaces it.
 */
public final class ValidatorSnapshot {

    /**
     * Result of one validation.
     */
    public record Outcome(SupportedFhirVersion version, ValidationResult result, List<String> appliedProfiles,
                          FhirContext fhirContext) {
    }

    private final SupportedFhirVersion version;
    private final long revision;
    private final List<String> resolvedPackages;
    private final FhirContext fhirContext;
    private final FhirValidator validator;
    private final ValidationSupportChain chain;
    private volatile long packageArchiveBytes;

    public ValidatorSnapshot(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                             FhirContext fhirContext, FhirValidator validator, ValidationSupportChain chain) {
        this.version = version;
        this.revision = revision;
        this.resolvedPackages = List.copyOf(resolvedPackages);
        this.fhirContext = fhirContext;
        this.validator = validator;
        this.chain = chain;
    }

    /** Total archive size of the packages this snapshot was built from (memory sizing, metrics). */
    public long packageArchiveBytes() {
        return packageArchiveBytes;
    }

    void setPackageArchiveBytes(long bytes) {
        this.packageArchiveBytes = bytes;
    }

    /**
     * Validates {@code body}. Must run on a worker thread.
     * <p>
     * The body is first parsed leniently, only to reject input that is not FHIR at all; the validator then checks
     * the raw text, so invalid values (e.g. unknown codes) are reported as validation issues rather than parse
     * failures. Requested profiles must resolve, by exact canonical ({@code url} or {@code url|version}), from this
     * snapshot's definitions (core and included IGs); nothing is ever fetched (spec 003, FR-001 to FR-003). They are
     * passed as validation options; the resource is never modified.
     *
     * @throws DataFormatException if the body is not parseable FHIR in the given format
     * @throws ProblemException    400 {@code unknown-profile} if any requested profile does not resolve; nothing is
     *                             validated then (FR-006)
     */
    public Outcome validate(String body, SupportedContentType contentType, List<String> profiles) {
        return validate(body, contentType, profiles, Integer.MAX_VALUE);
    }

    /**
     * @param maxBundleEntries a Bundle with more entries is refused with 413 ({@code FV_VALIDATION_MAX_BUNDLE_ENTRIES})
     */
    public Outcome validate(String body, SupportedContentType contentType, List<String> profiles, int maxBundleEntries) {
        IParser parser = (contentType == SupportedContentType.XML || contentType == SupportedContentType.FHIR_XML)
                ? fhirContext.newXmlParser() : fhirContext.newJsonParser();
        parser.setParserErrorHandler(new LenientErrorHandler(false).setErrorOnInvalidValue(false));
        IBaseResource resource = parser.parseResource(body);
        if (resource instanceof IBaseBundle bundle) {
            int entries = BundleUtil.toListOfEntries(fhirContext, bundle).size();
            if (entries > maxBundleEntries) {
                throw new ProblemException(413, "payload-too-large",
                        "The Bundle has " + entries + " entries; the limit is " + maxBundleEntries);
            }
        }
        ValidationOptions options = new ValidationOptions();
        // Requested first (request order), then resolvable meta.profile entries; each once, never an unresolved one.
        Set<String> applied = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        if (profiles != null) {
            for (String profile : profiles) {
                String requested = profile.trim();
                if (requested.isEmpty() || applied.contains(requested)) {
                    continue;
                }
                if (resolves(requested)) {
                    applied.add(requested);
                } else if (!unresolved.contains(requested)) {
                    unresolved.add(requested);
                }
            }
        }
        if (!unresolved.isEmpty()) {
            throw ProblemException.unknownProfiles(version, unresolved);
        }
        applied.forEach(options::addProfile);
        if (resource instanceof IAnyResource anyResource) {
            // Unknown declared profiles are still reported by the validator as errors (Q2: A); they are just not listed.
            anyResource.getMeta().getProfile().stream()
                    .map(p -> p.getValueAsString())
                    .filter(p -> p != null && !applied.contains(p) && resolves(p))
                    .forEach(applied::add);
        }
        return new Outcome(version, validator.validateWithResult(body, options), List.copyOf(applied), fhirContext);
    }

    /**
     * Whether {@code canonical} names a StructureDefinition of this snapshot exactly: its {@code url}, or
     * {@code url|version}. HAPI also indexes definitions by their last URL segments, so partial values such as a bare
     * id must be rejected here (research R3). In-memory lookup only.
     */
    boolean resolves(String canonical) {
        IBaseResource sd = chain.fetchStructureDefinition(canonical);
        if (sd == null) {
            return false;
        }
        FhirTerser terser = fhirContext.newTerser();
        String url = terser.getSinglePrimitiveValueOrNull(sd, "url");
        if (url == null) {
            return false;
        }
        if (canonical.equals(url)) {
            return true;
        }
        String sdVersion = terser.getSinglePrimitiveValueOrNull(sd, "version");
        return sdVersion != null && canonical.equals(url + "|" + sdVersion);
    }

    public SupportedFhirVersion version() {
        return version;
    }

    public long revision() {
        return revision;
    }

    public List<String> resolvedPackages() {
        return resolvedPackages;
    }

    public FhirContext fhirContext() {
        return fhirContext;
    }
}
