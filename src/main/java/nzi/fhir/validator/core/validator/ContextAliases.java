package nzi.fhir.validator.core.validator;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The aliases of the two-IG contexts included for one FHIR version (spec 012 FR-008, FR-012, data-model §2). Derived
 * from the included keys and never stored (research R3): every instance builds the same index from the same included
 * set, on every applied revision, and the include transaction builds one from the locked configuration row.
 * <ul>
 *     <li>{@code B#b|A#a} → {@code A#a|B#b} (reversed order);</li>
 *     <li>{@code A#a} → {@code A#a|B#b} (the first IG; a single-IG context {@code A#a} takes precedence, which is the
 *     caller's rule, not this index's).</li>
 * </ul>
 * The second IG is never an alias.
 */
public final class ContextAliases {
    public static final ContextAliases EMPTY = new ContextAliases(Map.of(), Map.of());

    /** reversed pair → key */
    private final Map<String, String> reversed;
    /** first member → key */
    private final Map<String, String> firstMember;

    private ContextAliases(Map<String, String> reversed, Map<String, String> firstMember) {
        this.reversed = reversed;
        this.firstMember = firstMember;
    }

    /** @param included the included keys of one FHIR version ({@code null} entries are ignored) */
    public static ContextAliases of(Collection<String> included) {
        Map<String, String> reversed = new HashMap<>();
        Map<String, String> first = new HashMap<>();
        for (String key : included) {
            if (!SnapshotKey.isCombined(key)) {
                continue;
            }
            List<String> m = SnapshotKey.membersOf(key);
            reversed.put(m.get(1) + SnapshotKey.MEMBER_SEPARATOR + m.get(0), key);
            // FR-012 keeps first members unique; should two ever exist (hand-written rows), the lowest key wins so
            // every instance picks the same one.
            first.merge(m.get(0), key, (x, y) -> x.compareTo(y) <= 0 ? x : y);
        }
        return reversed.isEmpty() ? EMPTY : new ContextAliases(Map.copyOf(reversed), Map.copyOf(first));
    }

    /** The key {@code alias} stands for: a reversed pair or a first IG. The key itself is not an alias. */
    public Optional<String> target(String alias) {
        if (alias == null) {
            return Optional.empty();
        }
        String key = SnapshotKey.isCombined(alias) ? reversed.get(alias) : firstMember.get(alias);
        return Optional.ofNullable(key);
    }

    /** The included two-IG key holding {@code a} and {@code b} in either order. */
    public Optional<String> pair(String a, String b) {
        String forward = a + SnapshotKey.MEMBER_SEPARATOR + b;
        String backward = b + SnapshotKey.MEMBER_SEPARATOR + a;
        if (reversed.containsKey(backward)) {
            return Optional.of(forward);
        }
        return Optional.ofNullable(reversed.get(forward));
    }

    /** Whether a two-IG context whose first IG is {@code member} is included (FR-012). */
    public boolean firstMemberTaken(String member) {
        return firstMember.containsKey(member);
    }

    /** The two-IG key whose first IG is {@code member}. */
    public Optional<String> firstMemberOwner(String member) {
        return Optional.ofNullable(firstMember.get(member));
    }

    /** The aliases of {@code key}: {@code [B#b|A#a, A#a]} for a two-IG key, empty otherwise (contracts §1). */
    public List<String> aliasesOf(String key) {
        return aliasesFor(key);
    }

    /** {@link #aliasesOf} without an index: the aliases a two-IG key has by definition. */
    public static List<String> aliasesFor(String key) {
        if (!SnapshotKey.isCombined(key)) {
            return List.of();
        }
        List<String> m = SnapshotKey.membersOf(key);
        return List.of(m.get(1) + SnapshotKey.MEMBER_SEPARATOR + m.get(0), m.get(0));
    }
}
