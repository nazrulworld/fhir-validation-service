package nzi.fhir.validator.web.config;

import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.json.JsonObject;

import java.net.URL;
import java.nio.file.Paths;

/**
 * @author Md Nazrul Islam
 */
public class VerticleConfig {

    public static ConfigRetrieverOptions getConfigRetrieverOptions(){

        String env = System.getProperty("vertx.environment", "default");
        String configPath;
        if (!"default".equalsIgnoreCase(env)) {
            configPath = System.getProperty("vertx.config.path", "config.json");
        } else {
            URL resourceUrl = VerticleConfig.class.getClassLoader().getResource("config.json");
            if (resourceUrl == null) {
                throw new IllegalStateException("Classpath config.json not found");
            }
            configPath = Paths.get(resourceUrl.getPath()).toString();
            System.out.println("Loading config from classpath: " + configPath);
        }
        ConfigStoreOptions fileStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("json")
                .setConfig(new JsonObject().put("path", configPath));
        return new ConfigRetrieverOptions().addStore(fileStore);
    }
}
