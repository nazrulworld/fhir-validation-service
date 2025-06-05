package nzi.fhir.validator.web.testcontainers;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base test container class that initializes PostgreSQL container
 * for integration testing.
 * 
 * @author Md Nazrul Islam
 */
public class BaseTestContainer {
    private static final Logger logger = LoggerFactory.getLogger(BaseTestContainer.class);

    // PostgreSQL configuration
    private static final String POSTGRES_IMAGE = "postgres:16.8-alpine3.21";
    protected static final String POSTGRES_DATABASE = "fhir_validator";
    protected static final String POSTGRES_USERNAME = "postgres";
    protected static final String POSTGRES_PASSWORD = "Test1234";
    private static final int POSTGRES_PORT = 5432;

    // Containers
    protected static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    public static void startContainers() {
        // Initialize a PostgreSQL container if not already initialized
        if (postgresContainer == null) {
            logger.info("Initializing PostgreSQL container");
            try (PostgreSQLContainer<?> container = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                    .withDatabaseName(POSTGRES_DATABASE)
                    .withUsername(POSTGRES_USERNAME)
                    .withPassword(POSTGRES_PASSWORD)
                    .withCommand("-c max_wal_size=2GB")) {

                // Start the container
                container.start();

                // Assign to static field (container will be managed by JUnit lifecycle)
                postgresContainer = container;

                // Set system properties for PostgreSQL connection
                System.setProperty("PG_HOST", postgresContainer.getHost());
                System.setProperty("PG_PORT", String.valueOf(postgresContainer.getMappedPort(POSTGRES_PORT)));
                System.setProperty("PG_DATABASE", POSTGRES_DATABASE);
                System.setProperty("PG_USER", POSTGRES_USERNAME);
                System.setProperty("PG_PASSWORD", POSTGRES_PASSWORD);

                logger.info("PostgreSQL container started at {}:{}", 
                        postgresContainer.getHost(), 
                        postgresContainer.getMappedPort(POSTGRES_PORT));

                // Suppress closing of the container when exiting the try-with-resources block
                container.close();
            }
        } else if (!postgresContainer.isRunning()) {
            // Start a PostgresSQL container if not already running
            logger.info("Starting PostgresSQL container");
            postgresContainer.start();

            // Set system properties for PostgresSQL connection
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
    public static void stopContainers() {
        if (postgresContainer != null && postgresContainer.isRunning()) {
            logger.info("Stopping PostgreSQL container");
            postgresContainer.stop();
        }
    }

    /**
     * Get the JDBC URL for the PostgreSQL container
     * @return JDBC URL
     */
    public static String getPostgresJdbcUrl() {
        return postgresContainer.getJdbcUrl();
    }

    /**
     * Get the host for the PostgreSQL container
     * @return PostgreSQL host
     */
    public static String getPostgresHost() {
        return postgresContainer.getHost();
    }

    /**
     * Get the mapped port for the PostgreSQL container
     * @return PostgreSQL port
     */
    public static Integer getPostgresPort() {
        return postgresContainer.getMappedPort(POSTGRES_PORT);
    }

}
