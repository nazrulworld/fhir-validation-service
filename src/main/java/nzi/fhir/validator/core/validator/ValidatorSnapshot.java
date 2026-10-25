package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import ca.uhn.fhir.util.BundleUtil;
import nzi.fhir.validator.web.ProblemException;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable validator for one FHIR version, built from exactly one resolved IG package set. Thread-safe; shared
 * by all requests until a newer configuration replaces it.
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
    private final FhirInstanceValidator instanceValidator;
    private final ValidationSupportChain chain;
    private final ProfileStore profileStore;
    private final List<IValidationSupport> reusableSupports;
    private volatile long packageArchiveBytes;

    /**
     * @param reusableSupports immutable supports (core definitions, IG packages) that a re-assembled snapshot can
     *                         share instead of loading them again
     */
    public ValidatorSnapshot(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                             FhirContext fhirContext, FhirValidator validator, FhirInstanceValidator instanceValidator,
                             ValidationSupportChain chain, ProfileStore profileStore, List<IValidationSupport> reusableSupports) {
        this.version = version;
        this.revision = revision;
        this.resolvedPackages = List.copyOf(resolvedPackages);
        this.fhirContext = fhirContext;
        this.validator = validator;
        this.instanceValidator = instanceValidator;
        this.chain = chain;
        this.profileStore = profileStore;
        this.reusableSupports = List.copyOf(reusableSupports);
    }

    public List<IValidationSupport> reusableSupports() {
        return reusableSupports;
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
     * failures. Requested profiles are passed as validation options; the resource is never modified.
     *
     * @throws DataFormatException if the body is not parseable FHIR in the given format
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
        Set<String> applied = new LinkedHashSet<>();
        if (resource instanceof IAnyResource anyResource) {
            anyResource.getMeta().getProfile().forEach(p -> applied.add(p.getValueAsString()));
        }
        if (profiles != null) {
            for (String profile : profiles) {
                options.addProfile(profile.trim());
                applied.add(profile.trim());
            }
        }
        return new Outcome(version, validator.validateWithResult(body, options), List.copyOf(applied), fhirContext);
    }

    /**
     * Drops cached conformance resources after a profile change.
     */
    public void invalidateCaches() {
        chain.invalidateCaches();
        instanceValidator.invalidateCaches();
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

    public ProfileStore profileStore() {
        return profileStore;
    }
}
