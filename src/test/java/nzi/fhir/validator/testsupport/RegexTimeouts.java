package nzi.fhir.validator.testsupport;

import java.util.ArrayList;
import java.util.List;

/**
 * HL7 core 6.9.12 runs every regex with a fixed 500 ms timeout ({@code RegexTimeout.DEFAULT_TIMEOUT}) on a shared
 * executor. On a busy machine that timeout can fire, and the validation then reports an extra regex-timeout issue: load,
 * not a property of the validator context. Tests that compare validation results use these helpers to retry such a
 * failure without hiding any other difference.
 * <p>
 * Detection matches HL7's English messages; the test JVM runs with {@code user.language=en} (surefire
 * {@code argLine}) so the messages are never localised.
 */
public final class RegexTimeouts {
    /** The most attempts a check gets while it fails only because of a regex timeout. */
    public static final int ATTEMPTS = 3;

    /** The two ways HL7 core 6.9.12 reports a regex that ran past its timeout. */
    public static final List<String> TEXTS = List.of(
            "Regex evaluation timed out",   // RegexTimeout's TimeoutException, reported as an exception
            "could not be executed");       // REGEX_MATCH_TIMED_OUT: "The regex '{0}' could not be executed"

    private RegexTimeouts() {
    }

    /** A check that may throw (the comparison of one case). */
    @FunctionalInterface
    public interface Check {
        void run() throws Exception;
    }

    /** Whether {@code text} (a rendered outcome or a failure message) reports a regex timeout. */
    public static boolean mentioned(String text) {
        return text != null && TEXTS.stream().anyMatch(text::contains);
    }

    /** Whether {@code failure}'s message reports a regex timeout. */
    public static boolean timedOut(Throwable failure) {
        return mentioned(failure.getMessage());
    }

    /**
     * Runs {@code check}, again while it fails with a message that reports a regex timeout, up to {@value #ATTEMPTS}
     * attempts. Any other failure is thrown at once. The failure finally thrown carries the earlier attempts' failures
     * as suppressed exceptions, so a flaky run stays visible.
     */
    public static void retry(Check check) throws Exception {
        List<AssertionError> earlier = new ArrayList<>();
        for (int attempt = 1; ; attempt++) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (attempt >= ATTEMPTS || !timedOut(e)) {
                    earlier.forEach(e::addSuppressed);
                    throw e;
                }
                earlier.add(e);
            }
        }
    }
}
