package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.org.OrganisationRepository;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code key create|revoke|list} (spec 005 FR-024, contracts/cli.md). A generated key is printed once, in the
 * {@code create} result, and only its SHA-256 hash is stored. Key values are never logged (FR-026).
 */
final class KeyCommands {
    private static final Logger logger = LogManager.getLogger(KeyCommands.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private KeyCommands() {
    }

    static Future<Object> run(CliArgs.Command cmd, CliContext ctx) {
        return switch (cmd.command()) {
            case "create" -> create(cmd, ctx);
            case "revoke" -> revoke(cmd, ctx);
            case "list" -> list(cmd, ctx);
            default -> Future.failedFuture(CliException.usage("Unknown command 'key " + cmd.command() + "'"));
        };
    }

    /** 32 random bytes, base64url without padding: 43 characters of {@code [A-Za-z0-9_-]}. */
    static String generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Future<Object> create(CliArgs.Command cmd, CliContext ctx) {
        String name = cmd.required("name");
        String orgName = cmd.required("org");
        ApiKeyRepository.Role role;
        try {
            role = ApiKeyRepository.Role.valueOf(cmd.required("role").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CliException.usage("--role must be ADMIN or VALIDATE");
        }
        if (ApiKeyRepository.isReserved(name)) {
            throw new CliException(CliException.CONFLICT, "conflict",
                    "The key name '" + name + "' is reserved for a well-known key");
        }
        if (name.length() > 64) {
            throw CliException.usage("--name must be at most 64 characters");
        }
        java.time.Instant expiresAt = cmd.value("expires").map(KeyCommands::futureInstant).orElse(null);
        boolean fromStdin = cmd.has("key-stdin");
        String key = fromStdin ? readKey(ctx) : generateKey();
        if (!ApiKeyRepository.isWellFormed(key)) {
            throw CliException.usage("The key must be 32-128 characters of [A-Za-z0-9_-]");
        }
        return ctx.organisations().findByShortName(orgName)
                .compose(org -> org.isPresent() ? Future.succeededFuture(org.get())
                        : Future.failedFuture(OrganisationRepository.notFound()))
                .compose(org -> ctx.keys().create(name, key, role, org.id(), expiresAt).map(v -> {
                    logger.info("cli: key create name={} org={} role={} expires={}", name, org.nameShort(), role,
                            expiresAt == null ? "never" : expiresAt);
                    JsonObject result = new JsonObject().put("name", name).put("role", role.name()).put("org", org.nameShort())
                            .put("expiresAt", expiresAt == null ? null : expiresAt.toString());
                    if (!fromStdin) {
                        // Printed here, once; never stored or logged.
                        result.put("key", key);
                    }
                    return result;
                }));
    }

    /** Spec 008 FR-006: {@code --expires} is an ISO-8601 instant in the future. */
    private static java.time.Instant futureInstant(String value) {
        java.time.Instant at;
        try {
            at = java.time.Instant.parse(value.strip());
        } catch (java.time.format.DateTimeParseException e) {
            at = null;
        }
        if (at == null || !at.isAfter(java.time.Instant.now())) {
            throw CliException.usage("--expires must be a future ISO-8601 instant, e.g. 2027-01-01T00:00:00Z");
        }
        return at;
    }

    private static String readKey(CliContext ctx) {
        try {
            String text = new String(ctx.stdin().readNBytes(256), StandardCharsets.UTF_8).strip();
            int newline = text.indexOf('\n');
            return newline < 0 ? text : text.substring(0, newline).strip();
        } catch (IOException e) {
            throw CliException.usage("Cannot read the key from stdin");
        }
    }

    private static Future<Object> revoke(CliArgs.Command cmd, CliContext ctx) {
        String name = cmd.required("name");
        if (ApiKeyRepository.isReserved(name)) {
            // Spec 008: allowed. The key stays revoked until its variable is set to a new value (upsertWellKnown keeps
            // the revocation while the value is unchanged).
            logger.warn("cli: '{}' is installed from configuration; it stays revoked until its variable is set to a new "
                    + "value", name);
        }
        return ctx.keys().revokeNamed(name).map(org -> {
            logger.info("cli: key revoke name={} org={}", name, org);
            return new JsonObject().put("name", name).put("revoked", true);
        });
    }

    private static Future<Object> list(CliArgs.Command cmd, CliContext ctx) {
        Future<Optional<java.util.UUID>> owner = cmd.has("org")
                ? ctx.organisations().findByShortName(cmd.required("org")).compose(org -> org.isPresent()
                ? Future.succeededFuture(Optional.of(org.get().id())) : Future.failedFuture(OrganisationRepository.notFound()))
                : Future.succeededFuture(Optional.empty());
        return owner.compose(o -> ctx.keys().list(o)).map(keys -> {
            JsonArray out = new JsonArray();
            keys.forEach(k -> out.add(k.toJson()));
            return out;
        });
    }
}
