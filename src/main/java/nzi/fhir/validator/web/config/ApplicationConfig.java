package nzi.fhir.validator.web.config;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @author Md Nazrul Islam
 */
public class ApplicationConfig {
    private static final Logger logger = LogManager.getLogger(ApplicationConfig.class);
    private static Properties applicationProperties;
    public static final String DB_POSTGRES_SCHEMA_NAME = "fhir_validator_schema";
    public static String get(String key) {
        return get(key, "");
    }

    public static String get(String key, String defaultValue) {
        String propValue = System.getProperty(key, null);
        if (propValue != null){
            return propValue;
        }
        String envValue = System.getenv(key.toUpperCase().replace('.', '_'));
        if (envValue != null) {
            return envValue;
        }
        if (applicationProperties == null) {
            applicationProperties = loadProperties();
        }
        if (defaultValue.isEmpty()){
            return applicationProperties.getProperty(key, null);
        } else {
            return applicationProperties.getProperty(key, defaultValue);
        }
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        String env = System.getProperty("vertx.environment", "default");
        logger.info("Loading config for the environment: {}", env);
        if (!"default".equalsIgnoreCase(env)) {
            // Load from filesystem
            String configPath = System.getProperty("application.config.path", "application.properties");
            logger.info("Loading config from file system: {} defined as property application.config.path", configPath);
            File file = new File(configPath);
            if (file.exists() && file.isFile()) {
                try (InputStream input = new FileInputStream(file)) {
                    props.load(input);
                    logger.info("Loaded properties from file system: {}", configPath);
                } catch (IOException e) {
                    logger.error("Failed to load properties from {}: {}", configPath, e.getMessage(), e);
                }
            } else {
                logger.error("Config file not found at: {}", configPath);
            }
        } else {
            // Load from the classpath (default behavior)
            try (InputStream input = ApplicationConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
                if (input != null) {
                    props.load(input);
                    logger.info("Loaded properties from classpath: application.properties");
                } else {
                    logger.error("Classpath resource 'application.properties' not found.");
                }
            } catch (IOException e) {
                logger.error("Could not load application.properties from classpath: {}", e.getMessage(), e);
            }
        }

        return props;
    }

}
