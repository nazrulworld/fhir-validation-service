package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.org.OrganisationRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * {@code usage summary (--org <short name> | --key <name>) [--since <ISO-8601 instant>]} (spec 008 FR-022,
 * contracts/operations.md §2): stored usage of one organisation or one key since a time (default: the last 24 hours),
 * from the {@code db} usage sink's table. Unknown CPU and allocation values (-1) count as 0.
 */
final class UsageCommands {
    private UsageCommands() {
    }

    static Future<Object> run(CliArgs.Command cmd, CliContext ctx) {
        if (!"summary".equals(cmd.command())) {
            return Future.failedFuture(CliException.usage("Unknown command 'usage " + cmd.command() + "'"));
        }
        boolean byOrg = cmd.has("org");
        boolean byKey = cmd.has("key");
        if (byOrg == byKey) {
            throw CliException.usage("Give exactly one of --org or --key");
        }
        Instant since;
        try {
            since = cmd.value("since").map(s -> Instant.parse(s.strip())).orElse(Instant.now().minus(Duration.ofHours(24)));
        } catch (DateTimeParseException e) {
            throw CliException.usage("--since must be an ISO-8601 instant, e.g. 2026-11-01T00:00:00Z");
        }
        Future<UUID> scope = byOrg
                ? ctx.organisations().findByShortName(cmd.required("org")).compose(o -> o.isPresent()
                ? Future.succeededFuture(o.get().id()) : Future.failedFuture(OrganisationRepository.notFound()))
                : ctx.keys().findIdByName(cmd.required("key")).compose(id -> id.isPresent()
                ? Future.succeededFuture(id.get()) : Future.failedFuture(nzi.fhir.validator.core.security.ApiKeyRepository.notFound()));
        JsonObject result = new JsonObject()
                .put("scope", new JsonObject().put(byOrg ? "org" : "key", cmd.required(byOrg ? "org" : "key")))
                .put("since", since.toString());
        nzi.fhir.validator.core.usage.UsageRepository.Scope kind = byOrg
                ? nzi.fhir.validator.core.usage.UsageRepository.Scope.ORG : nzi.fhir.validator.core.usage.UsageRepository.Scope.KEY;
        return scope.compose(id -> new nzi.fhir.validator.core.usage.UsageRepository(ctx.pool()).summary(kind, id, since))
                .map(summary -> (Object) result.mergeIn(summary));
    }
}
