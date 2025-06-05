package nzi.fhir.validator.web.config;

import io.vertx.pgclient.PgConnectOptions;
import java.util.Properties;
import java.io.InputStream;
import java.io.IOException;

/**
 * @author Md Nazrul Islam
 */
public class PgConfig {
    public static PgConnectOptions createPgOptions() {

        return new PgConnectOptions()
                .setHost(ApplicationConfig.get( "pg.host", "localhost"))
                .setPort(Integer.parseInt(ApplicationConfig.get("pg.port", "54329")))
                .setDatabase(ApplicationConfig.get("pg.database", "fhir_validator"))
                .setUser(ApplicationConfig.get("pg.user", "postgres"))
                .setPassword(ApplicationConfig.get("pg.password", "Test1234"))
                .setCachePreparedStatements(true);
    }
}
