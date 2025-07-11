package nzi.fhir.validator.testcontainers;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Order;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * Base test container class that initializes PostgreSQL container
 * for integration testing.
 * 
 * @author Md Nazrul Islam
 */
public class BaseTestContainer {
    private static final Logger logger = LoggerFactory.getLogger(BaseTestContainer.class);
    private Pool pgPool;
    // PostgresSQL configuration
    private static final String POSTGRES_IMAGE = "postgres:16.8-alpine3.21";
    protected static final String POSTGRES_DATABASE = "fhir_validator";
    protected static final String POSTGRES_USERNAME = "postgres";
    protected static final String POSTGRES_PASSWORD = "Test1234";
    private static final int POSTGRES_PORT = 5432;

    // Containers
    protected static PostgreSQLContainer<?> postgresContainer;

    protected boolean hasPgPool() {
        return pgPool != null;
    }

    protected void removePgPool() {
        if (pgPool != null) {
            pgPool = null;
        }
    }

    protected Pool getPgPool(Vertx vertx) {
        return getPgPool(vertx, false);
    }

    protected Pool getPgPool(Vertx vertx, boolean noCache) {
        if (pgPool == null || noCache) {
            Pool pool = createPgPool(vertx);
            if (noCache) {
                return pool;
            } else {
                pgPool = pool;
            }
        }
        return pgPool;
    }

    protected static void initDatabaseScheme(Pool pgPool){
        String createSchemaSQL = """
                CREATE SCHEMA IF NOT EXISTS %s;
                """.formatted(DB_POSTGRES_SCHEMA_NAME);
        pgPool.query(createSchemaSQL).execute().compose(rowset -> pgPool.query("SET search_path TO %s;".formatted(DB_POSTGRES_SCHEMA_NAME)).execute().mapEmpty()).toCompletionStage().toCompletableFuture().join();

    }
    protected static void createTables(Pool pgPool){
        // Create the necessary table
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS %s.fhir_implementation_guides (
                ig_package_id TEXT NOT NULL,
                ig_package_version TEXT NOT NULL,
                ig_package_meta JSONB NOT NULL,
                content_raw BYTEA NOT NULL,
                dependencies TEXT[],
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                modified_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (ig_package_id, ig_package_version)
            );
        """.formatted(DB_POSTGRES_SCHEMA_NAME);
        createTableSQL += """
            CREATE TABLE IF NOT EXISTS %s.fhir_profiles (
                url TEXT NOT NULL,
                profile_json JSONB NOT NULL,
                fhir_version TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                modified_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (url, fhir_version)
            );
        """.formatted(DB_POSTGRES_SCHEMA_NAME);

        createTableSQL += """
            CREATE TABLE IF NOT EXISTS %s.fhir_validator_logs (
                id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                validator_id VARCHAR(36) NOT NULL,
                fhir_version VARCHAR(16) NOT NULL,
                included_ig_packages TEXT[] NOT NULL DEFAULT '{}',
                included_profiles TEXT[] NOT NULL DEFAULT '{}',
                is_active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NULL,
                UNIQUE (validator_id, fhir_version)
            )
        """.formatted(DB_POSTGRES_SCHEMA_NAME);

        pgPool.query(createTableSQL)
                .execute()
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }

    protected static void dropTables(Pool pgPool){
        String dropTableSQL = "DROP TABLE IF EXISTS %s.fhir_implementation_guides;".formatted(DB_POSTGRES_SCHEMA_NAME);
        dropTableSQL += "DROP TABLE IF EXISTS %s.fhir_profiles; ".formatted(DB_POSTGRES_SCHEMA_NAME);
        dropTableSQL += "DROP TABLE IF EXISTS %s.fhir_validator_logs".formatted(DB_POSTGRES_SCHEMA_NAME);
        pgPool.query(dropTableSQL)
                .execute()
                .toCompletionStage()
                .toCompletableFuture()
                .join();
    }
    protected static Pool createPgPool(Vertx vertx) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(getPostgresHost())
                .setPort(getPostgresPort())
                .setDatabase(POSTGRES_DATABASE)
                .setUser(POSTGRES_USERNAME)
                .setPassword(POSTGRES_PASSWORD);

        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(5);
        return PgBuilder.pool().connectingTo(connectOptions).with(poolOptions).using(vertx).build();
    }

    @BeforeAll
    @Order(1)
    public static void startContainers() {
        if (postgresContainer == null) {
            logger.info("Initializing PostgreSQL container");
            postgresContainer = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName(POSTGRES_DATABASE)
                    .withUsername(POSTGRES_USERNAME)
                    .withPassword(POSTGRES_PASSWORD)
                    .withCommand("-c max_wal_size=2GB")
                    .withStartupTimeout(Duration.ofSeconds(60))
                    .withStartupAttempts(3)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));

            postgresContainer.start();

            // Set system properties for PostgreSQL connection
            System.setProperty("PG_HOST", postgresContainer.getHost());
            System.setProperty("PG_PORT", String.valueOf(postgresContainer.getMappedPort(POSTGRES_PORT)));
            System.setProperty("PG_DATABASE", POSTGRES_DATABASE);
            System.setProperty("PG_USER", POSTGRES_USERNAME);
            System.setProperty("PG_PASSWORD", POSTGRES_PASSWORD);

            logger.info("PostgreSQL container started at {}:{}", 
                    postgresContainer.getHost(), 
                    postgresContainer.getMappedPort(POSTGRES_PORT));
        } else if (!postgresContainer.isRunning()) {
            logger.info("Starting PostgreSQL container");
            postgresContainer.start();

            System.setProperty("PG_HOST", postgresContainer.getHost());
            System.setProperty("PG_PORT", String.valueOf(postgresContainer.getMappedPort(POSTGRES_PORT)));
            System.setProperty("PG_DATABASE", POSTGRES_DATABASE);
            System.setProperty("PG_USER", POSTGRES_USERNAME);
            System.setProperty("PG_PASSWORD", POSTGRES_PASSWORD);

            logger.info("PostgreSQL container started at {}:{}", 
                    postgresContainer.getHost(), 
                    postgresContainer.getMappedPort(POSTGRES_PORT));
        }
    }

    @AfterAll
    @Order(200)
    public static void stopContainers() {
        if (postgresContainer != null && postgresContainer.isRunning()) {
            logger.info("Stopping PostgreSQL container");
            postgresContainer.stop();
        }
    }

    public static String getPostgresJdbcUrl() {
        return postgresContainer.getJdbcUrl();
    }

    public static String getPostgresHost() {
        return postgresContainer.getHost();
    }

    public static Integer getPostgresPort() {
        return postgresContainer.getMappedPort(POSTGRES_PORT);
    }

    public static boolean hasInternetConnection() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("packages2.fhir.org", 443), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    protected byte[] readIgPackageFromClassPath(String classpath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(classpath)) {
            if (is == null) {
                throw new IOException("Could not find US Core package in resources: " + classpath);
            }
            return is.readAllBytes();
        }
    }
}