package nzi.fhir.validator.web.config;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author Md Nazrul Islam
 */
public class ApplicationConfig {
    private static Properties applicationProperties;

    public static String get(String key) {
        return get(key, "");
    }
    public static String get(String key, String defaultValue) {
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
        if (!"default".equalsIgnoreCase(env)) {
            // Load from filesystem
            String configPath = System.getProperty("application.config.path", "application.properties");
            File file = new File(configPath);
            if (file.exists() && file.isFile()) {
                try (InputStream input = new FileInputStream(file)) {
                    props.load(input);
                    System.out.println("Loaded properties from file system: " + configPath);
                } catch (IOException e) {
                    System.err.println("Failed to load properties from " + configPath + ": " + e.getMessage());
                }
            } else {
                System.err.println("Config file not found at: " + configPath);
            }
        } else {
            // Load from the classpath (default behavior)
            try (InputStream input = ApplicationConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
                if (input != null) {
                    props.load(input);
                    System.out.println("Loaded properties from classpath: application.properties");
                } else {
                    System.err.println("Classpath resource 'application.properties' not found.");
                }
            } catch (IOException e) {
                System.err.println("Could not load application.properties from classpath: " + e.getMessage());
            }
        }

        return props;
    }

}
