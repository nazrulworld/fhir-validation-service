package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.npm.PostgresPackageCacheManager;
import nzi.fhir.validator.core.npm.SharedPackage;
import nzi.fhir.validator.core.npm.SharedPackageDefinitions;
import nzi.fhir.validator.core.validator.SnapshotPregenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Conformance resources of a fixed set of IG packages. Built once per validator snapshot and never mutated
 * afterwards, so replacing a package version can never leave resources of the old version behind (B4, B5).
 * <p>
 * Spec 010 (FR-017 to FR-021, research R7): the parsed resources of a package are shared by every context of the
 * instance that contains the same package content ({@link SharedPackageDefinitions}); this support's lookup indexes
 * are its own, built in the same order as before, so which definition wins for a duplicate canonical URL is unchanged.
 * A StructureDefinition this context completes ({@link SnapshotPregenerator} works on copies) or that stays incomplete
 * is private to this context: only definitions complete as shipped are shared, so nothing a context derives from its
 * own other packages reaches another context.
 *
 * @author Md Nazrul Islam
 */
public class CustomNpmPackageValidationSupport extends NpmPackageValidationSupport {
    private static final Logger logger = LogManager.getLogger(CustomNpmPackageValidationSupport.class);

    private final List<NpmPackage> packages;
    /** Strong references: the shared entries live while this context does (spec 010 FR-019). */
    private final List<SharedPackage> shared;

    /**
     * Every package parsed for this context alone (no sharing): the 1.0.0 behaviour, kept for direct use in tests (the
     * spec 010 differential baseline) and never reachable from configuration (FR-022).
     */
    public CustomNpmPackageValidationSupport(FhirContext ctx, List<NpmPackage> packages) {
        this(ctx, packages, Map.of(), null, null);
    }

    /**
     * Must run on a worker thread (a package worker when {@code sharedDefinitions} is set): parses the packages, or
     * reuses their shared definitions, and generates missing StructureDefinition snapshots up front
     * ({@link SnapshotPregenerator}), so validation never has HAPI complete them lazily on shared definitions.
     *
     * @param hashes            content hash per {@code name#version}; a package without one is parsed privately
     * @param sharedDefinitions the instance's shared definitions, or {@code null} to parse every package privately
     * @param version           the FHIR version of the context (part of a shared entry's key)
     */
    public CustomNpmPackageValidationSupport(FhirContext ctx, List<NpmPackage> packages, Map<String, byte[]> hashes,
                                             SharedPackageDefinitions sharedDefinitions, SupportedFhirVersion version) {
        super(ctx);
        this.packages = List.copyOf(packages);
        List<SharedPackage> held = new ArrayList<>();
        List<IBaseResource> resources = new ArrayList<>();
        String fhirVersion = ctx.getVersion().getVersion().getFhirVersionString();
        for (NpmPackage pkg : this.packages) {
            String id = pkg.name() + "#" + pkg.version();
            byte[] hash = hashes == null ? null : hashes.get(id);
            if (sharedDefinitions != null && hash != null && version != null) {
                boolean[] parsedHere = {false};
                SharedPackage entry = sharedDefinitions.getOrParse(new SharedPackage.Key(version, id, hash), () -> {
                    parsedHere[0] = true;
                    return new SharedPackage(new SharedPackage.Key(version, id, hash), parse(pkg),
                            PostgresPackageCacheManager.expandedBytes(pkg));
                });
                held.add(entry);
                resources.addAll(entry.resources());
                logger.info("Loaded IG {} ({} resources, {}) for FHIR {}", id, entry.resources().size(),
                        parsedHere[0] ? "parsed" : "reused", fhirVersion);
            } else {
                List<IBaseResource> parsed = parse(pkg);
                resources.addAll(parsed);
                logger.info("Loaded IG {} ({} resources, parsed{}) for FHIR {}", id, parsed.size(),
                        sharedDefinitions != null ? ", not shared: no content hash" : "", fhirVersion);
            }
        }
        this.shared = List.copyOf(held);
        // Bases may be in any package of the set (or in core): complete all StructureDefinitions as one batch.
        List<IBaseResource> definitions = new ArrayList<>();
        for (IBaseResource resource : resources) {
            if ("StructureDefinition".equals(ctx.getResourceType(resource))) {
                definitions.add(resource);
            }
        }
        List<IBaseResource> completed = SnapshotPregenerator.withSnapshots(ctx, definitions, List.of());
        int next = 0;
        int privateCopies = 0;
        for (IBaseResource resource : resources) {
            IBaseResource own = resource;
            if ("StructureDefinition".equals(ctx.getResourceType(resource))) {
                own = completed.get(next++);
                // Spec 010 FR-020: a definition still incomplete would be completed lazily, with this context's chain,
                // on the object itself; give this context its own copy so that never reaches another context.
                if (own == resource && !this.shared.isEmpty() && SnapshotPregenerator.needsSnapshot(ctx, own)) {
                    own = ctx.newTerser().clone(own);
                    privateCopies++;
                }
            }
            super.addResource(own);
        }
        if (privateCopies > 0) {
            logger.debug("{} incomplete StructureDefinition(s) copied privately for this context", privateCopies);
        }
    }

    private List<IBaseResource> parse(NpmPackage pkg) {
        List<IBaseResource> into = new ArrayList<>();
        NpmPackage.NpmPackageFolder packageFolder = pkg.getFolders().get("package");
        if (packageFolder == null) {
            logger.warn("IG {}#{} has no 'package' folder", pkg.name(), pkg.version());
            return into;
        }
        for (String nextFile : packageFolder.listFiles()) {
            if (!nextFile.toLowerCase(Locale.ROOT).endsWith(".json")) {
                continue;
            }
            String input = new String(packageFolder.getContent().get(nextFile), StandardCharsets.UTF_8);
            try {
                into.add(getFhirContext().newJsonParser().parseResource(input));
            } catch (Exception e) {
                logger.debug("Skipping {} in IG {}#{}: not a usable resource", nextFile, pkg.name(), pkg.version());
            }
        }
        return into;
    }

    public List<NpmPackage> getPackages() {
        return packages;
    }

    /** The shared entries this context holds (spec 010; tests and metrics). */
    public List<SharedPackage> getSharedPackages() {
        return shared;
    }

    @Override
    public String getName() {
        return "CustomNpmPackageValidationSupport";
    }
}
