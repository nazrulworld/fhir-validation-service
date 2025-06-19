package nzi.fhir.validator.core.config;

import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URL;

/**
 * @author Md Nazrul Islam
 */
public class VerticleConfig {
    private static final Logger logger = LogManager.getLogger(VerticleConfig.class);
    public static ConfigRetrieverOptions getConfigRetrieverOptions(){

        String env = System.getProperty("vertx.environment", "default");
        logger.info("Loading config for the environment: {}", env);
        String configPath;
        if (!"default".equalsIgnoreCase(env)) {
            configPath = System.getProperty("vertx.config.path", "config.json");
            logger.info("Loading config from file system: {} defined as property vertx.config.path", configPath);
        } else {
            URL resourceUrl = VerticleConfig.class.getClassLoader().getResource("config.json");
            if (resourceUrl == null) {
                throw new IllegalStateException("Classpath config.json not found");
            }
            configPath = "config.json";
        }
        ConfigStoreOptions fileStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject().put("path", configPath));
        return new ConfigRetrieverOptions().addStore(fileStore);
    }
}
