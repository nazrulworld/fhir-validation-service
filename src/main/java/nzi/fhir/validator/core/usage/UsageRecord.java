package nzi.fhir.validator.core.usage;

import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One validation cycle attributed to an API key and its organisation (spec 008 FR-012, data-model §3). Never contains
 * resource content. Timings are whole milliseconds; a phase that didn't run is -1, and so are CPU time and allocated
 * bytes when the JVM can't measure them.
 *
 * @param totalMs   from handler entry until both the response and the validation work were done, so it is never less
 *                  than {@code validateMs}
 * @param queueMs   waiting for admission plus waiting for a validation worker
 * @param contextMs obtaining the validator context
 * @param validateMs on the validation worker (validation and rendering)
 */
public record UsageRecord(String requestId, Instant startedAt, UUID apiKeyId, String apiKeyName, UUID orgId,
                          String fhirVersion, String ig, int profiles, String contentType, int requestBytes,
                          String outcome, int httpStatus, int issuesFatal, int issuesError, int issuesWarning,
                          int issuesInformation, String clientIp, int totalMs, int queueMs, int contextMs, int validateMs,
                          long cpuTimeNs, long allocatedBytes) {

    /** The usage log line (contracts/operations.md §4), keys in that order. */
    public JsonObject toJson() {
        return new JsonObject()
                .put("requestId", requestId)
                .put("startedAt", startedAt == null ? null : startedAt.toString())
                .put("apiKeyId", apiKeyId == null ? null : apiKeyId.toString())
                .put("apiKeyName", apiKeyName)
                .put("orgId", orgId == null ? null : orgId.toString())
                .put("fhirVersion", fhirVersion)
                .put("ig", ig)
                .put("profiles", profiles)
                .put("contentType", contentType)
                .put("requestBytes", requestBytes)
                .put("outcome", outcome)
                .put("httpStatus", httpStatus)
                .put("issues", new JsonObject().put("fatal", issuesFatal).put("error", issuesError)
                        .put("warning", issuesWarning).put("information", issuesInformation))
                .put("clientIp", clientIp)
                .put("totalMs", totalMs)
                .put("queueMs", queueMs)
                .put("contextMs", contextMs)
                .put("validateMs", validateMs)
                .put("cpuTimeNs", cpuTimeNs)
                .put("allocatedBytes", allocatedBytes);
    }

    /** The parameters of {@link DbUsageWriter#INSERT}, in its column order. */
    Tuple toTuple() {
        return Tuple.tuple()
                .addValue(OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
                .addValue(requestId)
                .addValue(apiKeyId)
                .addValue(apiKeyName)
                .addValue(orgId)
                .addValue(fhirVersion)
                .addValue(ig)
                .addValue((short) Math.min(profiles, Short.MAX_VALUE))
                .addValue(contentType)
                .addValue(requestBytes)
                .addValue(outcome)
                .addValue((short) httpStatus)
                .addValue(issuesFatal)
                .addValue(issuesError)
                .addValue(issuesWarning)
                .addValue(issuesInformation)
                .addValue(clientIp)
                .addValue(totalMs)
                .addValue(queueMs)
                .addValue(contextMs)
                .addValue(validateMs)
                .addValue(cpuTimeNs)
                .addValue(allocatedBytes);
    }
}
