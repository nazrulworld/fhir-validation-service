package nzi.fhir.validator.cli;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.org.Organisation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Command-line parsing (spec 005 contracts/cli.md, research R9): {@code <group> <command> [--flag value]...}. Each
 * command knows its flags; an unknown flag, a missing value or a repeated non-repeatable flag is a usage error (exit 2).
 * No dependency: about a dozen flags don't need a framework (constitution Principle VIII).
 */
public final class CliArgs {

    /** Organisation field flag → organisation JSON field. */
    static final Map<String, String> FIELD_FLAGS = new LinkedHashMap<>();

    static {
        FIELD_FLAGS.put("name-short", Organisation.NAME_SHORT);
        FIELD_FLAGS.put("name-long", Organisation.NAME_LONG);
        FIELD_FLAGS.put("type", Organisation.TYPE);
        FIELD_FLAGS.put("address-line", Organisation.ADDRESS_LINES);
        FIELD_FLAGS.put("post-code", Organisation.POST_CODE);
        FIELD_FLAGS.put("country", Organisation.COUNTRY);
        FIELD_FLAGS.put("email", Organisation.EMAIL);
        FIELD_FLAGS.put("phone", Organisation.PHONE);
        FIELD_FLAGS.put("website", Organisation.WEBSITE);
        FIELD_FLAGS.put("contact-person-name", Organisation.CONTACT_PERSON_NAME);
        FIELD_FLAGS.put("contact-person-mobile", Organisation.CONTACT_PERSON_MOBILE);
        FIELD_FLAGS.put("contact-person-email", Organisation.CONTACT_PERSON_EMAIL);
    }

    private static final Set<String> REPEATABLE = Set.of("address-line");
    /** Flags that take no value. */
    private static final Set<String> SWITCHES = Set.of("key-stdin");
    private static final Set<String> SELECTOR = Set.of("id", "name-short");

    /** Allowed flags of every {@code group command}. */
    static final Map<String, Set<String>> COMMANDS = new LinkedHashMap<>();

    static {
        Set<String> fields = FIELD_FLAGS.keySet();
        COMMANDS.put("org create", union(Set.of("file"), fields));
        COMMANDS.put("org update", union(Set.of("id", "file", "rename"), fields));
        COMMANDS.put("org deactivate", SELECTOR);
        COMMANDS.put("org activate", SELECTOR);
        COMMANDS.put("org show", SELECTOR);
        COMMANDS.put("org list", Set.of("active"));
        COMMANDS.put("key create", Set.of("org", "name", "role", "key-stdin", "expires"));
        COMMANDS.put("key revoke", Set.of("name"));
        COMMANDS.put("key list", Set.of("org"));
        COMMANDS.put("package share", Set.of("ig", "shareable"));
        COMMANDS.put("package transfer", Set.of("ig", "to"));
        COMMANDS.put("snapshot share", Set.of("fhir-version", "ig", "shareable"));
        COMMANDS.put("snapshot transfer", Set.of("fhir-version", "ig", "to"));
        // Spec 008 FR-022.
        COMMANDS.put("usage summary", Set.of("org", "key", "since"));
    }

    /** The command groups; {@code Bootstrap.main} runs the command line when the first argument is one of them. */
    public static final Set<String> GROUPS = Set.of("org", "key", "package", "snapshot", "usage");

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new LinkedHashSet<>(a);
        u.addAll(b);
        return Set.copyOf(u);
    }

    /** A parsed command line. */
    public record Command(String group, String command, Map<String, List<String>> flags) {
        public String name() {
            return group + " " + command;
        }

        public boolean has(String flag) {
            return flags.containsKey(flag);
        }

        public Optional<String> value(String flag) {
            List<String> v = flags.get(flag);
            return v == null ? Optional.empty() : Optional.of(v.get(v.size() - 1));
        }

        /** @throws CliException 2 when the flag is missing or empty */
        public String required(String flag) {
            String v = value(flag).orElse("");
            if (v.isEmpty()) {
                throw CliException.usage("--" + flag + " is required for '" + name() + "'");
            }
            return v;
        }

        /** {@code --flag true|false}, or empty. */
        public Optional<Boolean> bool(String flag) {
            Optional<String> v = value(flag);
            if (v.isEmpty()) {
                return Optional.empty();
            }
            if (!v.get().equals("true") && !v.get().equals("false")) {
                throw CliException.usage("--" + flag + " must be true or false");
            }
            return Optional.of(Boolean.parseBoolean(v.get()));
        }
    }

    private CliArgs() {
    }

    /**
     * @throws CliException 2 for an unknown command or flag, a missing value or a repeated flag
     */
    public static Command parse(String[] args) {
        if (args.length < 2) {
            throw CliException.usage("Usage: <org|key|package|snapshot> <command> [options] (see contracts/cli.md)");
        }
        String name = args[0] + " " + args[1];
        Set<String> allowed = COMMANDS.get(name);
        if (allowed == null) {
            throw CliException.usage("Unknown command '" + name + "'. Commands: " + String.join(", ", COMMANDS.keySet()));
        }
        Map<String, List<String>> flags = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || arg.length() == 2) {
                throw CliException.usage("Unexpected argument '" + arg + "'");
            }
            String flag = arg.substring(2);
            if (!allowed.contains(flag)) {
                throw CliException.usage("Unknown option --" + flag + " for '" + name + "'");
            }
            String value;
            if (SWITCHES.contains(flag)) {
                value = "true";
            } else {
                if (i + 1 >= args.length) {
                    throw CliException.usage("--" + flag + " needs a value");
                }
                value = args[++i];
            }
            if (flags.containsKey(flag) && !REPEATABLE.contains(flag)) {
                throw CliException.usage("--" + flag + " is given more than once");
            }
            flags.computeIfAbsent(flag, k -> new ArrayList<>()).add(value);
        }
        return new Command(args[0], args[1], flags);
    }

    /**
     * The organisation fields of a create or update (FR-023): the {@code --file} JSON first, then each field flag
     * replaces its field. A repeated {@code --address-line} replaces the file's whole list; an empty value clears an
     * optional field.
     *
     * @param nameShortIsSelector for {@code org update}: {@code --name-short} selects the organisation and isn't a
     *                            field; {@code --rename} changes the short name, and a file may hold {@code nameShort}
     *                            only when it equals the selector
     * @throws CliException 2 for an unreadable file, an unknown field or a bad value
     */
    public static Organisation.Input organisationInput(Command cmd, boolean nameShortIsSelector) {
        Organisation o = Organisation.empty();
        Set<String> fields = new LinkedHashSet<>();
        if (cmd.has("file")) {
            String file = cmd.required("file");
            Organisation.Input fromFile;
            try {
                fromFile = Organisation.fromJson(new JsonObject(Files.readString(Path.of(file))));
            } catch (IOException | DecodeException e) {
                throw CliException.usage("Cannot read organisation JSON from " + file);
            } catch (IllegalArgumentException e) {
                throw CliException.usage(e.getMessage());
            }
            o = fromFile.organisation();
            fields.addAll(fromFile.fields());
            if (nameShortIsSelector && fields.contains(Organisation.NAME_SHORT)) {
                String selector = cmd.value("name-short").orElse(null);
                if (selector == null || !selector.equalsIgnoreCase(o.nameShort())) {
                    throw CliException.usage("The file's nameShort must equal --name-short; use --rename to change it");
                }
                fields.remove(Organisation.NAME_SHORT);
            }
        }
        try {
            for (Map.Entry<String, String> e : FIELD_FLAGS.entrySet()) {
                String flag = e.getKey();
                String field = e.getValue();
                if (!cmd.has(flag) || (nameShortIsSelector && flag.equals("name-short"))) {
                    continue;
                }
                if (field.equals(Organisation.ADDRESS_LINES)) {
                    List<String> lines = cmd.flags().get(flag).stream().filter(l -> !l.isEmpty()).toList();
                    o = o.withAddressLines(lines);
                } else {
                    o = o.with(field, cmd.value(flag).orElse(""));
                }
                fields.add(field);
            }
            if (nameShortIsSelector && cmd.has("rename")) {
                o = o.with(Organisation.NAME_SHORT, cmd.required("rename"));
                fields.add(Organisation.NAME_SHORT);
            }
        } catch (IllegalArgumentException e) {
            throw CliException.usage(e.getMessage());
        }
        return new Organisation.Input(o, fields);
    }
}
