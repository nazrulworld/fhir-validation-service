package nzi.fhir.validator.web.testcontainers;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base test container class that initializes PostgreSQL and Redis containers
 * for integration testing.
 */
public class BaseTestContainer {
    private static final Logger logger = LoggerFactory.getLogger(BaseTestContainer.class);

    // PostgreSQL configuration
    private static final String POSTGRES_IMAGE = "postgres:16.8-alpine3.21";
    protected static final String POSTGRES_DATABASE = "fhir_validator";
    protected static final String POSTGRES_USERNAME = "postgres";
    protected static final String POSTGRES_PASSWORD = "Test1234";
    private static final int POSTGRES_PORT = 5432;

    // Redis configuration
    private static final String REDIS_IMAGE = "redis:7.4.2";
    private static final int REDIS_PORT = 6379;

    // Containers
    protected static PostgreSQLContainer<?> postgresContainer;
    protected static GenericContainer<?> redisContainer;

    /**
     * Creates a Redis container with the specified configuration
     * @return Redis container
     */
    private static GenericContainer<?> createRedisContainer() {
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(REDIS_PORT)) {
            // Return the container without starting it
            return container;
        }
    }

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

        // Initialize Redis container if not already initialized
        if (redisContainer == null) {
            logger.info("Initializing Redis container");
            try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT)) {

                // Start the container
                container.start();

                // Assign to static field (container will be managed by JUnit lifecycle)
                redisContainer = container;

                // Set system properties for Redis connection
                System.setProperty("REDIS_HOST", redisContainer.getHost());
                System.setProperty("REDIS_PORT", String.valueOf(redisContainer.getMappedPort(REDIS_PORT)));

                logger.info("Redis container started at {}:{}", 
                        redisContainer.getHost(), 
                        redisContainer.getMappedPort(REDIS_PORT));

                // Suppress closing of the container when exiting the try-with-resources block
                container.close();
            }
        } else if (!redisContainer.isRunning()) {
            // Start Redis container if not already running
            logger.info("Starting Redis container");
            redisContainer.start();

            // Set system properties for Redis connection
            System.setProperty("REDIS_HOST", redisContainer.getHost());
            System.setProperty("REDIS_PORT", String.valueOf(redisContainer.getMappedPort(REDIS_PORT)));

            logger.info("Redis container started at {}:{}", 
                    redisContainer.getHost(), 
                    redisContainer.getMappedPort(REDIS_PORT));
        }
    }

    @AfterAll
    public static void stopContainers() {
        if (postgresContainer != null && postgresContainer.isRunning()) {
            logger.info("Stopping PostgreSQL container");
            postgresContainer.stop();
        }

        if (redisContainer != null && redisContainer.isRunning()) {
            logger.info("Stopping Redis container");
            redisContainer.stop();
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

    /**
     * Get the host for the Redis container
     * @return Redis host
     */
    public static String getRedisHost() {
        return redisContainer.getHost();
    }

    /**
     * Get the mapped port for the Redis container
     * @return Redis port
     */
    public static Integer getRedisPort() {
        return redisContainer.getMappedPort(REDIS_PORT);
    }

    /**
     * Get the Redis connection string
     * @return Redis connection string in the format redis://host:port
     */
    public static String getRedisConnectionString() {
        return String.format("redis://%s:%d", getRedisHost(), getRedisPort());
    }
}
