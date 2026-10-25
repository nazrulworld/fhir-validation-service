package nzi.fhir.validator.core.model;

import nzi.fhir.validator.core.model.MediaTypes.RequestFormat;
import nzi.fhir.validator.core.model.MediaTypes.ResponseFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class MediaTypesTest {

    @ParameterizedTest(name = "Accept: {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "*/*|JSON_LEGACY",
            "application/json|JSON_LEGACY",
            "application/json; charset=utf-8|JSON_LEGACY",
            "application/*|JSON_LEGACY",
            "application/fhir+json|FHIR_JSON",
            "application/fhir+xml|FHIR_XML",
            "application/xml|FHIR_XML",
            "application/fhir+json;q=0.5, application/fhir+xml|FHIR_XML",
            "text/html, application/fhir+json;q=0.9|FHIR_JSON",
            "application/fhir+xml;q=0, */*;q=0.1|JSON_LEGACY",
            "text/plain|NONE",
            "image/png, text/csv|NONE",
    })
    void negotiatesResponseFormat(String accept, ResponseFormat expected) {
        assertEquals(expected, MediaTypes.responseFormat(accept));
    }

    @Test
    void absentAcceptIsLegacyJson() {
        assertEquals(ResponseFormat.JSON_LEGACY, MediaTypes.responseFormat(null));
        assertEquals(ResponseFormat.JSON_LEGACY, MediaTypes.responseFormat(" "));
    }

    @ParameterizedTest(name = "Content-Type: {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "application/json|JSON",
            "application/json; charset=utf-8|JSON",
            "application/fhir+json|JSON",
            "application/x+json|JSON",
            "text/json|JSON",
            "application/xml|XML",
            "application/fhir+xml;charset=UTF-8|XML",
            "text/xml|XML",
            "text/csv|UNSUPPORTED",
            "multipart/form-data|UNSUPPORTED",
    })
    void classifiesRequestBody(String contentType, RequestFormat expected) {
        assertEquals(expected, MediaTypes.requestFormat(contentType));
    }

    @Test
    void absentContentTypeIsJson() {
        assertEquals(RequestFormat.JSON, MediaTypes.requestFormat(null));
    }
}
