package nzi.fhir.validator.core.terminology;

/**
 * The current terminology allowlist (spec 007 FR-007): the registry list is refreshed in the background, so users
 * ask for the current one at the moment they need it rather than keep a copy.
 */
@FunctionalInterface
public interface TxServerAllowlistSource {
    /** An allowlist that never allows anything (no list, no built-in fallback). */
    TxServerAllowlistSource EMPTY = () -> TxServerAllowlist.EMPTY;

    TxServerAllowlist current();
}
