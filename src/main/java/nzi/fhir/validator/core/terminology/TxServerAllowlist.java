package nzi.fhir.validator.core.terminology;

import io.vertx.core.json.JsonObject;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.web.ProblemException;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The servers this service may contact for terminology, per FHIR version (spec 007 FR-006 to FR-012, data-model §2.2).
 * Immutable: {@code TxRegistry} swaps the whole object on every refresh, so a check never sees half an update.
 * <ul>
 *     <li>A version with a {@link Source#REGISTRY} or {@link Source#STORED_REGISTRY} list allows exactly the open,
 *     healthy servers the official registry lists for it (FR-008), matched on the normalised URL (FR-009).</li>
 *     <li>A version for which no registry list was ever obtained uses the {@link Source#BUILT_IN} fallback list
 *     (FR-012).</li>
 * </ul>
 */
public final class TxServerAllowlist {
    public enum Source {
        /** Fetched from the registry by this instance. */
        REGISTRY,
        /** The last good registry list, read from shared storage at start (the registry was not reached yet). */
        STORED_REGISTRY,
        /** No registry list was ever obtained: the built-in fallback list. */
        BUILT_IN;

        public String wire() {
            return switch (this) {
                case REGISTRY -> "registry";
                case STORED_REGISTRY -> "stored-registry";
                case BUILT_IN -> "built-in";
            };
        }
    }

    /**
     * @param entries            normalised URL → entry
     * @param fetchedAt          when the list was obtained from the registry; {@code null} for the built-in list
     * @param registryLastUpdate the registry's {@code last-update}, when known
     */
    public record VersionList(Map<String, TxServerEntry> entries, Source source, Instant fetchedAt, Instant registryLastUpdate) {
        public VersionList {
            entries = Map.copyOf(entries);
        }

        public static VersionList of(Collection<TxServerEntry> entries, Source source, Instant fetchedAt,
                                     Instant registryLastUpdate) {
            Map<String, TxServerEntry> byUrl = new LinkedHashMap<>();
            entries.forEach(e -> byUrl.putIfAbsent(e.url(), e));
            return new VersionList(byUrl, source, fetchedAt, registryLastUpdate);
        }
    }

    /** Allows nothing: no list and no built-in fallback. */
    public static final TxServerAllowlist EMPTY = new TxServerAllowlist(Map.of(), Map.of());

    private final Map<SupportedFhirVersion, VersionList> perVersion;
    /** The registry's default server per version (tx.fhir.org); replaced only in tests. */
    private final Map<SupportedFhirVersion, String> defaults;

    TxServerAllowlist(Map<SupportedFhirVersion, VersionList> perVersion, Map<SupportedFhirVersion, String> defaults) {
        this.perVersion = perVersion.isEmpty() ? Map.of() : new EnumMap<>(perVersion);
        this.defaults = defaults.isEmpty() ? Map.of() : new EnumMap<>(defaults);
    }

    /**
     * The production starting point: every enabled version on its built-in fallback list, with tx.fhir.org as the
     * default server.
     */
    public static TxServerAllowlist builtIn() {
        Map<SupportedFhirVersion, VersionList> lists = new EnumMap<>(SupportedFhirVersion.class);
        Map<SupportedFhirVersion, String> defaults = new EnumMap<>(SupportedFhirVersion.class);
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            List<TxServerEntry> entries = BuiltInTxServer.forVersion(v).stream()
                    .map(s -> new TxServerEntry(s.url(), v, "built-in", List.of())).toList();
            lists.put(v, VersionList.of(entries, Source.BUILT_IN, null, null));
            BuiltInTxServer.forVersion(v).stream().findFirst().ifPresent(s -> defaults.put(v, s.url()));
        }
        return new TxServerAllowlist(lists, defaults);
    }

    /** A copy with {@code version}'s list replaced. */
    public TxServerAllowlist withVersion(SupportedFhirVersion version, VersionList list) {
        Map<SupportedFhirVersion, VersionList> copy = new EnumMap<>(SupportedFhirVersion.class);
        copy.putAll(perVersion);
        copy.put(version, list);
        return new TxServerAllowlist(copy, defaults);
    }

    /** A copy with the default server of {@code version} replaced (tests: point "tx.fhir.org" at a stub). */
    TxServerAllowlist withDefault(SupportedFhirVersion version, String url) {
        Map<SupportedFhirVersion, String> copy = new EnumMap<>(SupportedFhirVersion.class);
        copy.putAll(defaults);
        copy.put(version, TerminologySettings.normaliseUrl(url));
        return new TxServerAllowlist(perVersion, copy);
    }

    public Optional<VersionList> list(SupportedFhirVersion version) {
        return Optional.ofNullable(perVersion.get(version));
    }

    /** Whether a registry list (fetched or stored) exists for {@code version}. */
    public boolean hasRegistryList(SupportedFhirVersion version) {
        VersionList list = perVersion.get(version);
        return list != null && list.source() != Source.BUILT_IN;
    }

    /** Exact match on the normalised URL, for {@code version} only (FR-008, FR-009). */
    public boolean isAllowed(String url, SupportedFhirVersion version) {
        VersionList list = perVersion.get(version);
        if (list == null || url == null) {
            return false;
        }
        try {
            return list.entries().containsKey(TerminologySettings.normaliseUrl(url));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The allowlisted entry for {@code url}, if any. */
    public Optional<TxServerEntry> entry(String url, SupportedFhirVersion version) {
        VersionList list = perVersion.get(version);
        if (list == null || url == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(list.entries().get(TerminologySettings.normaliseUrl(url)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Checks a server for a setting (FR-010 a and b, FR-012a).
     *
     * @throws ProblemException 400 {@code tx-server-not-registered} when a registry list exists and doesn't allow it;
     *                          503 {@code tx-registry-unavailable} when no registry list was ever obtained and it is
     *                          not on the built-in list
     */
    public void require(String url, SupportedFhirVersion version) {
        if (isAllowed(url, version)) {
            return;
        }
        if (hasRegistryList(version)) {
            throw ProblemException.txServerNotRegistered(url, version);
        }
        throw ProblemException.txRegistryUnavailable(version);
    }

    /**
     * Checks a whole setting at save time (FR-010 a, FR-012a). {@code none} always passes. A server, when given, must
     * be allowed ({@link #require}). {@code ecosystem} without a server needs at least one allowed server for the
     * version: with a registry list that lists none (STU3, R4B) it is 400 {@code tx-server-not-registered}; with no list
     * and no built-in server it is 503 {@code tx-registry-unavailable}.
     */
    public void require(TerminologySettings settings, SupportedFhirVersion version) {
        if (settings.mode() == TerminologyMode.NONE) {
            return;
        }
        if (settings.server() != null) {
            require(settings.server(), version);
            return;
        }
        VersionList list = perVersion.get(version);
        if (list != null && !list.entries().isEmpty()) {
            return;
        }
        if (hasRegistryList(version)) {
            throw ProblemException.txServerNotRegistered("(any server)", version);
        }
        throw ProblemException.txRegistryUnavailable(version);
    }

    /**
     * The default server for {@code version} (tx.fhir.org), only when it is allowlisted. The built-in address is
     * {@code https}, while the registry may list the same server as {@code http} (it lists {@code http://tx.fhir.org/r4});
     * so when the built-in address itself isn't allowed, an allowlisted entry with the same host and path under the other
     * scheme is the default server, exactly as the registry lists it. Settings saved by users still need an exact match.
     */
    public Optional<String> defaultServer(SupportedFhirVersion version) {
        String url = defaults.get(version);
        if (url == null) {
            return Optional.empty();
        }
        if (isAllowed(url, version)) {
            return Optional.of(url);
        }
        String other = url.startsWith("https://") ? "http://" + url.substring("https://".length())
                : url.startsWith("http://") ? "https://" + url.substring("http://".length()) : null;
        return other != null && isAllowed(other, version) ? Optional.of(TerminologySettings.normaliseUrl(other)) : Optional.empty();
    }

    /** The {@code allowlist} object of the {@code /health} report (contracts §5). */
    public JsonObject status() {
        JsonObject out = new JsonObject();
        for (SupportedFhirVersion v : SupportedFhirVersion.enabled()) {
            VersionList list = perVersion.get(v);
            JsonObject s = new JsonObject();
            if (list == null) {
                s.put("source", Source.BUILT_IN.wire()).putNull("fetchedAt").putNull("registryLastUpdate").put("servers", 0)
                        .put("reason", "no server list; terminology servers can't be used for " + v);
            } else {
                s.put("source", list.source().wire())
                        .put("fetchedAt", list.fetchedAt() == null ? null : list.fetchedAt().toString())
                        .put("registryLastUpdate", list.registryLastUpdate() == null ? null : list.registryLastUpdate().toString())
                        .put("servers", list.entries().size());
                if (list.source() == Source.BUILT_IN) {
                    s.put("reason", list.entries().isEmpty()
                            ? "registry list not obtained yet and no built-in server for " + v
                            : "registry list not obtained yet; built-in fallback list in use");
                }
            }
            out.put(v.name(), s);
        }
        return out;
    }
}
