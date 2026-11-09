package nzi.fhir.validator.core.terminology;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One server the official HL7 terminology registry lists, as kept by the allowlist (spec 007 FR-008, data-model
 * §2.1, research R8).
 *
 * @param url           normalised base URL ({@link TerminologySettings#normaliseUrl})
 * @param fhirVersion   the release the entry's {@code fhirVersion} (for example {@code 4.0.1}) belongs to
 * @param serverCode    the registry's {@code server-code}, for logs and metrics
 * @param authoritative the registry's {@code authoritative[]} patterns (diagnostics only; routing uses {@code /resolve})
 */
public record TxServerEntry(String url, SupportedFhirVersion fhirVersion, String serverCode, List<String> authoritative) {

    public TxServerEntry {
        authoritative = authoritative == null ? List.of() : List.copyOf(authoritative);
    }

    /**
     * A registry discovery entry, kept only when its last check succeeded ({@code error} is null), it needs no
     * credentials ({@code security} is {@code open}), its FHIR version maps to an enabled release by major.minor, and
     * its URL is a plain http(s) base URL. Anything else, including a missing or malformed field, is dropped.
     */
    public static Optional<TxServerEntry> fromRegistry(JsonObject entry) {
        if (entry == null || entry.getValue("error") != null || !"open".equals(entry.getValue("security"))) {
            return Optional.empty();
        }
        if (!(entry.getValue("url") instanceof String url) || !(entry.getValue("fhirVersion") instanceof String fhir)) {
            return Optional.empty();
        }
        Optional<SupportedFhirVersion> version = SupportedFhirVersion.fromDeclaredVersion(fhir).filter(SupportedFhirVersion::isEnabled);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        String normalised;
        try {
            normalised = TerminologySettings.normaliseUrl(url);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        List<String> patterns = new ArrayList<>();
        if (entry.getValue("authoritative") instanceof JsonArray a) {
            a.forEach(p -> {
                if (p instanceof String s) {
                    patterns.add(s);
                }
            });
        }
        Object code = entry.getValue("server-code");
        return Optional.of(new TxServerEntry(normalised, version.get(), code instanceof String c ? c : normalised, patterns));
    }

    /** The stored form ({@code tx_registry_snapshot.body} items). */
    public JsonObject toJson() {
        return new JsonObject().put("url", url).put("fhirVersion", fhirVersion.name()).put("serverCode", serverCode)
                .put("authoritative", new JsonArray(authoritative));
    }

    /** Reads {@link #toJson()} back; empty for anything malformed. */
    public static Optional<TxServerEntry> fromStored(JsonObject json) {
        try {
            String url = TerminologySettings.normaliseUrl(json.getString("url"));
            SupportedFhirVersion version = SupportedFhirVersion.valueOf(json.getString("fhirVersion"));
            List<String> patterns = new ArrayList<>();
            JsonArray a = json.getJsonArray("authoritative", new JsonArray());
            a.forEach(p -> patterns.add(String.valueOf(p)));
            return Optional.of(new TxServerEntry(url, version, json.getString("serverCode", url), patterns));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
