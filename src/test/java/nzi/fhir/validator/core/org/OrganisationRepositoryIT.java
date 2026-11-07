package nzi.fhir.validator.core.org;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.service.ConfigRevisionRepository;
import nzi.fhir.validator.core.service.DatabaseService;
import nzi.fhir.validator.testcontainers.BaseTestContainer;
import nzi.fhir.validator.web.ProblemException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OrganisationRepository} against PostgreSQL (spec 005 FR-001 to FR-005).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
class OrganisationRepositoryIT extends BaseTestContainer {

    private Pool pool;
    private OrganisationRepository repo;

    @BeforeEach
    void setUp(Vertx vertx) {
        resetSchema();
        DatabaseService.flyway(connectOptions()).migrate();
        pool = createPgPool(vertx);
        repo = new OrganisationRepository(pool);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        pool.close().onComplete(ar -> ctx.completeNow());
    }

    private static Organisation acme() {
        return Organisation.empty().with(Organisation.NAME_SHORT, "acme").with(Organisation.NAME_LONG, "Acme Health ApS")
                .with(Organisation.TYPE, "COMMERCIAL").with(Organisation.COUNTRY, "DK")
                .with(Organisation.EMAIL, "info@acme.example").withAddressLines(List.of("Street 1", "2. sal"));
    }

    private static void assertProblem(Throwable err, int status, String type) {
        ProblemException p = assertInstanceOf(ProblemException.class, err);
        assertEquals(status, p.status(), p.detail());
        assertEquals(type, p.type());
    }

    @Test
    void createsAndFindsAnOrganisation(VertxTestContext ctx) {
        repo.create(acme())
                .compose(created -> repo.findByShortName("ACME").map(found -> {
                    assertEquals(created.id(), found.orElseThrow().id());
                    return created;
                }))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertNotNull(o.id());
                    assertTrue(o.active());
                    assertEquals(Organisations.Type.COMMERCIAL, o.type());
                    assertEquals(List.of("Street 1", "2. sal"), o.addressLines());
                    assertEquals("DK", o.country());
                    assertNotNull(o.createdAt());
                    assertNull(o.updatedAt());
                    ctx.completeNow();
                })));
    }

    @Test
    void updateChangesOnlyTheGivenFields(VertxTestContext ctx) {
        repo.create(acme())
                .compose(o -> repo.update(o.id(), Organisation.empty().with(Organisation.PHONE, "+45 12 34 56 78"),
                        Set.of(Organisation.PHONE)))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals("+45 12 34 56 78", o.phone());
                    assertEquals("info@acme.example", o.email(), "untouched");
                    assertEquals("Acme Health ApS", o.nameLong(), "untouched");
                    assertNotNull(o.updatedAt());
                    ctx.completeNow();
                })));
    }

    @Test
    void anEmptyValueClearsAnOptionalField(VertxTestContext ctx) {
        repo.create(acme())
                .compose(o -> repo.update(o.id(), Organisation.empty().with(Organisation.EMAIL, ""), Set.of(Organisation.EMAIL)))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertNull(o.email());
                    ctx.completeNow();
                })));
    }

    @Test
    void duplicateShortNameInAnotherCaseIsAConflict(VertxTestContext ctx) {
        repo.create(acme())
                .compose(o -> repo.create(acme().with(Organisation.NAME_SHORT, "ACME")))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 409, "conflict");
                    ctx.completeNow();
                })));
    }

    @Test
    void renameToAnExistingNameIsAConflict(VertxTestContext ctx) {
        repo.create(acme())
                .compose(a -> repo.create(acme().with(Organisation.NAME_SHORT, "beta")))
                .compose(b -> repo.update(b.id(), Organisation.empty().with(Organisation.NAME_SHORT, "Acme"),
                        Set.of(Organisation.NAME_SHORT)))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 409, "conflict");
                    ctx.completeNow();
                })));
    }

    @Test
    void invalidFieldIsABadRequestNamingTheField(VertxTestContext ctx) {
        repo.create(acme().with(Organisation.COUNTRY, "XX"))
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 400, "invalid-request");
                    assertTrue(((ProblemException) err).detail().contains("country"));
                    ctx.completeNow();
                })));
    }

    @Test
    void systemOrganisationIsProtected(VertxTestContext ctx) {
        Future<Throwable> create = repo.create(acme().with(Organisation.TYPE, "SYSTEM")).transform(ar -> Future.succeededFuture(ar.cause()));
        Future<Throwable> deactivate = repo.setActive(Organisations.SYSTEM_ID, false).transform(ar -> Future.succeededFuture(ar.cause()));
        Future<Throwable> retype = repo.update(Organisations.SYSTEM_ID, Organisation.empty().with(Organisation.TYPE, "COMMERCIAL"),
                Set.of(Organisation.TYPE)).transform(ar -> Future.succeededFuture(ar.cause()));
        Future.all(create, deactivate, retype).onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
            assertProblem(create.result(), 409, "conflict");
            assertProblem(deactivate.result(), 409, "conflict");
            assertProblem(retype.result(), 409, "conflict");
            ctx.completeNow();
        })));
    }

    @Test
    void unknownOrganisationIsNotFound(VertxTestContext ctx) {
        repo.setActive(java.util.UUID.randomUUID(), false)
                .onComplete(ctx.failing(err -> ctx.verify(() -> {
                    assertProblem(err, 404, "not-found");
                    ctx.completeNow();
                })));
    }

    @Test
    void renameBumpsTheCatalogRevision(VertxTestContext ctx) {
        repo.create(acme())
                .compose(o -> ConfigRevisionRepository.catalogRevision(pool).compose(before ->
                        repo.update(o.id(), Organisation.empty().with(Organisation.NAME_SHORT, "acme2"), Set.of(Organisation.NAME_SHORT))
                                .compose(v -> ConfigRevisionRepository.catalogRevision(pool))
                                .map(after -> after - before)))
                .compose(delta -> repo.findByShortName("acme").map(old -> Map.entry(delta, old)))
                .onComplete(ctx.succeeding(r -> ctx.verify(() -> {
                    assertEquals(1L, r.getKey(), "owner short names are refreshed on every instance (research R8)");
                    assertTrue(r.getValue().isEmpty(), "the old name is gone");
                    ctx.completeNow();
                })));
    }

    @Test
    void listFiltersByActiveStateAndOrdersByName(VertxTestContext ctx) {
        repo.create(acme().with(Organisation.NAME_SHORT, "zeta"))
                .compose(z -> repo.create(acme().with(Organisation.NAME_SHORT, "Beta")))
                .compose(b -> repo.setActive(b.id(), false))
                .compose(v -> Future.all(repo.list(Optional.empty()), repo.list(Optional.of(true)), repo.list(Optional.of(false))))
                .onComplete(ctx.succeeding(cf -> ctx.verify(() -> {
                    List<Organisation> all = cf.resultAt(0);
                    List<Organisation> active = cf.resultAt(1);
                    List<Organisation> inactive = cf.resultAt(2);
                    assertEquals(List.of("Beta", "system", "zeta"), all.stream().map(Organisation::nameShort).toList());
                    assertEquals(List.of("system", "zeta"), active.stream().map(Organisation::nameShort).toList());
                    assertEquals(List.of("Beta"), inactive.stream().map(Organisation::nameShort).toList());
                    ctx.completeNow();
                })));
    }

    @Test
    void applySystemConfigChangesOnlyTheGivenFields(VertxTestContext ctx) {
        repo.applySystemConfig(Map.of(Organisation.NAME_LONG, "Acme Ops", Organisation.ADDRESS_LINES, List.of("a", "b")))
                .compose(o -> repo.applySystemConfig(Map.of(Organisation.EMAIL, "ops@acme.example")))
                .onComplete(ctx.succeeding(o -> ctx.verify(() -> {
                    assertEquals("Acme Ops", o.nameLong());
                    assertEquals(List.of("a", "b"), o.addressLines());
                    assertEquals("ops@acme.example", o.email());
                    assertEquals("system", o.nameShort());
                    assertEquals(Organisations.Type.SYSTEM, o.type());
                    ctx.completeNow();
                })));
    }
}
