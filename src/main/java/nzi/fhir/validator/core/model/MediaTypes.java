package nzi.fhir.validator.core.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * HTTP content negotiation for validation (research R11): {@code Accept} with q-values, wildcards and parameters,
 * and {@code Content-Type} with parameters and structured-syntax suffixes ({@code +json}, {@code +xml}).
 */
public final class MediaTypes {

    public enum RequestFormat { JSON, XML, UNSUPPORTED }

    /**
     * {@code JSON_LEGACY}: the {@code {valid, messages}} shape (default); {@code FHIR_JSON}/{@code FHIR_XML}: a FHIR
     * OperationOutcome.
     */
    public enum ResponseFormat {
        JSON_LEGACY("application/json"),
        FHIR_JSON("application/fhir+json"),
        FHIR_XML("application/fhir+xml"),
        NONE(null);

        private final String mimeType;

        ResponseFormat(String mimeType) {
            this.mimeType = mimeType;
        }

        public String mimeType() {
            return mimeType;
        }
    }

    private record Range(String type, String subtype, double q, int order) {
    }

    private MediaTypes() {
    }

    public static RequestFormat requestFormat(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return RequestFormat.JSON;
        }
        String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mime.endsWith("/json") || mime.endsWith("+json")) {
            return RequestFormat.JSON;
        }
        if (mime.endsWith("/xml") || mime.endsWith("+xml")) {
            return RequestFormat.XML;
        }
        return RequestFormat.UNSUPPORTED;
    }

    public static ResponseFormat responseFormat(String accept) {
        if (accept == null || accept.isBlank()) {
            return ResponseFormat.JSON_LEGACY;
        }
        List<Range> ranges = parse(accept);
        ranges.sort(Comparator.comparingDouble(Range::q).reversed()
                .thenComparing(r -> specificity(r), Comparator.reverseOrder())
                .thenComparingInt(Range::order));
        for (Range r : ranges) {
            if (r.q() <= 0) {
                continue;
            }
            ResponseFormat f = match(r);
            if (f != ResponseFormat.NONE) {
                return f;
            }
        }
        return ResponseFormat.NONE;
    }

    private static int specificity(Range r) {
        return (r.type().equals("*") ? 0 : 1) + (r.subtype().equals("*") ? 0 : 1);
    }

    private static ResponseFormat match(Range r) {
        String type = r.type();
        String subtype = r.subtype();
        if (type.equals("*") || (type.equals("application") && subtype.equals("*"))) {
            return ResponseFormat.JSON_LEGACY;
        }
        if (!type.equals("application")) {
            return ResponseFormat.NONE;
        }
        return switch (subtype) {
            case "json" -> ResponseFormat.JSON_LEGACY;
            case "fhir+json" -> ResponseFormat.FHIR_JSON;
            case "fhir+xml", "xml" -> ResponseFormat.FHIR_XML;
            default -> ResponseFormat.NONE;
        };
    }

    private static List<Range> parse(String header) {
        List<Range> ranges = new ArrayList<>();
        String[] parts = header.split(",");
        for (int i = 0; i < parts.length; i++) {
            String[] params = parts[i].split(";");
            String mime = params[0].trim().toLowerCase(Locale.ROOT);
            int slash = mime.indexOf('/');
            if (slash <= 0 || slash == mime.length() - 1) {
                continue;
            }
            double q = 1.0;
            for (int p = 1; p < params.length; p++) {
                String[] kv = params[p].trim().split("=", 2);
                if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("q")) {
                    try {
                        q = Double.parseDouble(kv[1].trim());
                    } catch (NumberFormatException e) {
                        q = 0;
                    }
                }
            }
            ranges.add(new Range(mime.substring(0, slash), mime.substring(slash + 1), q, i));
        }
        return ranges;
    }
}
