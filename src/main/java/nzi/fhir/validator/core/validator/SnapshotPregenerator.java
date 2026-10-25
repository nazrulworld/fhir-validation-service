package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.util.FhirTerser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates missing StructureDefinition snapshots before the definitions become visible to validation.
 * <p>
 * Why: HL7 core's snapshot generator modifies the <em>base</em> definition it works against
 * ({@code ProfileUtilities.fixTypeOfResourceId}), and HAPI shares the parsed core definitions JVM-wide. When HAPI
 * generates a snapshot lazily during validation, that write races with every other validation reading the same base
 * (seen as {@code NoSuchElementException} or "Multiple matching extensions" failures). Here the generator runs
 * against a private chain in which every StructureDefinition is a deep copy made for this run, so shared objects are
 * never written; afterwards nothing is left for HAPI to generate at validation time.
 * <p>
 * CPU-heavy (copies the definitions it touches): call it on a worker pool, never on the event loop.
 */
public final class SnapshotPregenerator {
    private static final Logger logger = LogManager.getLogger(SnapshotPregenerator.class);

    private SnapshotPregenerator() {
    }

    /**
     * @param definitions StructureDefinitions to complete; entries that already have a snapshot are returned as is
     * @param sources     where base definitions are looked up (read only: everything served from them is copied);
     *                    the core definitions are always added
     * @return the definitions in the same order, with a generated snapshot where one was missing and could be built.
     * A definition whose snapshot cannot be generated (e.g. unknown base) is returned unchanged and logged
     */
    public static List<IBaseResource> withSnapshots(FhirContext ctx, List<IBaseResource> definitions,
                                                    List<IValidationSupport> sources) {
        if (definitions.stream().noneMatch(d -> needsSnapshot(ctx, d))) {
            return definitions;
        }
        List<IValidationSupport> allSources = new ArrayList<>();
        allSources.add(new DefaultProfileValidationSupport(ctx));
        allSources.addAll(sources);
        PrivateCopies copies = new PrivateCopies(ctx, allSources, definitions);
        ValidationSupportChain chain = new ValidationSupportChain(copies, new SnapshotGeneratingValidationSupport(ctx));
        ValidationSupportContext context = new ValidationSupportContext(chain);

        List<IBaseResource> result = new ArrayList<>(definitions.size());
        for (IBaseResource definition : definitions) {
            if (!needsSnapshot(ctx, definition)) {
                result.add(definition);
                continue;
            }
            String url = url(ctx, definition);
            try {
                IBaseResource generated = chain.generateSnapshot(context, copies.batchCopy(definition), url, null, url);
                if (generated == null || needsSnapshot(ctx, generated)) {
                    logger.warn("No snapshot could be generated for {}; it will be generated during validation", url);
                    result.add(definition);
                } else {
                    copies.replaceBatchEntry(definition, generated);
                    result.add(generated);
                }
            } catch (RuntimeException e) {
                logger.warn("Snapshot generation for {} failed ({}); it will be generated during validation", url, e.getMessage());
                result.add(definition);
            }
        }
        return result;
    }

    public static boolean needsSnapshot(FhirContext ctx, IBaseResource resource) {
        return resource != null && "StructureDefinition".equals(ctx.getResourceType(resource))
                && ctx.newTerser().getValues(resource, "snapshot.element").isEmpty();
    }

    private static String url(FhirContext ctx, IBaseResource resource) {
        return ctx.newTerser().getSinglePrimitiveValueOrNull(resource, "url");
    }

    /**
     * Serves deep copies of StructureDefinitions only, each copied once per run, so the generator (and HAPI's
     * per-object conversion cache) never touches an object anyone else holds. Value sets and code systems are not
     * needed for snapshot generation and are not served.
     */
    private static final class PrivateCopies implements IValidationSupport {
        private final FhirContext ctx;
        private final FhirTerser terser;
        private final List<IValidationSupport> sources;
        private final Map<String, IBaseResource> batchByUrl = new LinkedHashMap<>();
        private final Map<IBaseResource, IBaseResource> batchCopyOf = new IdentityHashMap<>();
        private final Map<String, IBaseResource> sourceCopies = new LinkedHashMap<>();
        private List<IBaseResource> all;

        PrivateCopies(FhirContext ctx, List<IValidationSupport> sources, List<IBaseResource> batch) {
            this.ctx = ctx;
            this.terser = ctx.newTerser();
            this.sources = sources;
            for (IBaseResource definition : batch) {
                IBaseResource copy = terser.clone(definition);
                batchCopyOf.put(definition, copy);
                String url = url(ctx, copy);
                if (url != null) {
                    batchByUrl.put(url, copy);
                }
            }
        }

        IBaseResource batchCopy(IBaseResource definition) {
            return batchCopyOf.get(definition);
        }

        /** Later definitions of the batch then resolve this one with its new snapshot. */
        void replaceBatchEntry(IBaseResource definition, IBaseResource generated) {
            IBaseResource copy = terser.clone(generated);
            batchCopyOf.put(definition, copy);
            String url = url(ctx, copy);
            if (url != null) {
                batchByUrl.put(url, copy);
            }
            all = null;
        }

        @Override
        public FhirContext getFhirContext() {
            return ctx;
        }

        @Override
        public IBaseResource fetchStructureDefinition(String theUrl) {
            String url = theUrl.contains("|") ? theUrl.substring(0, theUrl.indexOf('|')) : theUrl;
            IBaseResource own = batchByUrl.get(url);
            if (own != null) {
                return own;
            }
            IBaseResource cached = sourceCopies.get(url);
            if (cached != null) {
                return cached;
            }
            for (IValidationSupport source : sources) {
                IBaseResource found = source.fetchStructureDefinition(theUrl);
                if (found != null) {
                    IBaseResource copy = terser.clone(found);
                    sourceCopies.put(url, copy);
                    return copy;
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T extends IBaseResource> List<T> fetchAllStructureDefinitions() {
            if (all == null) {
                Map<String, IBaseResource> byUrl = new LinkedHashMap<>();
                for (IValidationSupport source : sources) {
                    List<IBaseResource> fromSource = source.fetchAllStructureDefinitions();
                    if (fromSource == null) {
                        continue;
                    }
                    for (IBaseResource found : fromSource) {
                        String url = url(ctx, found);
                        if (url != null && !byUrl.containsKey(url) && !batchByUrl.containsKey(url)) {
                            byUrl.put(url, sourceCopies.computeIfAbsent(url, u -> terser.clone(found)));
                        }
                    }
                }
                byUrl.putAll(batchByUrl);
                all = new ArrayList<>(byUrl.values());
            }
            return (List<T>) all;
        }

        @Override
        public String getName() {
            return "SnapshotPregenerator private copies";
        }
    }
}
