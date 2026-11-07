package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.org.Organisation;
import nzi.fhir.validator.core.org.OrganisationRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code org create|update|deactivate|activate|show|list} (spec 005 FR-022, FR-023, contracts/cli.md).
 */
final class OrgCommands {
    private static final Logger logger = LogManager.getLogger(OrgCommands.class);

    private OrgCommands() {
    }

    static Future<Object> run(CliArgs.Command cmd, CliContext ctx) {
        return switch (cmd.command()) {
            case "create" -> create(cmd, ctx);
            case "update" -> update(cmd, ctx);
            case "deactivate" -> setActive(cmd, ctx, false);
            case "activate" -> setActive(cmd, ctx, true);
            case "show" -> show(cmd, ctx);
            case "list" -> list(cmd, ctx);
            default -> Future.failedFuture(CliException.usage("Unknown command 'org " + cmd.command() + "'"));
        };
    }

    private static Future<Object> create(CliArgs.Command cmd, CliContext ctx) {
        Organisation.Input input = CliArgs.organisationInput(cmd, false);
        return ctx.organisations().create(input.organisation())
                .map(o -> {
                    logger.info("cli: org create name_short={}", o.nameShort());
                    return o.toJson();
                });
    }

    private static Future<Object> update(CliArgs.Command cmd, CliContext ctx) {
        Organisation.Input input = CliArgs.organisationInput(cmd, true);
        if (input.fields().isEmpty()) {
            throw CliException.usage("Nothing to update: give --file, --rename or field options");
        }
        return select(cmd, ctx)
                .compose(o -> ctx.organisations().update(o.id(), input.organisation(), input.fields())
                        .map(updated -> {
                            logger.info("cli: org update name_short={} fields={}", updated.nameShort(), input.fields());
                            return updated.toJson();
                        }));
    }

    private static Future<Object> setActive(CliArgs.Command cmd, CliContext ctx, boolean active) {
        return select(cmd, ctx)
                .compose(o -> ctx.organisations().setActive(o.id(), active))
                .map(o -> {
                    logger.info("cli: org {} name_short={}", active ? "activate" : "deactivate", o.nameShort());
                    return o.toJson();
                });
    }

    private static Future<Object> show(CliArgs.Command cmd, CliContext ctx) {
        return select(cmd, ctx).compose(o -> ctx.keys().list(Optional.of(o.id())).map(keys -> {
            JsonArray summary = new JsonArray();
            keys.forEach(k -> summary.add(new JsonObject().put("name", k.name()).put("role", k.role().name())
                    .put("revoked", k.revoked())));
            return o.toJson().put("keys", summary);
        }));
    }

    private static Future<Object> list(CliArgs.Command cmd, CliContext ctx) {
        return ctx.organisations().list(cmd.bool("active")).map(all -> {
            JsonArray out = new JsonArray();
            all.forEach(o -> out.add(o.toJson()));
            return out;
        });
    }

    /** The organisation named by {@code --id} or {@code --name-short}. */
    static Future<Organisation> select(CliArgs.Command cmd, CliContext ctx) {
        Future<Optional<Organisation>> found;
        if (cmd.has("id")) {
            UUID id;
            try {
                id = UUID.fromString(cmd.required("id"));
            } catch (IllegalArgumentException e) {
                throw CliException.usage("--id must be a UUID");
            }
            found = ctx.organisations().findById(id);
        } else if (cmd.has("name-short")) {
            found = ctx.organisations().findByShortName(cmd.required("name-short"));
        } else {
            throw CliException.usage("Give --id or --name-short to select the organisation");
        }
        return found.compose(o -> o.isPresent() ? Future.succeededFuture(o.get())
                : Future.failedFuture(OrganisationRepository.notFound()));
    }
}
