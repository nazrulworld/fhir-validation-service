package nzi.fhir.validator.core.security;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.Constant;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Spec 008 code review regressions for keys installed from configuration ({@code upsertWellKnown}). */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class ApiKeyRepositoryIT extends BaseTestContainer {
    private static final String KEY = "trial-key-0123456789abcdefghijklmnopqrst";
    private static final String ROTATED = "rotated-key-0123456789abcdefghijklmnopqr";

    private Pool pool;
    private ApiKeyRepository repo;

    @BeforeEach
    void setUp(Vertx vertx) {
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        pool = createPgPool(vertx);
        repo = new ApiKeyRepository(pool);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        pool.close().onComplete(ar -> ctx.completeNow());
    }

    private Future<Void> install(String key) {
        return repo.upsertWellKnown(Constant.TRIAL_ORG_VALIDATOR_API_KEY_UUID, Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME, key,
                ApiKeyRepository.Role.VALIDATE, Constant.TRIAL_ORG_UUID, Constant.SYS_ORG_API_KEY_EXPIRES_AT).mapEmpty();
    }

    /** A revoked seeded key stays revoked while the configured value is unchanged; a new value un-revokes it. */
    @Test
    void aRevocationSurvivesReinstallingTheSameValue(VertxTestContext ctx) {
        install(KEY)
                .compose(v -> repo.revokeNamed(Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME))
                .compose(org -> install(KEY)) // an instance restarting with the old (leaked) value
                .compose(v -> repo.findActive(KEY))
                .compose(stillRevoked -> {
                    ctx.verify(() -> assertTrue(stillRevoked.isEmpty(), "the leaked key stays revoked"));
                    return install(ROTATED); // the operator deploys a new value
                })
                .compose(v -> Future.all(repo.findActive(ROTATED), repo.findActive(KEY)))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertTrue(((java.util.Optional<?>) cf.resultAt(0)).isPresent(), "the new value works");
                    assertTrue(((java.util.Optional<?>) cf.resultAt(1)).isEmpty(), "the old value doesn't");
                    ctx.completeNow();
                })));
    }

    /** Instances starting at once on a database without the row all succeed (no fatal unique violation). */
    @Test
    void concurrentInstallsAllSucceed(VertxTestContext ctx) {
        List<Future<Void>> installs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            installs.add(install(KEY));
        }
        Future.all(installs)
                .compose(cf -> pool.query("SELECT count(*) FROM fhir_validator_schema.api_keys").execute())
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(1, rows.iterator().next().getInteger(0));
                    ctx.completeNow();
                })));
    }

    /**
     * Code review regression (V13): a revoked value is never installed again, also after the key was rotated, so an
     * instance still configured with a leaked value can't bring it back.
     */
    @Test
    void aRevokedValueCannotComeBackAfterARotation(VertxTestContext ctx) {
        install(KEY)
                .compose(v -> repo.revokeNamed(Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME)) // KEY leaked: revoke it
                .compose(org -> install(ROTATED)) // an instance with the new value
                .compose(v -> repo.upsertWellKnown(Constant.TRIAL_ORG_VALIDATOR_API_KEY_UUID,
                        Constant.TRIAL_ORG_VALIDATOR_API_KEY_NAME, KEY, ApiKeyRepository.Role.VALIDATE, Constant.TRIAL_ORG_UUID,
                        Constant.SYS_ORG_API_KEY_EXPIRES_AT)) // an instance still configured with the leaked value restarts
                .compose(installed -> {
                    ctx.verify(() -> assertFalse(installed, "the leaked value is refused"));
                    return Future.all(repo.findActive(ROTATED), repo.findActive(KEY));
                })
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    assertTrue(((java.util.Optional<?>) cf.resultAt(0)).isPresent(), "the rotated key still works");
                    assertTrue(((java.util.Optional<?>) cf.resultAt(1)).isEmpty(), "the leaked key stays dead");
                    ctx.completeNow();
                })));
    }

    /** A revocation by hand-written SQL is recorded too (the trigger). */
    @Test
    void aRevocationBySqlIsRecorded(VertxTestContext ctx) {
        install(KEY)
                .compose(v -> pool.query("UPDATE fhir_validator_schema.api_keys SET revoked_at = now() WHERE id = '"
                        + Constant.TRIAL_ORG_VALIDATOR_API_KEY_ID + "'").execute())
                .compose(v -> pool.query("SELECT count(*) FROM fhir_validator_schema.revoked_key_hashes").execute())
                .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
                    assertEquals(1, rows.iterator().next().getInteger(0));
                    ctx.completeNow();
                })));
    }
}
