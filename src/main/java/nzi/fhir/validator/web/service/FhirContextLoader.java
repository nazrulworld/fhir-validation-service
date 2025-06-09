package nzi.fhir.validator.web.service;

import ca.uhn.fhir.context.FhirContext;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.Map;

/**
 * Singleton class responsible for loading and caching FHIR contexts.
 *
 * @author Your Name
 */
public class FhirContextLoader {
    private static final Logger logger = LogManager.getLogger(FhirContextLoader.class);
    private static FhirContextLoader instance;
    private final Map<SupportedFhirVersion, FhirContext> contextMap;

    private FhirContextLoader() {
        contextMap = new EnumMap<>(SupportedFhirVersion.class);
        initializeContexts();
    }

    /**
     * Gets the singleton instance of FhirContextLoader.
     *
     * @return The FhirContextLoader instance
     */
    public static synchronized FhirContextLoader getInstance() {
        if (instance == null) {
            instance = new FhirContextLoader();
        }
        return instance;
    }

    private void initializeContexts() {
        logger.info("Initializing FHIR contexts");
        try {
            contextMap.put(SupportedFhirVersion.STU3, FhirContext.forDstu3());
            contextMap.put(SupportedFhirVersion.R4, FhirContext.forR4());
            contextMap.put(SupportedFhirVersion.R4B, FhirContext.forR4B());
            contextMap.put(SupportedFhirVersion.R5, FhirContext.forR5());
            logger.info("FHIR contexts initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize FHIR contexts", e);
            throw new RuntimeException("Failed to initialize FHIR contexts", e);
        }
    }

    /**
     * Gets the FhirContext for the specified version.
     *
     * @param version The FHIR version
     * @return The corresponding FhirContext
     * @throws IllegalArgumentException if the version is not supported
     */
    public FhirContext getContext(SupportedFhirVersion version) {
        FhirContext context = contextMap.get(version);
        if (context == null) {
            logger.error("Unsupported FHIR version: {}", version);
            throw new IllegalArgumentException("Unsupported FHIR version: " + version);
        }
        return context;
    }

    /**
     * Gets the default FhirContext (R4).
     *
     * @return The default FhirContext
     */
    public FhirContext getDefaultContext() {
        return getContext(SupportedFhirVersion.getDefault());
    }

    /**
     * Checks if a context for the specified version exists.
     *
     * @param version The FHIR version to check
     * @return true if the context exists, false otherwise
     */
    public boolean hasContext(SupportedFhirVersion version) {
        return contextMap.containsKey(version);
    }
}