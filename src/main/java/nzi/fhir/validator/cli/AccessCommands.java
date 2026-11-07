package nzi.fhir.validator.cli;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.security.Access;
import nzi.fhir.validator.core.security.AccessChange;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.core.validator.IgReference;
import nzi.fhir.validator.web.ProblemException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * {@code package share|transfer} and {@code snapshot share|transfer} (spec 005 FR-019, contracts/cli.md). They run
 * as the system organisation through the same service methods as the HTTP PATCH operations, so revision bumps and
 * notifications are identical and running instances apply them within the propagation window.
 */
final class AccessCommands {
    private static final Logger logger = LogManager.getLogger(AccessCommands.class);

    private AccessCommands() {
    }

    static Future<Object> run(CliArgs.Command cmd, CliContext ctx) {
        boolean snapshot = cmd.group().equals("snapshot");
        boolean transfer = cmd.command().equals("transfer");
        IgReference ref;
        try {
            ref = IgReference.parse(cmd.required("ig"));
        } catch (ProblemException e) {
            throw CliException.usage("--ig must be name#version");
        }
        if (ref.version() == null) {
            throw CliException.usage("--ig must be an exact name#version");
        }
        AccessChange change = transfer
                ? AccessChange.of(Optional.empty(), Optional.of(cmd.required("to")))
                : AccessChange.of(Optional.of(cmd.bool("shareable")
                .orElseThrow(() -> CliException.usage("--shareable is required for '" + cmd.name() + "'"))), Optional.empty());
        String ig = ref.label();
        if (!snapshot) {
            return ctx.packages().access(ref.name(), ref.version())
                    .compose(before -> ctx.packages().changePackageAccess(ctx.system(), ref.name(), ref.version(), change)
                            .map(r -> {
                                log(cmd, ig, null, before.map(Access::ownerShortName).orElse(null), r);
                                JsonObject out = r.copy();
                                out.remove("status");
                                return (Object) out;
                            }));
        }
        String label = cmd.required("fhir-version");
        SupportedFhirVersion version = SupportedFhirVersion.parseEnabled(label)
                .orElseThrow(() -> CliException.usage("--fhir-version must be one of the enabled FHIR versions"));
        return ConfigRevisionRepository.current(ctx.pool())
                .map(rows -> rows.get(version) == null ? null : rows.get(version).access().get(ig))
                .compose(before -> ctx.packages().changeSnapshotAccess(ctx.system(), version, ig, change).map(r -> {
            log(cmd, ig, version.name(), before == null ? null : before.ownerShortName(), r);
            JsonObject out = r.copy();
            out.remove("status");
            out.remove("warnings");
            return (Object) out;
        }));
    }

    private static void log(CliArgs.Command cmd, String ig, String fhirVersion, String from, JsonObject r) {
        String scope = fhirVersion == null ? "" : " fhir_version=" + fhirVersion;
        if (cmd.command().equals("transfer")) {
            logger.info("cli: {} transfer ig={}{} from={} to={}", cmd.group(), ig, scope, from == null ? "-" : from,
                    r.getString("owner"));
        } else {
            logger.info("cli: {} share ig={}{} owner={} shareable={}", cmd.group(), ig, scope, r.getString("owner"),
                    r.getBoolean("shareable"));
        }
    }
}
