package nzi.fhir.validator.core.terminology;

import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.web.ProblemException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The terminology setting of one included validator context (spec 007 FR-001, FR-002, data-model §1). It is part of
 * what the context is built from: a different setting is a different context build (research R12).
 *
 * @param mode   never {@code null}
 * @param server a normalised base URL ({@link #normaliseUrl}); required for {@code SERVER}, optional for
 *               {@code ECOSYSTEM} (it then replaces the registry's default server), forbidden for {@code NONE}
 */
public record TerminologySettings(TerminologyMode mode, String server) {
    /** No terminology server: fully offline, as every context was before 4.0.0. */
    public static final TerminologySettings NONE = new TerminologySettings(TerminologyMode.NONE, null);

    /** At most 512 characters (data-model §1, the {@code tx_server} column). */
    public static final int MAX_SERVER_LENGTH = 512;

    private static final Set<String> FIELDS = Set.of("mode", "server");

    public TerminologySettings {
        Objects.requireNonNull(mode, "mode");
        if (mode == TerminologyMode.NONE && server != null) {
            throw new IllegalArgumentException("mode none takes no server");
        }
        if (mode == TerminologyMode.SERVER && server == null) {
            throw new IllegalArgumentException("mode server needs a server");
        }
    }

    /**
     * Parses the wire form {@code {"mode": "none"|"server"|"ecosystem", "server"?: "<base url>"}}.
     *
     * @throws ProblemException 400 {@code invalid-request} with a curated text for any malformed setting
     */
    public static TerminologySettings parse(JsonObject json) {
        if (json == null) {
            throw invalid("terminology must be an object with a mode");
        }
        for (String field : json.fieldNames()) {
            if (!FIELDS.contains(field)) {
                throw invalid("Unknown terminology field '" + field + "'");
            }
        }
        if (!(json.getValue("mode") instanceof String wire)) {
            throw invalid("terminology.mode is required: none, server or ecosystem");
        }
        TerminologyMode mode;
        try {
            mode = TerminologyMode.fromWire(wire);
        } catch (IllegalArgumentException e) {
            throw invalid("terminology.mode must be none, server or ecosystem");
        }
        Object rawServer = json.getValue("server");
        if (rawServer != null && !(rawServer instanceof String)) {
            throw invalid("terminology.server must be a URL");
        }
        String server = (String) rawServer;
        if (mode == TerminologyMode.NONE && server != null) {
            throw invalid("terminology.server is not allowed with mode none");
        }
        if (mode == TerminologyMode.SERVER && server == null) {
            throw invalid("terminology.server is required with mode server");
        }
        if (server != null) {
            if (server.length() > MAX_SERVER_LENGTH) {
                throw invalid("terminology.server must be at most " + MAX_SERVER_LENGTH + " characters");
            }
            try {
                server = normaliseUrl(server);
            } catch (IllegalArgumentException e) {
                throw invalid("terminology.server must be an http or https base URL without query, fragment or user info");
            }
        }
        return new TerminologySettings(mode, server);
    }

    /** The wire form; {@code server} is omitted when there is none. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject().put("mode", mode.wire());
        if (server != null) {
            json.put("server", server);
        }
        return json;
    }

    /**
     * The form two server addresses are compared in (FR-009): scheme and host in lower case, the default port
     * ({@code :80} for http, {@code :443} for https) dropped, no trailing {@code /}. The path keeps its case.
     *
     * @throws IllegalArgumentException for anything but an absolute http(s) URL with a host and without query, fragment
     *                                  or user info
     */
    public static String normaliseUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("no url");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a url");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("not http(s)");
        }
        if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("not a plain base url");
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port) + path;
    }

    private static ProblemException invalid(String detail) {
        return new ProblemException(400, "invalid-request", detail);
    }
}
