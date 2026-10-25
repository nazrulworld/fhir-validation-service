package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import nzi.fhir.validator.core.service.CustomProfileValidationSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static nzi.fhir.validator.core.config.ApplicationConfig.DB_POSTGRES_SCHEMA_NAME;

/**
 * In-memory copy of the custom profiles of one FHIR version, keyed by exact canonical URL (FR-022). Fully loaded at
 * startup and refreshed incrementally, so lookups during validation never wait on the database (fixes B1).
 * A derived cache: {@code fhir_profiles} remains the source of truth.
 */
public class ProfileStore {
    private static final Logger logger = LogManager.getLogger(ProfileStore.class);

    private final SupportedFhirVersion version;
    private final FhirContext fhirContext;
    private final Map<String, IBaseResource> profiles = new ConcurrentHashMap<>();
    /** Change timestamp of each loaded profile, so re-read rows that did not change are skipped. */
    private final Map<String, OffsetDateTime> loadedAt = new ConcurrentHashMap<>();
    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z");
    /**
     * {@code modified_at} is a transaction start time, so a registration can commit after one that started later.
     * Incremental refreshes re-read this far back so such rows are not missed (FR-001, FR-003).
     */
    static final java.time.Duration COMMIT_ORDER_OVERLAP = java.time.Duration.ofSeconds(60);
    private volatile OffsetDateTime lastSeenChange = EPOCH;
    /** Extra places to look up base definitions (the active validator's IG packages), see {@link #setSnapshotSources}. */
    private volatile Supplier<List<IValidationSupport>> snapshotSources = List::of;

    public ProfileStore(SupportedFhirVersion version, FhirContext fhirContext) {
        this.version = version;
        this.fhirContext = fhirContext;
    }

    /** The {@code fhir_profiles.fhir_version} value used for this FHIR version, e.g. {@code 4.0.1}. */
    public String storageVersion() {
        return fhirContext.getVersion().getVersion().getFhirVersionString();
    }

    public SupportedFhirVersion version() {
        return version;
    }

    public IBaseResource get(String url) {
        if (url == null) {
            return null;
        }
        IBaseResource exact = profiles.get(url);
        if (exact != null) {
            return exact;
        }
        int bar = url.indexOf('|');
        return bar > 0 ? profiles.get(url.substring(0, bar)) : null;
    }

    public List<IBaseResource> all() {
        return new ArrayList<>(profiles.values());
    }

    public int size() {
        return profiles.size();
    }

    /**
     * Where base definitions of custom profiles are looked up besides the core definitions, typically the IG packages
     * of the active validator, so a profile derived from an IG profile still gets its snapshot here.
     */
    public void setSnapshotSources(Supplier<List<IValidationSupport>> sources) {
        this.snapshotSources = sources;
    }

    /**
     * Stores a profile; a missing snapshot is generated first (see {@link SnapshotPregenerator}). CPU-heavy when it
     * generates: worker threads only.
     */
    public void put(String url, IBaseResource profile) {
        profiles.put(url, withSnapshots(List.of(profile)).get(0));
    }

    /**
     * Generates snapshots for stored profiles that still have none, e.g. because their base was in an IG package that
     * was not loaded yet when they were stored. Worker threads only.
     *
     * @param sources the IG packages of the validator being built
     * @return how many profiles got a snapshot
     */
    public int ensureSnapshots(List<IValidationSupport> sources) {
        Map<String, IBaseResource> missing = new LinkedHashMap<>();
        profiles.forEach((url, profile) -> {
            if (SnapshotPregenerator.needsSnapshot(fhirContext, profile)) {
                missing.put(url, profile);
            }
        });
        if (missing.isEmpty()) {
            return 0;
        }
        List<IBaseResource> before = new ArrayList<>(missing.values());
        List<IBaseResource> after = SnapshotPregenerator.withSnapshots(fhirContext, before, withStored(sources));
        int completed = 0;
        int i = 0;
        for (String url : missing.keySet()) {
            IBaseResource result = after.get(i);
            // Only replace the exact object we completed: a concurrent refresh may have stored a newer one.
            if (result != before.get(i) && profiles.replace(url, before.get(i), result)) {
                completed++;
            }
            i++;
        }
        return completed;
    }

    private List<IBaseResource> withSnapshots(List<IBaseResource> batch) {
        return SnapshotPregenerator.withSnapshots(fhirContext, batch, withStored(snapshotSources.get()));
    }

    /** Lookup sources plus the profiles already stored (bases can be other custom profiles). */
    private List<IValidationSupport> withStored(List<IValidationSupport> sources) {
        List<IValidationSupport> all = new ArrayList<>(sources);
        all.add(new CustomProfileValidationSupport(fhirContext, this));
        return all;
    }

    /**
     * Loads every profile of this version (replacing nothing that is newer).
     */
    public Future<Integer> loadAll(Pool pool, Executors executors) {
        return load(pool, executors, EPOCH);
    }

    /**
     * Loads profiles changed since the last seen change timestamp, minus {@link #COMMIT_ORDER_OVERLAP} (poll fallback
     * and change notifications).
     *
     * @return the number of profiles that are new or changed (re-read unchanged rows are not counted)
     */
    public Future<Integer> refreshChanged(Pool pool, Executors executors) {
        OffsetDateTime seen = lastSeenChange;
        return load(pool, executors, seen.equals(EPOCH) ? EPOCH : seen.minus(COMMIT_ORDER_OVERLAP));
    }

    private Future<Integer> load(Pool pool, Executors executors, OffsetDateTime since) {
        String sql = ("SELECT url, profile_json::text, modified_at FROM %s.fhir_profiles "
                + "WHERE fhir_version = $1 AND modified_at >= $2 ORDER BY modified_at").formatted(DB_POSTGRES_SCHEMA_NAME);
        return pool.preparedQuery(sql)
                .execute(Tuple.of(storageVersion(), since))
                .compose(rows -> {
                    List<Row> list = new ArrayList<>();
                    rows.forEach(list::add);
                    if (list.isEmpty()) {
                        return Future.succeededFuture(0);
                    }
                    // Parsing StructureDefinitions is CPU work: keep it off the event loop.
                    return executors.packages().executeBlocking(() -> {
                        List<String> urls = new ArrayList<>();
                        List<IBaseResource> parsed = new ArrayList<>();
                        for (Row row : list) {
                            String url = row.getString(0);
                            OffsetDateTime changed = row.getOffsetDateTime(2);
                            if (changed != null && changed.equals(loadedAt.get(url))) {
                                continue; // re-read through the overlap window, unchanged
                            }
                            try {
                                parsed.add(fhirContext.newJsonParser().parseResource(row.getString(1)));
                                urls.add(url);
                            } catch (Exception e) {
                                logger.error("Stored profile {} ({}) cannot be parsed and is ignored", url, version);
                            }
                            if (changed != null) {
                                loadedAt.put(url, changed);
                            }
                            if (changed != null && changed.isAfter(lastSeenChange)) {
                                lastSeenChange = changed;
                            }
                        }
                        // Snapshots first, so validation never sees a profile HAPI would have to complete lazily.
                        List<IBaseResource> complete = withSnapshots(parsed);
                        for (int i = 0; i < urls.size(); i++) {
                            profiles.put(urls.get(i), complete.get(i));
                        }
                        int loaded = urls.size();
                        if (loaded > 0) {
                            logger.info("Loaded {} profile(s) for {} changed since {}", loaded, version, since);
                        }
                        return loaded;
                    }, false);
                });
    }
}
