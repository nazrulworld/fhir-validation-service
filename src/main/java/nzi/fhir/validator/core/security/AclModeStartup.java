package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.StartupException;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.core.security.ApiKeyRepository.Role;
import nzi.fhir.validator.core.settings.ServiceSettingsRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The startup access-control step (spec 009 FR-021 to FR-029, research R11). It replaces 4.1's two key-install steps
 * and runs as one transaction, serialised across instances on the {@code service_settings.acl_security} row:
 * <ol>
 *     <li>reads the mode stored by the last start (none on a fresh or just-upgraded database);</li>
 *     <li>refuses a start whose mode differs from the stored one unless {@code FV_ACL_MODE_CHANGE_CONFIRMED} is set
 *     (FR-029; nothing changes);</li>
 *     <li>disabled: gives both fixed-caller keys new 256-bit random values that nobody ever sees (FR-021), ignoring
 *     {@code FV_ADMIN_API_KEY} / {@code FV_VALIDATOR_API_KEY} (FR-022);</li>
 *     <li>enabled: installs the configured values exactly as 4.1 did (FR-027); after a disabled run this is the
 *     re-synchronisation (FR-025);</li>
 *     <li>stores the mode (FR-024).</li>
 * </ol>
 * Log lines are written after the commit and never contain a key value.
 */
public final class AclModeStartup {
    private static final Logger logger = LogManager.getLogger(AclModeStartup.class);
    static final String RESYNC_MESSAGE = "API keys re-synchronised from environment";

    /** Test hook (spec 009 T039): sees each generated value, so a test can prove it is never logged. */
    private static volatile Consumer<String> generatedValueListener = v -> { };

    /** Test hook (spec 009 T039): {@code listener} sees each generated key value; {@code null} resets it. */
    public static void setGeneratedValueListenerForTests(Consumer<String> listener) {
        generatedValueListener = listener == null ? v -> { } : listener;
    }

    private AclModeStartup() {
    }

    /** One fixed caller key, with the variable it is installed from in enabled mode. */
    private record FixedKey(String variable, UUID id, String name, Role role, UUID owner) {
    }

    private static final List<FixedKey> FIXED_KEYS = List.of(
            new FixedKey("FV_ADMIN_API_KEY", Constant.SYS_ORG_ADMIN_API_KEY_UUID, Constant.SYS_ORG_ADMIN_API_KEY_NAME,
                    Role.ADMIN, Constant.SYSTEM_ORG_UUID),
            new FixedKey("FV_VALIDATOR_API_KEY", Constant.TRIAL_ORG_VALIDATOR_API_KEY_UUID,
                    Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME, Role.VALIDATE, Constant.TRIAL_ORG_UUID));

    /**
     * Runs the step. On success the mode is stored and {@code auth}'s gate is open; on a refused mode change it fails
     * with {@link StartupException} (the process exits 1) and nothing changed.
     */
    public static Future<AclMode> run(Pool pool, ApiKeyRepository apiKeys, ApplicationConfig config, ApiKeyAuthHandler auth) {
        return run(pool, apiKeys, config, auth, (tx, afterCommit) -> Future.succeededFuture());
    }

    /**
     * Runs the step with {@code sameTransaction} as its last part, in the same transaction (spec 009 T069): startup
     * passes the served-versions reconciliation, so a start refused by either guard (FR-029, FR-033) changes no key, no
     * stored setting and no context, and the gate opens only after both have committed. {@code sameTransaction} adds
     * its log lines to the list it is given; they run after the commit.
     */
    public static Future<AclMode> run(Pool pool, ApiKeyRepository apiKeys, ApplicationConfig config, ApiKeyAuthHandler auth,
                                      BiFunction<SqlClient, List<Runnable>, Future<?>> sameTransaction) {
        AclMode mode = config.aclMode();
        List<Runnable> afterCommit = new ArrayList<>();
        return pool.withTransaction(tx -> previousMode(tx, mode)
                        .compose(previous -> {
                            guard(previous, mode, config.aclModeChangeConfirmed(), afterCommit);
                            Future<Void> keys = mode == AclMode.DISABLED
                                    ? randomiseKeys(tx, apiKeys, config, afterCommit)
                                    : installConfiguredKeys(tx, apiKeys, config,
                                    previous.filter(p -> p == AclMode.DISABLED).isPresent(), afterCommit);
                            return keys.compose(v -> ServiceSettingsRepository.upsert(tx, ServiceSettingsRepository.ACL_SECURITY,
                                            mode.value(), ServiceSettingsRepository.instanceName()))
                                    .compose(v -> sameTransaction.apply(tx, afterCommit));
                        }))
                .onSuccess(v -> {
                    afterCommit.forEach(Runnable::run);
                    auth.openGate();
                    if (mode == AclMode.DISABLED) {
                        // FR-023, FR-030.
                        logger.warn("ACCESS CONTROL IS DISABLED (FV_GLOBAL_DISABLED_ACL_SECURITY): no endpoint needs a key; "
                                + "administrative endpoints are {}", config.adminCidrs().isEmpty()
                                ? "open from any network" : "limited to FV_AUTH_ADMIN_CIDRS");
                    }
                })
                // Code review of PR #72: any failure is fatal (exit 1, the orchestrator restarts the instance); otherwise
                // the instance would stay up with its gate closed, answering every keyed request 503.
                .recover(err -> Future.failedFuture(err instanceof StartupException ? err
                        : new StartupException("the access-control startup step failed: " + err.getMessage(), err)))
                .map(mode);
    }

    /**
     * The mode stored by the previous start, with its row locked: empty when there is none. Concurrent first starts
     * serialise: the first inserts the row, the others then lock it and see that start's mode.
     */
    private static Future<Optional<AclMode>> previousMode(SqlClient tx, AclMode mode) {
        String setting = ServiceSettingsRepository.ACL_SECURITY;
        return ServiceSettingsRepository.lockForUpdate(tx, setting).compose(stored -> {
            if (stored.isPresent()) {
                return Future.succeededFuture(stored.map(AclMode::fromValue));
            }
            return ServiceSettingsRepository.insertIfAbsent(tx, setting, mode.value())
                    .compose(inserted -> inserted
                            ? Future.succeededFuture(Optional.<AclMode>empty())
                            : ServiceSettingsRepository.lockForUpdate(tx, setting).map(s -> s.map(AclMode::fromValue)));
        });
    }

    /** FR-029: a different stored mode needs the confirmation; the confirmation with an unchanged mode is warned about. */
    private static void guard(Optional<AclMode> previous, AclMode mode, boolean confirmed, List<Runnable> afterCommit) {
        if (previous.isEmpty()) {
            return;
        }
        AclMode stored = previous.get();
        if (stored != mode) {
            if (!confirmed) {
                throw new StartupException("access control mode change refused: stored mode is " + stored.value()
                        + ", this instance is configured " + mode.value() + ". To switch, set FV_ACL_MODE_CHANGE_CONFIRMED=true "
                        + "on the instances being switched (see docs/upgrade-4.2.md)");
            }
            afterCommit.add(() -> logger.warn("Access control mode changes from {} to {} (FV_ACL_MODE_CHANGE_CONFIRMED)",
                    stored.value(), mode.value()));
        } else if (confirmed) {
            afterCommit.add(() -> logger.warn("FV_ACL_MODE_CHANGE_CONFIRMED is set but the mode is unchanged ({}); remove it so "
                    + "an accidental mode change is refused", mode.value()));
        }
    }

    /** FR-021, FR-022: both fixed keys get fresh random values; configured values are ignored, and never printed. */
    private static Future<Void> randomiseKeys(SqlClient tx, ApiKeyRepository apiKeys, ApplicationConfig config,
                                              List<Runnable> afterCommit) {
        Future<Void> chain = Future.succeededFuture();
        for (FixedKey key : FIXED_KEYS) {
            String configured = configured(config, key);
            if (configured != null) {
                afterCommit.add(() -> logger.info("{} is ignored: access control is disabled", key.variable()));
            }
            // Code review of PR #72: a revoked fixed key stays revoked across restarts (an operator may revoke the trial
            // validator to stop anonymous validation); only its value is replaced.
            chain = chain.compose(v -> apiKeys.upsertWellKnown(tx, key.id(), key.name(), randomKey(), key.role(), key.owner(),
                            Constant.SYS_ORG_API_KEY_EXPIRES_AT, true)
                    .compose(installed -> apiKeys.isRevoked(tx, key.id()))
                    .map(revoked -> {
                        if (revoked) {
                            afterCommit.add(() -> logger.warn("The '{}' key is revoked: requests that run as it are refused "
                                    + "with 403 while access control is disabled, also after restarts", key.name()));
                        }
                        return null;
                    }));
        }
        return chain;
    }

    /**
     * FR-025, FR-027: the configured values, installed as 4.1 did. An unset variable changes nothing in the database
     * (instances may be configured differently); a revoked value is never installed again (V13).
     */
    private static Future<Void> installConfiguredKeys(SqlClient tx, ApiKeyRepository apiKeys, ApplicationConfig config,
                                                      boolean afterDisabledRun, List<Runnable> afterCommit) {
        if (afterDisabledRun) {
            afterCommit.add(() -> logger.info(RESYNC_MESSAGE));
        }
        Future<Void> chain = Future.succeededFuture();
        for (FixedKey key : FIXED_KEYS) {
            String value = configured(config, key);
            if (value == null) {
                afterCommit.add(() -> {
                    if (afterDisabledRun) {
                        logger.warn("{} is not set: the '{}' key keeps an unknown value and can't be used until it is set",
                                key.variable(), key.name());
                    } else {
                        // Spec 008 US2/AC4: without the trial key, validation still needs a key created with 'key create'.
                        logger.warn("{} is not set: {}this instance installs no '{}' key (one stored earlier stays valid "
                                + "until revoked)", key.variable(), key.role() == Role.VALIDATE
                                ? "validation is key-only and " : "", key.name());
                    }
                });
                continue;
            }
            chain = chain.compose(v -> apiKeys.upsertWellKnown(tx, key.id(), key.name(), value, key.role(), key.owner(),
                            Constant.SYS_ORG_API_KEY_EXPIRES_AT)
                    .map(installed -> {
                        afterCommit.add(() -> {
                            if (installed) {
                                logger.info("'{}' API key installed ({})", key.name(), key.variable());
                            } else {
                                // V13: a revoked value is never installed again; the stored key is left as it is.
                                logger.error("{} is set to a value that was revoked: this instance does not install it, and the "
                                        + "stored '{}' key is unchanged. Configure a new value.", key.variable(), key.name());
                            }
                        });
                        return null;
                    }));
        }
        return chain;
    }

    private static String configured(ApplicationConfig config, FixedKey key) {
        String value = key.role() == Role.ADMIN ? config.bootstrapAdminKey() : config.validatorKey();
        return value == null || value.isBlank() ? null : value;
    }

    /** A 256-bit random value from the shared key generator (FR-021); the test hook sees it. */
    static String randomKey() {
        String value = ApiKeyRepository.generateKey();
        generatedValueListener.accept(value);
        return value;
    }

}
