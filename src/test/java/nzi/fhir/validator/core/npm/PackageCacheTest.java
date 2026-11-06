package nzi.fhir.validator.core.npm;

import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackageCacheTest {

    @Test
    void invalidateDropsEntryAndItsWeight() throws Exception {
        PackageCache cache = new PackageCache(1);
        NpmPackage pkg = NpmPackage.empty();
        cache.put("a#1.0.0", pkg, 1000);
        cache.put("b#1.0.0", pkg, 2000);
        assertEquals(2, cache.size());
        long before = cache.weightBytes();
        cache.invalidate("a#1.0.0");
        assertNull(cache.get("a#1.0.0"));
        assertEquals(1, cache.size());
        assertEquals(before - 1000L * PackageCache.EXPANSION_FACTOR, cache.weightBytes());
        cache.invalidate("missing#1.0.0");
        assertEquals(1, cache.size());
    }
}
