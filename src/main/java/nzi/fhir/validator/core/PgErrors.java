package nzi.fhir.validator.core;

import io.vertx.pgclient.PgException;

/** PostgreSQL error checks shared by the repositories (SQLState and constraint names). */
public final class PgErrors {
    /** SQLState of a unique-constraint violation. */
    public static final String UNIQUE_VIOLATION = "23505";

    private PgErrors() {
    }

    /** Whether {@code err} is a unique-constraint violation. */
    public static boolean isUniqueViolation(Throwable err) {
        return err instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState());
    }

    /** Whether {@code err} is a unique-constraint violation of {@code constraint}. */
    public static boolean isUniqueViolation(Throwable err, String constraint) {
        return isUniqueViolation(err) && constraint.equals(((PgException) err).getConstraint());
    }

    /** The violated constraint's name, or {@code null}. */
    public static String constraint(Throwable err) {
        return err instanceof PgException pg ? pg.getConstraint() : null;
    }
}
