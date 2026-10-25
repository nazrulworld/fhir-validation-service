package nzi.fhir.validator.core.validator;

import ca.uhn.fhir.context.FhirContext;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import nzi.fhir.validator.core.concurrency.Executors;
import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z");
    private volatile OffsetDateTime lastSeenChange = EPOCH;

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

    public void put(String url, IBaseResource profile) {
        profiles.put(url, profile);
    }

    /**
     * Loads every profile of this version (replacing nothing that is newer).
     */
    public Future<Integer> loadAll(Pool pool, Executors executors) {
        return load(pool, executors, EPOCH);
    }

    /**
     * Loads profiles changed since the last seen change timestamp (poll fallback and change notifications).
     */
    public Future<Integer> refreshChanged(Pool pool, Executors executors) {
        return load(pool, executors, lastSeenChange);
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
                        int loaded = 0;
                        for (Row row : list) {
                            String url = row.getString(0);
                            try {
                                profiles.put(url, fhirContext.newJsonParser().parseResource(row.getString(1)));
                                loaded++;
                            } catch (Exception e) {
                                logger.error("Stored profile {} ({}) cannot be parsed and is ignored", url, version);
                            }
                            OffsetDateTime changed = row.getOffsetDateTime(2);
                            if (changed != null && changed.isAfter(lastSeenChange)) {
                                lastSeenChange = changed;
                            }
                        }
                        logger.info("Loaded {} profile(s) for {} changed since {}", loaded, version, since);
                        return loaded;
                    }, false);
                });
    }
}
