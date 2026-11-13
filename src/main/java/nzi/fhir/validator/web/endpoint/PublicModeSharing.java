package nzi.fhir.validator.web.endpoint;

import nzi.fhir.validator.core.security.AccessChange;
import nzi.fhir.validator.core.security.AclMode;

import java.util.List;
import java.util.Optional;

/**
 * Spec 009 FR-034: while access control is disabled, every request runs as one of two fixed callers, so private
 * content would only be hidden from the very clients that use it. Every HTTP request that sets the sharing of a
 * validator context or an IG package therefore stores it as shareable; a requested {@code shareable=false} is ignored
 * with a warning, and {@code shareable=true} (private to shareable) works as before. In enabled mode nothing changes.
 * The administrative command line doesn't come through here.
 */
final class PublicModeSharing {
    static final String FORCED_SHAREABLE_WARNING = "shareable=false was ignored: access control is disabled "
            + "(FV_GLOBAL_DISABLED_ACL_SECURITY), so packages and validator contexts are always shareable";

    private final AclMode mode;

    PublicModeSharing(AclMode mode) {
        this.mode = mode;
    }

    private boolean forcing(boolean requestedShareable) {
        return mode == AclMode.DISABLED && !requestedShareable;
    }

    /** The sharing to store for a request that asked for {@code shareable}; adds the warning when it is overridden. */
    boolean apply(boolean shareable, List<String> warnings) {
        if (forcing(shareable)) {
            warnings.add(FORCED_SHAREABLE_WARNING);
            return true;
        }
        return shareable;
    }

    /** As {@link #apply(boolean, List)} for an optional request field: an absent value stays absent. */
    Optional<Boolean> apply(Optional<Boolean> shareable, List<String> warnings) {
        return shareable.map(s -> apply(s, warnings));
    }

    /** A change with {@code shareable=false} replaced by {@code true}; its owner, terminology and errors are kept. */
    AccessChange apply(AccessChange change, List<String> warnings) {
        if (change.shareable().isEmpty() || !forcing(change.shareable().get())) {
            return change;
        }
        warnings.add(FORCED_SHAREABLE_WARNING);
        return new AccessChange(Optional.of(true), change.owner(), change.terminology(), change.invalid());
    }
}
