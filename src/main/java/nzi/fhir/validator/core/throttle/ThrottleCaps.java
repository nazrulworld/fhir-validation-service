package nzi.fhir.validator.core.throttle;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Every stored cap at one moment (spec 014, research R8): the keys and organisations with a cap of their own, and the
 * two service-wide defaults. Immutable; reloaded from the database every {@code FV_THROTTLE_CAPS_REFRESH_MS} and
 * swapped in whole, so the request path reads it without a lock or any I/O.
 *
 * @param keyCaps    per-minute caps of keys that have one of their own
 * @param orgCaps    per-minute caps of organisations that have one of their own
 * @param defaultKey the cap of keys without one of their own; empty = not limited at key level
 * @param defaultOrg the cap of organisations without one of their own; empty = not limited at organisation level
 * @param loadedAt   when the snapshot was read (logs)
 */
public record ThrottleCaps(Map<UUID, Integer> keyCaps, Map<UUID, Integer> orgCaps, OptionalInt defaultKey,
                           OptionalInt defaultOrg, Instant loadedAt) {

    /** No caps at all: nothing is limited. */
    public static final ThrottleCaps EMPTY = new ThrottleCaps(Map.of(), Map.of(), OptionalInt.empty(), OptionalInt.empty(),
            Instant.EPOCH);

    public ThrottleCaps {
        keyCaps = Map.copyOf(keyCaps);
        orgCaps = Map.copyOf(orgCaps);
    }

    /** FR-006: the key's own cap, else the default key cap, else none. */
    public OptionalInt keyCap(UUID keyId) {
        Integer own = keyId == null ? null : keyCaps.get(keyId);
        return own != null ? OptionalInt.of(own) : defaultKey;
    }

    /** FR-006: the organisation's own cap, else the default organisation cap, else none. */
    public OptionalInt orgCap(UUID orgId) {
        Integer own = orgId == null ? null : orgCaps.get(orgId);
        return own != null ? OptionalInt.of(own) : defaultOrg;
    }
}
