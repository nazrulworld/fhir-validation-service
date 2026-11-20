package nzi.fhir.validator.core.validator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The alias index derived from the included keys of one FHIR version (spec 012 FR-008, FR-012, research R3).
 */
class ContextAliasesTest {

    @Test
    void aliasesOfCombinedKeys() {
        ContextAliases aliases = ContextAliases.of(Set.of("a#1|b#2", "c#3"));
        assertEquals(Optional.of("a#1|b#2"), aliases.target("b#2|a#1"));
        assertEquals(Optional.of("a#1|b#2"), aliases.target("a#1"));
        assertEquals(Optional.empty(), aliases.target("c#3"), "a single key has no alias");
        assertEquals(Optional.empty(), aliases.target("b#2"), "the second IG is never an alias");
        assertEquals(Optional.empty(), aliases.target("a#1|b#2"), "the key itself is not an alias");
        assertEquals(List.of("b#2|a#1", "a#1"), aliases.aliasesOf("a#1|b#2"));
        assertEquals(List.of(), aliases.aliasesOf("c#3"));
    }

    @Test
    void takenChecks() {
        ContextAliases aliases = ContextAliases.of(List.of("a#1|b#2"));
        assertTrue(aliases.firstMemberTaken("a#1"));
        assertFalse(aliases.firstMemberTaken("b#2"));
        assertEquals(Optional.of("a#1|b#2"), aliases.pair("a#1", "b#2"));
        assertEquals(Optional.of("a#1|b#2"), aliases.pair("b#2", "a#1"));
        assertEquals(Optional.empty(), aliases.pair("a#1", "c#3"));
        assertEquals(Optional.of("a#1|b#2"), aliases.firstMemberOwner("a#1"));
    }

    @Test
    void empty() {
        ContextAliases aliases = ContextAliases.of(Set.of());
        assertEquals(Optional.empty(), aliases.target("a#1"));
        assertFalse(aliases.firstMemberTaken("a#1"));
        assertSame(ContextAliases.EMPTY.target("x#1"), Optional.empty());
    }

    @Test
    void ignoresBaseAndNulls() {
        ContextAliases aliases = ContextAliases.of(java.util.Arrays.asList("a#1|b#2", null));
        assertEquals(Optional.of("a#1|b#2"), aliases.target("a#1"));
    }
}
