package nzi.fhir.validator.core.npm;

import org.hl7.fhir.utilities.npm.NpmPackage;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed IG packages kept in memory so validator rebuilds do not re-read and re-decompress unchanged packages
 * (P3). Bounded by an estimated byte weight ({@code FV_PACKAGE_CACHE_MAX_MB}); least recently used entries are
 * evicted first. Packages are immutable per {@code name#version}, so entries never go stale.
 */
public class PackageCache {
    /** Expanded packages are roughly this many times larger than their archive. */
    static final int EXPANSION_FACTOR = 4;

    private record Entry(NpmPackage pkg, long weight) {
    }

    private final long maxWeight;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;
    private long hits;
    private long misses;

    public PackageCache(long maxMegabytes) {
        this.maxWeight = maxMegabytes * 1024L * 1024L;
    }

    public synchronized NpmPackage get(String idAndVersion) {
        Entry e = entries.get(idAndVersion);
        if (e == null) {
            misses++;
            return null;
        }
        hits++;
        return e.pkg();
    }

    /**
     * @param archiveBytes size of the compressed archive, used to estimate memory use
     */
    public synchronized void put(String idAndVersion, NpmPackage pkg, long archiveBytes) {
        long w = archiveBytes * EXPANSION_FACTOR;
        if (w > maxWeight) {
            return;
        }
        Entry previous = entries.put(idAndVersion, new Entry(pkg, w));
        if (previous != null) {
            weight -= previous.weight();
        }
        weight += w;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (weight > maxWeight && it.hasNext()) {
            Map.Entry<String, Entry> eldest = it.next();
            weight -= eldest.getValue().weight();
            it.remove();
        }
    }

    public synchronized long weightBytes() {
        return weight;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }
}
