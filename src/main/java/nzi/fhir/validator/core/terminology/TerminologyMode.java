package nzi.fhir.validator.core.terminology;

import java.util.Locale;

/**
 * How an included validator context checks codes it can't decide from its own packages (spec 007 FR-001).
 * <ul>
 *     <li>{@link #NONE}: never; no outbound request (the default, and the only mode of the base context).</li>
 *     <li>{@link #SERVER}: every such code goes to the context's one server (the CLI's {@code -tx <url>}).</li>
 *     <li>{@link #ECOSYSTEM}: each code system goes to the server the official HL7 terminology registry names for it.</li>
 * </ul>
 * The wire form is lower-case ({@code none}, {@code server}, {@code ecosystem}); the database form is the constant name.
 */
public enum TerminologyMode {
    NONE, SERVER, ECOSYSTEM;

    /** The lower-case name used in requests and responses. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @throws IllegalArgumentException for anything but the exact lower-case wire names
     */
    public static TerminologyMode fromWire(String wire) {
        for (TerminologyMode m : values()) {
            if (m.wire().equals(wire)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown terminology mode");
    }
}
