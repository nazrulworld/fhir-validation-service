package nzi.fhir.validator.core;

/**
 * A warm-up failure the service must not run with (spec 008, research R14): a well-known organisation or key clashes
 * with existing data, or a key installed from configuration is malformed. {@code Bootstrap.main} logs it and exits
 * with status 1; other warm-up failures only leave the instance unready. The message never contains a secret.
 */
public class StartupException extends RuntimeException {

    public StartupException(String message) {
        super(message);
    }

    public StartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
