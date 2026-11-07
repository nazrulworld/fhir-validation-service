package nzi.fhir.validator.cli;

import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.org.OrganisationRepository;
import nzi.fhir.validator.core.security.ApiKeyRepository;
import nzi.fhir.validator.core.security.Caller;
import nzi.fhir.validator.core.service.IgPackageService;

import java.io.InputStream;

/**
 * What a command needs: the same repositories and services as the HTTP path, so every change commits with the same
 * revision bumps and notifications (research R9). The command line acts as the system organisation: an operator with
 * database access is trusted.
 */
record CliContext(Vertx vertx, Pool pool, OrganisationRepository organisations, ApiKeyRepository keys,
                  IgPackageService packages, Caller system, InputStream stdin) {
}
