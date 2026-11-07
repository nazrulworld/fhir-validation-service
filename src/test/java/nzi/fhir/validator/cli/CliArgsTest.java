package nzi.fhir.validator.cli;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.org.Organisation;
import nzi.fhir.validator.core.org.Organisations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Command-line parsing and organisation input (spec 005 FR-023, contracts/cli.md).
 */
class CliArgsTest {

    private static CliArgs.Command parse(String... args) {
        return CliArgs.parse(args);
    }

    private static void usageError(String... args) {
        CliException e = assertThrows(CliException.class, () -> CliArgs.parse(args));
        assertEquals(CliException.USAGE, e.exitCode(), e.getMessage());
    }

    @Test
    void parsesFlagsAndValues() {
        CliArgs.Command cmd = parse("org", "create", "--name-short", "acme", "--name-long", "Acme Health", "--type", "COMMERCIAL");
        assertEquals("org create", cmd.name());
        assertEquals("acme", cmd.required("name-short"));
        assertEquals("Acme Health", cmd.value("name-long").orElseThrow());
        assertTrue(cmd.value("email").isEmpty());
    }

    @Test
    void addressLineIsRepeatableAndReplacesTheList() {
        CliArgs.Command cmd = parse("org", "create", "--address-line", "Street 1", "--address-line", "2. sal");
        Organisation.Input in = CliArgs.organisationInput(cmd, false);
        assertEquals(List.of("Street 1", "2. sal"), in.organisation().addressLines());
        assertTrue(in.fields().contains(Organisation.ADDRESS_LINES));
    }

    @Test
    void anEmptyValueClearsAField() {
        Organisation.Input in = CliArgs.organisationInput(parse("org", "update", "--name-short", "acme", "--email", ""), true);
        assertNull(in.organisation().email());
        assertEquals(Set.of(Organisation.EMAIL), in.fields(), "the selector is not a field");
    }

    @Test
    void switchesTakeNoValue() {
        CliArgs.Command cmd = parse("key", "create", "--org", "acme", "--name", "ci", "--role", "VALIDATE", "--key-stdin");
        assertTrue(cmd.has("key-stdin"));
    }

    @Test
    void usageErrors() {
        usageError("org");
        usageError("org", "delete");
        usageError("tenant", "create");
        usageError("org", "create", "--colour", "red");
        usageError("org", "create", "--name-short");
        usageError("org", "create", "acme");
        usageError("org", "create", "--name-short", "a", "--name-short", "b");
        usageError("key", "create", "--id", "x");
        assertThrows(CliException.class, () -> parse("org", "list", "--active", "maybe").bool("active"));
        assertThrows(CliException.class, () -> parse("key", "revoke").required("name"));
    }

    @Test
    void theFileIsReadFirstAndFlagsOverrideIt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("acme.json");
        Files.writeString(file, new JsonObject().put("nameShort", "acme").put("nameLong", "From File").put("type", "COMMERCIAL")
                .put("email", "file@acme.example").put("addressLines", new JsonArray().add("File street")).encode());
        Organisation.Input in = CliArgs.organisationInput(parse("org", "create", "--file", file.toString(),
                "--email", "flag@acme.example"), false);
        Organisation o = in.organisation();
        assertEquals("acme", o.nameShort());
        assertEquals("From File", o.nameLong());
        assertEquals(Organisations.Type.COMMERCIAL, o.type());
        assertEquals("flag@acme.example", o.email(), "the flag replaces the file's value");
        assertEquals(List.of("File street"), o.addressLines());
    }

    @Test
    void unknownFileFieldsAreRefusedAndOutputOnlyOnesIgnored(@TempDir Path dir) throws IOException {
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, new JsonObject().put("nameShort", "acme").put("colour", "red").encode());
        CliException e = assertThrows(CliException.class,
                () -> CliArgs.organisationInput(parse("org", "create", "--file", bad.toString()), false));
        assertEquals(CliException.USAGE, e.exitCode());
        assertTrue(e.getMessage().contains("colour"));

        Path output = dir.resolve("output.json");
        Files.writeString(output, new JsonObject().put("nameShort", "acme").put("id", "x").put("active", false)
                .put("createdAt", "2026-01-01T00:00:00Z").put("updatedAt", null).encode());
        Organisation.Input in = CliArgs.organisationInput(parse("org", "create", "--file", output.toString()), false);
        assertEquals(Set.of(Organisation.NAME_SHORT), in.fields());
    }

    @Test
    void updateFileNameShortMustMatchTheSelector(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("u.json");
        Files.writeString(file, new JsonObject().put("nameShort", "other").put("phone", "+45 1234").encode());
        assertThrows(CliException.class, () -> CliArgs.organisationInput(parse("org", "update", "--name-short", "acme",
                "--file", file.toString()), true));
        Files.writeString(file, new JsonObject().put("nameShort", "ACME").put("phone", "+45 1234").encode());
        Organisation.Input in = CliArgs.organisationInput(parse("org", "update", "--name-short", "acme", "--file", file.toString()), true);
        assertEquals(Set.of(Organisation.PHONE), in.fields());
        Organisation.Input renamed = CliArgs.organisationInput(parse("org", "update", "--name-short", "acme", "--rename", "acme2"), true);
        assertEquals("acme2", renamed.organisation().nameShort());
        assertEquals(Set.of(Organisation.NAME_SHORT), renamed.fields());
    }

    @Test
    void unreadableFileIsAUsageError() {
        CliException e = assertThrows(CliException.class,
                () -> CliArgs.organisationInput(parse("org", "create", "--file", "/no/such/file.json"), false));
        assertEquals(CliException.USAGE, e.exitCode());
    }

    @Test
    void commandGroupsAreRecognised() {
        assertTrue(AdminCli.isCommand(new String[]{"org", "list"}));
        assertTrue(AdminCli.isCommand(new String[]{"snapshot"}));
        assertFalse(AdminCli.isCommand(new String[]{}));
        assertFalse(AdminCli.isCommand(new String[]{"--help"}));
    }
}
