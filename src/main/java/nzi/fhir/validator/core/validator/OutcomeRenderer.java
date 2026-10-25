package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.parser.IParser;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.model.MediaTypes.ResponseFormat;
import nzi.fhir.validator.core.model.ValidationResponse;

/**
 * Renders validation outcomes in the negotiated format (research R11): the service's JSON shape for
 * {@code application/json}, a FHIR {@code OperationOutcome} for the FHIR media types.
 */
public final class OutcomeRenderer {

    private OutcomeRenderer() {
    }

    public static String render(ValidatorSnapshot.Outcome outcome, ResponseFormat format) {
        return switch (format) {
            case FHIR_JSON -> operationOutcome(outcome, false);
            case FHIR_XML -> operationOutcome(outcome, true);
            default -> legacy(outcome).encode();
        };
    }

    public static JsonObject legacy(ValidatorSnapshot.Outcome outcome) {
        JsonArray messages = new JsonArray();
        outcome.result().getMessages().forEach(msg -> messages.add(new JsonObject()
                .put("severity", msg.getSeverity().name())
                .put("location", msg.getLocationString())
                .put("message", msg.getMessage())));
        return new ValidationResponse(outcome.result().isSuccessful(), messages, outcome.version().name(),
                outcome.appliedProfiles()).toJson();
    }

    private static String operationOutcome(ValidatorSnapshot.Outcome outcome, boolean xml) {
        IParser parser = xml ? outcome.fhirContext().newXmlParser() : outcome.fhirContext().newJsonParser();
        return parser.encodeResourceToString(outcome.result().toOperationOutcome());
    }

    /**
     * The {@code issues} member of an {@code invalid-resource} problem: one fatal issue describing the parse failure.
     */
    public static JsonArray parseFailure(String message) {
        return new JsonArray().add(new JsonObject()
                .put("severity", "FATAL")
                .put("location", "")
                .put("message", message));
    }
}
