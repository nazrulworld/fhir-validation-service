package nzi.fhir.validator.core.model;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Pool;
import nzi.fhir.validator.core.enums.SupportedContentType;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * @author Md Nazrul Islam
 */
public class ValidationRequestContext {

    public static final String UUID_V4_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    final SupportedContentType contentType;
     final ValidatorIdentity validatorIdentity;
     final SupportedContentType acceptedContentType;
     final ValidationRequestOptions validationOptions;

    public ValidationRequestContext(SupportedContentType contentType, ValidatorIdentity validatorIdentity,
                                    SupportedContentType acceptedContentType, ValidationRequestOptions validationOptions) {
         this.contentType = contentType;
         this.validatorIdentity = validatorIdentity;
         this.acceptedContentType = acceptedContentType;
         this.validationOptions = validationOptions;
     }
    public static Future<ValidationRequestContext> fromRoutingContext(RoutingContext routingContext){
        return fromRoutingContext(routingContext, null);
    }

    public static Future<ValidationRequestContext> fromRoutingContext(RoutingContext routingContext, Pool pgPool) {
        HttpServerRequest request = routingContext.request();

        return createValidatorIdentity(routingContext, pgPool)
                .map(validatorId -> {
                    // Extract content type handling
                    SupportedContentType contentType = determineContentType(request);
                    SupportedContentType acceptedContentType = determineAcceptedContentType(request, contentType);
                    ValidationRequestOptions validationOptions = ValidationRequestOptions.fromRequest(request);

                    return new ValidationRequestContext(
                            contentType,
                            validatorId,
                            acceptedContentType,
                            validationOptions
                    );
                });
    }

    private static SupportedContentType determineContentType(HttpServerRequest request) {
        String contentTypeHeader = request.getHeader("Content-Type");
        return toValueOfSupportedContentType(contentTypeHeader, SupportedContentType.JSON);
    }

    private static SupportedContentType determineAcceptedContentType(HttpServerRequest request,
                                                                     SupportedContentType defaultContentType) {
        String acceptHeader = request.getHeader("Accept");
        return toValueOfSupportedContentType(acceptHeader, defaultContentType);
    }
    public static Future<ValidatorIdentity> createValidatorIdentity(RoutingContext routingContext, Pool pgPool){
        try {
             return Future.succeededFuture(ValidatorIdentity.createFromFhirVersion(SupportedFhirVersion.valueOf(routingContext.pathParam("version").toUpperCase())));
         } catch (IllegalArgumentException e) {
            if(pgPool != null && routingContext.pathParam("version").toLowerCase().matches(UUID_V4_PATTERN)) {

                return pgPool.withConnection(conn -> conn.preparedQuery("SELECT id, fhir_version, active FROM %s.api_clients WHERE id = $1".formatted(DB_POSTGRES_SCHEMA_NAME))
                        .execute(io.vertx.sqlclient.Tuple.of(routingContext.pathParam("version")))
                        .map(result -> {
                            if (result.size() == 1) {
                                return new ValidatorIdentity(result.iterator().next().getUUID("id").toString(), SupportedFhirVersion.valueOf(result.iterator().next().getString("fhir_version").toUpperCase()));
                            } else {
                                throw new IllegalArgumentException("Invalid validator identity id: " + routingContext.pathParam("version"));
                            }
                        })
                );
            }
            return Future.failedFuture(e);
         }
    }

    private static SupportedContentType toValueOfSupportedContentType(String headerValue, SupportedContentType defaultContentType) {
        SupportedContentType contentType = null;
        if (headerValue != null) {
           if(headerValue.startsWith(SupportedContentType.JSON.getMimeType())) {
                contentType = SupportedContentType.JSON;
            } else if(headerValue.startsWith(SupportedContentType.XML.getMimeType())) {
               contentType = SupportedContentType.XML;
           } else if (headerValue.startsWith(SupportedContentType.FHIR_JSON.getMimeType())) {
               contentType = SupportedContentType.FHIR_JSON;
           } else if (headerValue.startsWith(SupportedContentType.FHIR_XML.getMimeType())) {
               contentType = SupportedContentType.FHIR_XML;
           }
        } else {
            contentType = defaultContentType;
        }
        return contentType;
    }


    public SupportedContentType getContentType() {
        return contentType;
    }
    public SupportedContentType getAcceptedContentType() {
        return acceptedContentType;
    }

    public ValidationRequestOptions getValidationOptions() {
        return validationOptions;
    }

    public ValidatorIdentity getValidatorIdentity() {
        return validatorIdentity;
    }
}
