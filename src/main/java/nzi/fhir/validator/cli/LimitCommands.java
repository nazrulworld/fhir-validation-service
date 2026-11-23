package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.org.OrganisationRepository;
import nzi.fhir.validator.core.org.Organisations;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.settings.ServiceSettingsRepository;
import nzi.fhir.validator.core.throttle.RateLimitRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code limit set | remove | list} (spec 014 FR-013, contracts/operations.md §2): the per-minute validation caps of
 * keys, organisations and the two service-wide defaults. Every change runs in one transaction and is logged with the old
 * and new value (FR-016). The system organisation and its keys are never rate limited (FR-006b): a cap for them is a
 * conflict. Running instances apply a change within {@code FV_THROTTLE_CAPS_REFRESH_MS} (FR-014).
 */
final class LimitCommands {
    private static final Logger logger = LogManager.getLogger(LimitCommands.class);
    static final String SYSTEM_NEVER_LIMITED = "The system organisation and its keys are never rate limited";

    private LimitCommands() {
    }

    private enum Target {
        KEY("key"), ORGANISATION("organisation"), DEFAULT_KEY("default-key"), DEFAULT_ORG("default-organisation");

        final String label;

        Target(String label) {
            this.label = label;
        }
    }

    static Future<Object> run(CliArgs.Command cmd, CliContext ctx) {
        return switch (cmd.command()) {
            case "set" -> change(cmd, ctx, perMinute(cmd));
            case "remove" -> change(cmd, ctx, null);
            case "list" -> list(cmd, ctx);
            default -> Future.failedFuture(CliException.usage("Unknown command 'limit " + cmd.command() + "'"));
        };
    }

    private static Target target(CliArgs.Command cmd) {
        List<Target> given = new java.util.ArrayList<>();
        if (cmd.has("key")) {
            given.add(Target.KEY);
        }
        if (cmd.has("org")) {
            given.add(Target.ORGANISATION);
        }
        if (cmd.has("default-key")) {
            given.add(Target.DEFAULT_KEY);
        }
        if (cmd.has("default-org")) {
            given.add(Target.DEFAULT_ORG);
        }
        if (given.size() != 1) {
            throw CliException.usage("Give exactly one of --key <name>, --org <short name>, --default-key or --default-org");
        }
        return given.get(0);
    }

    /** FR-012: a whole number from 1 to 1,000,000. */
    private static int perMinute(CliArgs.Command cmd) {
        String v = cmd.required("per-minute");
        if (!v.matches("[0-9]{1,7}") || Integer.parseInt(v) < 1 || Integer.parseInt(v) > Constant.RATE_LIMIT_MAX_PER_MINUTE) {
            throw CliException.usage("--per-minute must be a whole number from 1 to " + Constant.RATE_LIMIT_MAX_PER_MINUTE);
        }
        return Integer.parseInt(v);
    }

    private static Integer parse(Optional<String> setting) {
        return setting.map(Integer::valueOf).orElse(null);
    }

    private static Future<Object> change(CliArgs.Command cmd, CliContext ctx, Integer perMinute) {
        Target target = target(cmd);
        String verb = perMinute == null ? "remove" : "set";
        return ctx.pool().withTransaction(tx -> switch (target) {
            case KEY, ORGANISATION -> changeSubject(tx, target, cmd.required(target == Target.KEY ? "key" : "org"), perMinute);
            case DEFAULT_KEY, DEFAULT_ORG -> changeDefault(tx, target, perMinute);
        }).map(result -> {
            logger.info("cli: limit {} {}={} id={} old={} new={}", verb, target == Target.DEFAULT_ORG ? "default-org" : target.label,
                    result.getString("name", "default"), result.getString("id", "-"), label(result.getInteger("previous")),
                    label(result.getInteger("perMinute")));
            return result;
        });
    }

    private static String label(Integer cap) {
        return cap == null ? "none" : String.valueOf(cap);
    }

    private static Future<JsonObject> changeSubject(SqlClient tx, Target target, String name, Integer perMinute) {
        boolean key = target == Target.KEY;
        return (key ? RateLimitRepository.lockKey(tx, name) : RateLimitRepository.lockOrganisation(tx, name))
                .compose(found -> {
                    if (found.isEmpty()) {
                        // contracts/operations.md §2.1: the error names what wasn't found.
                        return Future.failedFuture(key ? ApiKeyRepository.notFound(name) : OrganisationRepository.notFound(name));
                    }
                    RateLimitRepository.Target t = found.get();
                    if (Organisations.SYSTEM_ID.equals(t.owner())) {
                        return Future.failedFuture(new CliException(CliException.CONFLICT, "conflict", SYSTEM_NEVER_LIMITED));
                    }
                    Future<Void> write = java.util.Objects.equals(t.perMinute(), perMinute) ? Future.succeededFuture()
                            : key ? RateLimitRepository.setKey(tx, t.id(), perMinute)
                            : RateLimitRepository.setOrganisation(tx, t.id(), perMinute);
                    String setting = key ? ServiceSettingsRepository.THROTTLE_DEFAULT_KEY : ServiceSettingsRepository.THROTTLE_DEFAULT_ORG;
                    return write.compose(v -> ServiceSettingsRepository.get(tx, setting)).map(def -> new JsonObject()
                            .put("target", target.label).put("name", t.name()).put("id", t.id().toString())
                            .put("previous", t.perMinute()).put("perMinute", perMinute)
                            .put("changed", !java.util.Objects.equals(t.perMinute(), perMinute))
                            .put("effective", effective(perMinute, parse(def))));
                });
    }

    private static Future<JsonObject> changeDefault(SqlClient tx, Target target, Integer perMinute) {
        String setting = target == Target.DEFAULT_KEY ? ServiceSettingsRepository.THROTTLE_DEFAULT_KEY
                : ServiceSettingsRepository.THROTTLE_DEFAULT_ORG;
        return ServiceSettingsRepository.lockForUpdate(tx, setting).compose(current -> {
            Integer previous = parse(current);
            Future<?> write = java.util.Objects.equals(previous, perMinute) ? Future.succeededFuture()
                    : perMinute == null ? ServiceSettingsRepository.delete(tx, setting)
                    : ServiceSettingsRepository.upsert(tx, setting, String.valueOf(perMinute),
                    "cli:" + ServiceSettingsRepository.instanceName());
            return write.map(v -> new JsonObject().put("target", target.label)
                    .put("previous", previous).put("perMinute", perMinute)
                    .put("changed", !java.util.Objects.equals(previous, perMinute))
                    .put("effective", new JsonObject().put("perMinute", perMinute).put("source", perMinute == null ? "none" : "own")));
        });
    }

    /** FR-013: the cap that applies, and where it comes from: {@code own}, {@code default} or {@code none}. */
    private static JsonObject effective(Integer own, Integer fallback) {
        if (own != null) {
            return new JsonObject().put("perMinute", own).put("source", "own");
        }
        return fallback != null ? new JsonObject().put("perMinute", fallback).put("source", "default")
                : new JsonObject().putNull("perMinute").put("source", "none");
    }

    private static JsonObject notThrottled() {
        return new JsonObject().putNull("perMinute").put("source", "not-throttled");
    }

    private static Future<Object> list(CliArgs.Command cmd, CliContext ctx) {
        String only = cmd.has("org") ? cmd.required("org") : null;
        return ctx.pool().withConnection(c -> ServiceSettingsRepository.get(c, ServiceSettingsRepository.THROTTLE_DEFAULT_KEY)
                .compose(dk -> ServiceSettingsRepository.get(c, ServiceSettingsRepository.THROTTLE_DEFAULT_ORG)
                        .compose(dorg -> RateLimitRepository.organisations(c, only)
                                .compose(orgs -> {
                                    if (only != null && orgs.isEmpty()) {
                                        return Future.failedFuture(OrganisationRepository.notFound(only));
                                    }
                                    return RateLimitRepository.keys(c).map(keys -> listJson(parse(dk), parse(dorg), orgs, keys));
                                }))));
    }

    private static Object listJson(Integer defaultKey, Integer defaultOrg, List<RateLimitRepository.OrgRow> orgs,
                                   List<RateLimitRepository.KeyRow> keys) {
        Map<UUID, List<RateLimitRepository.KeyRow>> byOwner = keys.stream()
                .collect(Collectors.groupingBy(RateLimitRepository.KeyRow::owner));
        JsonArray out = new JsonArray();
        for (RateLimitRepository.OrgRow o : orgs) {
            boolean system = Organisations.SYSTEM_ID.equals(o.id());
            JsonArray ks = new JsonArray();
            for (RateLimitRepository.KeyRow k : byOwner.getOrDefault(o.id(), List.of())) {
                ks.add(new JsonObject().put("name", k.name()).put("id", k.id().toString()).put("role", k.role())
                        .put("revoked", k.revoked()).put("perMinute", k.perMinute())
                        .put("effective", system ? notThrottled() : effective(k.perMinute(), defaultKey)));
            }
            out.add(new JsonObject().put("name", o.name()).put("id", o.id().toString()).put("perMinute", o.perMinute())
                    .put("effective", system ? notThrottled() : effective(o.perMinute(), defaultOrg))
                    .put("keys", ks));
        }
        return new JsonObject()
                .put("defaults", new JsonObject().put("key", defaultKey).put("organisation", defaultOrg))
                .put("organisations", out);
    }
}
