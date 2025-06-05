package nzi.fhir.validator.web.service;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import nzi.fhir.validator.model.IGPackageIdentity;
import nzi.fhir.validator.web.enums.FhirCoreIgPackageType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom NpmPackageValidationSupport to load IG profiles from IgService using a protected loadResourcesFromPackage.
 * @author Md Nazrul Islam
 */
public class CustomNpmPackageValidationSupport extends NpmPackageValidationSupport {
    private static final Logger logger = LogManager.getLogger(CustomNpmPackageValidationSupport.class);
    private static final HashMap<IGPackageIdentity, CustomNpmPackageValidationSupport> NPM_PACKAGE_VALIDATION_SUPPORT_CACHE = new HashMap<>();

    private final IgPackageService igPackageService;


    public CustomNpmPackageValidationSupport(FhirContext ctx, IgPackageService igPackageService) {
        super(ctx);
        this.igPackageService = igPackageService;

    }
    public Future<Void> loadIgPackageFromDatabase(String name, String version) {
        return loadIgPackageFromDatabase(name, version, new ArrayList<>());
    }


    /**
     * Loads an IG and processes its resources using loadResourcesFromPackage.
     * @param name IG name (e.g., hl7.fhir.us.core)
     * @param version IG version (e.g., 3.1.1)
     * @return Future indicating completion
     */
    public Future<Void> loadIgPackageFromDatabase(String name, String version, ArrayList<String> resolvedDependencies) {
        return igPackageService.loadIgPackage(name, version)
                .compose(pkg -> {
                    try {
                        loadResourcesFromPackage(pkg);
                        logger.info("Loaded IG {}@{}", name, version);
                        
                        // Create a list of futures for dependencies
                        List<Future<Void>> dependencyFutures = new ArrayList<>();
                        for (String idAndVer : pkg.dependencies()) {
                            String name_ = idAndVer.contains("#") ? idAndVer.substring(0, idAndVer.indexOf("#")) : idAndVer;
                            String version_ = idAndVer.contains("#") ? idAndVer.substring(idAndVer.indexOf("#") + 1) : null;
                            
                            if (FhirCoreIgPackageType.getNameList().contains(name_) || resolvedDependencies.contains(name_)) {
                                continue;
                            }
                            
                            resolvedDependencies.add(name_);
                            dependencyFutures.add(loadIgPackageFromDatabase(name_, version_, resolvedDependencies));
                        }
                        
                        // Wait for all dependencies to load
                        return CompositeFuture.all(new ArrayList<>(dependencyFutures))
                                .mapEmpty();
                                
                    } catch (Exception e) {
                        logger.error("Failed to process IG {}@{}: {}", name, version, e.getMessage(), e);
                        return Future.failedFuture(e);
                    }
                });
    }

    /**
     * Processes StructureDefinition resources from an NpmPackage and caches them.
     * @param pkg The NpmPackage to process
     */
    protected void loadResourcesFromPackage(NpmPackage pkg) {
        NpmPackage.NpmPackageFolder packageFolder = pkg.getFolders().get("package");
        if (packageFolder == null) {
            logger.warn("No 'package' folder found in IG");
            return;
        }
        for(String nextFile : packageFolder.listFiles()) {
            if (nextFile.toLowerCase(Locale.ROOT).endsWith(".json")) {
                String input = new String((byte[])packageFolder.getContent().get(nextFile), StandardCharsets.UTF_8);
                IBaseResource resource = this.getFhirContext().newJsonParser().parseResource(input);
                super.addResource(resource);
            }
        }
    }

    public static boolean isValidClassPath(String classPath) {
        String klasspath = classPath;
        if (classPath.startsWith("classpath:")) {
            klasspath = classPath.substring("classpath:".length());
        }
        URL retVal = CustomNpmPackageValidationSupport.class.getResource(klasspath);
        if (retVal == null) {
            if (klasspath.startsWith("/")) {
                retVal = CustomNpmPackageValidationSupport.class.getResource(klasspath.substring(1));
            } else {
                retVal = CustomNpmPackageValidationSupport.class.getResource("/" + klasspath);
            }
        }
        return retVal != null;
    }

    public static CustomNpmPackageValidationSupport getValidationSupport(IGPackageIdentity igPackageIdentity) {
        return NPM_PACKAGE_VALIDATION_SUPPORT_CACHE.get(igPackageIdentity);
    }
    public static Future<CustomNpmPackageValidationSupport> getValidationSupport(
        IGPackageIdentity igPackageIdentity, 
        IgPackageService igPackageService) {

        CustomNpmPackageValidationSupport npmValidationSupport = NPM_PACKAGE_VALIDATION_SUPPORT_CACHE.get(igPackageIdentity);
        if (npmValidationSupport != null) {
            return Future.succeededFuture(npmValidationSupport);
        }

        final CustomNpmPackageValidationSupport finalNpmValidationSupport = new CustomNpmPackageValidationSupport(
                FhirContextLoader.getInstance().getContext(igPackageIdentity.getFhirVersion()),
                igPackageService);

        if (CustomNpmPackageValidationSupport.isValidClassPath(igPackageIdentity.asClassPath())) {
            try {
                finalNpmValidationSupport.loadPackageFromClasspath(igPackageIdentity.asClassPath());
                NPM_PACKAGE_VALIDATION_SUPPORT_CACHE.put(igPackageIdentity, finalNpmValidationSupport);
                return Future.succeededFuture(finalNpmValidationSupport);
            } catch (Exception e) {
                logger.error("Failed to load IG package: {}", e.getMessage(), e);
                return Future.failedFuture(new RuntimeException("Failed to load IG package", e));
            }
        } else {
            return finalNpmValidationSupport.loadIgPackageFromDatabase(
                    igPackageIdentity.getName(),
                    igPackageIdentity.getVersion())
                .map(v -> {
                    NPM_PACKAGE_VALIDATION_SUPPORT_CACHE.put(igPackageIdentity, finalNpmValidationSupport);
                    return finalNpmValidationSupport;
                });
        }
    }
}