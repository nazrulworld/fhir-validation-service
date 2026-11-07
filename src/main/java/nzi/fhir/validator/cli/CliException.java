package nzi.fhir.validator.cli;

/**
 * A command-line failure with its exit code (contracts/cli.md): 2 usage or validation, 3 not found, 4 conflict.
 */
public class CliException extends RuntimeException {
    public static final int USAGE = 2;
    public static final int NOT_FOUND = 3;
    public static final int CONFLICT = 4;

    private final int exitCode;
    private final String code;

    public CliException(int exitCode, String code, String message) {
        super(message, null, false, false);
        this.exitCode = exitCode;
        this.code = code;
    }

    public static CliException usage(String message) {
        return new CliException(USAGE, "usage", message);
    }

    public int exitCode() {
        return exitCode;
    }

    /** Short machine-readable code for the {@code error: <code>: <message>} line. */
    public String code() {
        return code;
    }
}
