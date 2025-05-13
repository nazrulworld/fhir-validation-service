package nzi.fhir.validator.web.config;

import io.vertx.core.Vertx;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import java.util.Properties;
import java.io.InputStream;
import java.io.IOException;

public class RedisConfig {
    public static RedisOptions createRedisOptions() {
        Properties props = loadProperties();

        return new RedisOptions()
                .setConnectionString(
                        "redis://" +
                                getProperty(props, "redis.host", "localhost") + ":" +
                                getProperty(props, "redis.port", "6379")
                )
                .setMaxPoolSize(10)
                .setMaxPoolWaiting(20);
    }

    public static RedisAPI createRedisClient(Vertx vertx) {
        Properties props = loadProperties();

        String host = getProperty(props, "redis.host", "localhost");
        String port = getProperty(props, "redis.port", "6379");

        return RedisAPI.api(Redis.createClient(vertx, String.format("redis://%s:%s", host, port)));
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream input = RedisConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                props.load(input);
            }
        } catch (IOException e) {
            // Fall back to default values if a properties file cannot be loaded
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
