package nzi.fhir.validator.core.service;

import ca.uhn.fhir.context.FhirContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.nio.charset.StandardCharsets;
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

    public CustomNpmPackageValidationSupport(FhirContext ctx, List<NpmPackage> packages) {
        super(ctx);
        this.packages = List.copyOf(packages);
        for (NpmPackage pkg : this.packages) {
            int added = addPackageResources(pkg);
            logger.info("Loaded IG {}#{} ({} resources) for FHIR {}", pkg.name(), pkg.version(), added,
                    ctx.getVersion().getVersion().getFhirVersionString());
        }
    }

    private int addPackageResources(NpmPackage pkg) {
        NpmPackage.NpmPackageFolder packageFolder = pkg.getFolders().get("package");
        if (packageFolder == null) {
            logger.warn("IG {}#{} has no 'package' folder", pkg.name(), pkg.version());
            return 0;
        }
        int added = 0;
        for (String nextFile : packageFolder.listFiles()) {
            if (!nextFile.toLowerCase(Locale.ROOT).endsWith(".json")) {
                continue;
            }
            String input = new String(packageFolder.getContent().get(nextFile), StandardCharsets.UTF_8);
            try {
                IBaseResource resource = getFhirContext().newJsonParser().parseResource(input);
                super.addResource(resource);
                added++;
            } catch (Exception e) {
                logger.debug("Skipping {} in IG {}#{}: not a usable resource", nextFile, pkg.name(), pkg.version());
            }
        }
        return added;
    }

    public List<NpmPackage> getPackages() {
        return packages;
    }

    @Override
    public String getName() {
        return "CustomNpmPackageValidationSupport";
    }
}
