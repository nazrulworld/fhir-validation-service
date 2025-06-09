package nzi.fhir.validator.model;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import nzi.fhir.validator.web.enums.SupportedContentType;
import nzi.fhir.validator.web.enums.SupportedFhirVersion;

/**
 * @author Md Nazrul Islam
 */
public class ValidationRequestContext {

     final SupportedContentType contentType;
     final SupportedFhirVersion fhirVersion;
     final SupportedContentType acceptedContentType;
     final ValidationRequestOptions validationOptions;

    public ValidationRequestContext(SupportedContentType contentType, SupportedFhirVersion fhirVersion,
                                    SupportedContentType acceptedContentType, ValidationRequestOptions validationOptions) {
         this.contentType = contentType;
         this.fhirVersion = fhirVersion;
         this.acceptedContentType = acceptedContentType;
         this.validationOptions = validationOptions;
     }

     public static ValidationRequestContext fromRoutingContext(RoutingContext routingContext){
         HttpServerRequest request = routingContext.request();

         SupportedFhirVersion fhirVersion = SupportedFhirVersion.valueOf(routingContext.pathParam("version").toUpperCase());
         String contentTypeHeader = request.getHeader("Content-Type");
         SupportedContentType contentType = toValueOfSupportedContentType(contentTypeHeader, SupportedContentType.JSON);
         SupportedContentType acceptedContentType = toValueOfSupportedContentType(request.getHeader("Accept"), contentType);
         ValidationRequestOptions validationOptions = ValidationRequestOptions.fromRequest(request);
         return new ValidationRequestContext(contentType,fhirVersion, acceptedContentType, validationOptions);
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

    public SupportedFhirVersion getFhirVersion() {
        return fhirVersion;
    }

    public SupportedContentType getAcceptedContentType() {
        return acceptedContentType;
    }

    public ValidationRequestOptions getValidationOptions() {
        return validationOptions;
    }
}
