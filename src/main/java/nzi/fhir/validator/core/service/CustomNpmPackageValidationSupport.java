package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
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

/**
 * Conformance resources of a fixed set of IG packages. Built once per validator snapshot and never mutated
 * afterwards, so replacing a package version can never leave resources of the old version behind (B4, B5).
 *
 * @author Md Nazrul Islam
 */
public class CustomNpmPackageValidationSupport extends NpmPackageValidationSupport {
    private static final Logger logger = LogManager.getLogger(CustomNpmPackageValidationSupport.class);

    private final List<NpmPackage> packages;

    /**
     * Must run on a worker thread: parses the packages and generates missing StructureDefinition snapshots up front
     * ({@link SnapshotPregenerator}), so validation never has HAPI complete them lazily on shared definitions.
     */
    public CustomNpmPackageValidationSupport(FhirContext ctx, List<NpmPackage> packages) {
        super(ctx);
        this.packages = List.copyOf(packages);
        List<IBaseResource> resources = new ArrayList<>();
        for (NpmPackage pkg : this.packages) {
            int parsed = parsePackageResources(pkg, resources);
            logger.info("Loaded IG {}#{} ({} resources) for FHIR {}", pkg.name(), pkg.version(), parsed,
                    ctx.getVersion().getVersion().getFhirVersionString());
        }
        // Bases may be in any package of the set (or in core): complete all StructureDefinitions as one batch.
        List<IBaseResource> definitions = new ArrayList<>();
        for (IBaseResource resource : resources) {
            if ("StructureDefinition".equals(ctx.getResourceType(resource))) {
                definitions.add(resource);
            }
        }
        List<IBaseResource> completed = SnapshotPregenerator.withSnapshots(ctx, definitions, List.of());
        int next = 0;
        for (IBaseResource resource : resources) {
            super.addResource("StructureDefinition".equals(ctx.getResourceType(resource)) ? completed.get(next++) : resource);
        }
    }

    private int parsePackageResources(NpmPackage pkg, List<IBaseResource> into) {
        NpmPackage.NpmPackageFolder packageFolder = pkg.getFolders().get("package");
        if (packageFolder == null) {
            logger.warn("IG {}#{} has no 'package' folder", pkg.name(), pkg.version());
            return 0;
        }
        int parsed = 0;
        for (String nextFile : packageFolder.listFiles()) {
            if (!nextFile.toLowerCase(Locale.ROOT).endsWith(".json")) {
                continue;
            }
            String input = new String(packageFolder.getContent().get(nextFile), StandardCharsets.UTF_8);
            try {
                into.add(getFhirContext().newJsonParser().parseResource(input));
                parsed++;
            } catch (Exception e) {
                logger.debug("Skipping {} in IG {}#{}: not a usable resource", nextFile, pkg.name(), pkg.version());
            }
        }
        return parsed;
    }

    public List<NpmPackage> getPackages() {
        return packages;
    }

    @Override
    public String getName() {
        return "CustomNpmPackageValidationSupport";
    }
}
