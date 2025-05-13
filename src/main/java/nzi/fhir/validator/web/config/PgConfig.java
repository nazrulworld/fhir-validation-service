package nzi.fhir.validator.web.config;

import io.vertx.pgclient.PgConnectOptions;
import java.util.Properties;
import java.io.InputStream;
import java.io.IOException;

public class PgConfig {
    public static PgConnectOptions createPgOptions() {
        Properties props = loadProperties();
        
        return new PgConnectOptions()
                .setHost(getProperty(props, "pg.host", "localhost"))
                .setPort(Integer.parseInt(getProperty(props, "pg.port", "54329")))
                .setDatabase(getProperty(props, "pg.database", "fhir_validator"))
                .setUser(getProperty(props, "pg.user", "postgres"))
                .setPassword(getProperty(props, "pg.password", "Test1234"))
                .setCachePreparedStatements(true);
    }
    
    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream input = PgConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                props.load(input);
            }
        } catch (IOException e) {
            // Fall back to default values if properties file cannot be loaded
            System.err.println("Could not load application.properties: " + e.getMessage());
        }
        return props;
    }
    
    private static String getProperty(Properties props, String key, String defaultValue) {
        // First check system environment, then properties file, then default value
        String envValue = System.getenv(key.toUpperCase().replace('.', '_'));
        if (envValue != null) {
            return envValue;
        }
        return props.getProperty(key, defaultValue);
    }
}