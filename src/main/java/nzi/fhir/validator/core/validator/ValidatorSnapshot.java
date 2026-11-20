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
import nzi.fhir.validator.core.terminology.EffectiveTerminology;
import nzi.fhir.validator.core.terminology.TerminologyBudget;
import nzi.fhir.validator.core.terminology.TerminologyRouter;
import nzi.fhir.validator.core.terminology.TerminologyScope;
import nzi.fhir.validator.core.terminology.TerminologyServices;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import nzi.fhir.validator.core.security.Access;
import java.util.Map;
import java.util.Set;

/**
 * An immutable validator for one FHIR version, built from exactly one resolved IG package set, for its whole life:
 * nothing is added to it after it is built, so the only profiles it knows are those of the core specification and its
 * packages (spec 003). Thread-safe; shared by all requests until a newer configuration replaces it.
 */
public final class ValidatorSnapshot {

    /**
     * Result of one validation.
     *
     * @param contextAccess owner and sharing of the context used (spec 005 FR-020); set by the validation service,
     *                      {@code null} until then
     * @param effective     the terminology setting the validation used (spec 010 FR-010)
     */
    public record Outcome(SupportedFhirVersion version, ValidationResult result, List<String> appliedProfiles,
                          FhirContext fhirContext, String igPackage, Access contextAccess, EffectiveTerminology effective) {
        public Outcome {
            effective = effective == null ? EffectiveTerminology.NONE : effective;
        }

        public Outcome(SupportedFhirVersion version, ValidationResult result, List<String> appliedProfiles,
                       FhirContext fhirContext, String igPackage, Access contextAccess) {
            this(version, result, appliedProfiles, fhirContext, igPackage, contextAccess, EffectiveTerminology.NONE);
        }

        public Outcome(SupportedFhirVersion version, ValidationResult result, List<String> appliedProfiles,
                       FhirContext fhirContext, String igPackage) {
            this(version, result, appliedProfiles, fhirContext, igPackage, null);
        }

        public Outcome(SupportedFhirVersion version, ValidationResult result, List<String> appliedProfiles,
                       FhirContext fhirContext) {
            this(version, result, appliedProfiles, fhirContext, null, null);
        }

        public Outcome withContextAccess(Access access) {
            return new Outcome(version, result, appliedProfiles, fhirContext, igPackage, access, effective);
        }

        /** {@code name#version} of the IG package, or {@code base}: the {@code X-Validation-Context} header value. */
        public String contextLabel() {
            return igPackage == null ? "base" : igPackage;
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong();

    /** A new snapshot id, unique in this process (spec 010: part of every code-cache key). */
    public static long newSnapshotId() {
        return NEXT_ID.incrementAndGet();
    }

    private final long snapshotId;
    private final SupportedFhirVersion version;
    private final SnapshotKey key;
    private final Map<String, byte[]> packageHashes;
    private final long revision;
    private final List<String> resolvedPackages;
    private final FhirContext fhirContext;
    private final FhirValidator validator;
    private final ValidationSupportChain chain;
    private volatile long packageArchiveBytes;
    /** The shared terminology infrastructure (spec 010): request budget and the instance's code cache. */
    private volatile TerminologyServices txServices = TerminologyServices.OFFLINE;

    public ValidatorSnapshot(SupportedFhirVersion version, long revision, List<String> resolvedPackages,
                             FhirContext fhirContext, FhirValidator validator, ValidationSupportChain chain) {
        this(SnapshotKey.base(version), revision, resolvedPackages, Map.of(), fhirContext, validator, chain);
    }

    /**
     * @param packageHashes content hash of every resolved package ({@code name#version}), so a replaced stored package
     *                      can be detected (spec 004 FR-025); missing hashes are allowed
     */
    public ValidatorSnapshot(SnapshotKey key, long revision, List<String> resolvedPackages, Map<String, byte[]> packageHashes,
                             FhirContext fhirContext, FhirValidator validator, ValidationSupportChain chain) {
        this(chain instanceof ScopedValidationSupportChain scoped ? scoped.snapshotId() : newSnapshotId(), key, revision,
                resolvedPackages, packageHashes, fhirContext, validator, chain);
    }

    /**
     * @param snapshotId unique per build ({@link #newSnapshotId()}); the same id as the chain's code-cache keys
     */
    public ValidatorSnapshot(long snapshotId, SnapshotKey key, long revision, List<String> resolvedPackages,
                             Map<String, byte[]> packageHashes, FhirContext fhirContext, FhirValidator validator,
                             ValidationSupportChain chain) {
        this.snapshotId = snapshotId;
        this.version = key.fhirVersion();
        this.key = key;
        this.packageHashes = Map.copyOf(packageHashes);
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

    void setTerminologyServices(TerminologyServices txServices) {
        this.txServices = txServices;
    }

    /** Spec 013: re-checks a result that reports a regex timeout; the service instance's own (budget, metrics). */
    private volatile RegexRecheck regexRecheck = RegexRecheck.DEFAULT;

    void setRegexRecheck(RegexRecheck regexRecheck) {
        this.regexRecheck = regexRecheck == null ? RegexRecheck.DEFAULT : regexRecheck;
    }

    /**
     * Test hook: the context default the plain {@code validate} overloads apply, as {@code FhirValidationService} would
     * (with the same allowlist check). Never set in production, where the service passes each request's setting.
     */
    private volatile nzi.fhir.validator.core.terminology.TerminologySettings testDefault;

    void setDefaultTerminologyForTests(nzi.fhir.validator.core.terminology.TerminologySettings settings) {
        this.testDefault = settings;
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
        nzi.fhir.validator.core.terminology.TerminologySettings d = testDefault;
        if (d == null) {
            return validate(body, contentType, profiles, maxBundleEntries, EffectiveTerminology.NONE, TerminologyRouter.NONE);
        }
        EffectiveTerminology e = EffectiveTerminology.resolve(d, nzi.fhir.validator.core.terminology.TerminologyOverride.ABSENT);
        if (d.server() != null && !txServices.allowlist().current().isAllowed(d.server(), version)) {
            e = e.asDenied();
        }
        return validate(body, contentType, profiles, maxBundleEntries, e,
                TerminologyRouter.of(e, version, txServices.resolver(), txServices.allowlist()));
    }

    /**
     * @param effective the request's terminology setting (spec 010 FR-003), fixed for the whole validation
     * @param router    the server choice of {@code effective}
     */
    public Outcome validate(String body, SupportedContentType contentType, List<String> profiles, int maxBundleEntries,
                            EffectiveTerminology effective, TerminologyRouter router) {
        return validate(body, contentType, profiles, maxBundleEntries, effective, router, java.util.OptionalLong.empty());
    }

    /**
     * @param requestDeadlineNanos when the request must be answered ({@link System#nanoTime()} scale), if any: regex
     *                             re-checks never run past it (spec 013 FR-006)
     */
    public Outcome validate(String body, SupportedContentType contentType, List<String> profiles, int maxBundleEntries,
                            EffectiveTerminology effective, TerminologyRouter router, java.util.OptionalLong requestDeadlineNanos) {
        // Spec 013 FR-006: the validation budget of the regex re-checks counts from here, before parsing.
        long validationStart = System.nanoTime();
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
            throw ProblemException.unknownProfiles(version, key.igPackage(), unresolved);
        }
        applied.forEach(options::addProfile);
        if (resource instanceof IAnyResource anyResource) {
            // Unknown declared profiles are still reported by the validator as errors (Q2: A); they are just not listed.
            anyResource.getMeta().getProfile().stream()
                    .map(p -> p.getValueAsString())
                    .filter(p -> p != null && !applied.contains(p) && resolves(p))
                    .forEach(applied::add);
        }
        // Spec 010 FR-003: the request's terminology scope (setting and router) is open while HAPI validates on this
        // worker thread. Spec 007 FR-019a, research R5, R6: so is its terminology budget (only time spent on
        // terminology calls counts; 0 in mode none). A request that got a degraded answer drops the cached code
        // results of its own scope (spec 010 FR-013), so a "not checked" warning never outlives the outage.
        boolean openedScope = !TerminologyScope.isOpen();
        if (openedScope) {
            TerminologyScope.open(effective, router);
        }
        boolean openedBudget = !TerminologyBudget.isOpen();
        if (openedBudget) {
            TerminologyBudget.open(TerminologyScope.current().offline() ? 0 : txServices.requestBudgetMs());
        }
        try {
            // Spec 013: a result that reports a regex timeout (HL7 core's fixed 500 ms, which load alone can exceed) is
            // validated again, at most twice, inside this scope and budget; the answer is one attempt's whole result.
            ValidationResult result = regexRecheck.run(() -> validator.validateWithResult(body, options),
                    validationStart, requestDeadlineNanos, version, key.igPackage() == null ? "base" : key.igPackage()).result();
            if (TerminologyBudget.degraded()) {
                txServices.codeCache().invalidateScope(TerminologyScope.current().scopeId());
            }
            return new Outcome(version, result, List.copyOf(applied), fhirContext, key.igPackage(), null,
                    TerminologyScope.current().effective());
        } finally {
            if (openedBudget) {
                TerminologyBudget.close();
            }
            if (openedScope) {
                TerminologyScope.close();
            }
        }
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

    /** Unique per build in this process (spec 010: the code cache keys results by it). */
    public long snapshotId() {
        return snapshotId;
    }

    public SupportedFhirVersion version() {
        return version;
    }

    public long revision() {
        return revision;
    }

    public SnapshotKey key() {
        return key;
    }

    /** Content hash per resolved package; a package without a recorded hash is absent. */
    public Map<String, byte[]> packageHashes() {
        return packageHashes;
    }

    public List<String> resolvedPackages() {
        return resolvedPackages;
    }

    public FhirContext fhirContext() {
        return fhirContext;
    }
}
