package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.testsupport.Fixtures;
import nzi.fhir.validator.web.ProblemException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F3 regression: unsafe or oversized archives are rejected before parsing, while streaming.
 */
class PackageArchiveGuardTest {

    /** Small limits so the test archives stay small: 1 MB compressed, 4 MB expanded, 50 entries, ratio 100. */
    private final PackageArchiveGuard guard = new PackageArchiveGuard(1, 4, 50, 100);

    private interface Entries {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    private static byte[] tgz(Entries entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            entries.write(tar);
        }
        return out.toByteArray();
    }

    private static void entry(TarArchiveOutputStream tar, String name, byte[] data) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, true);
        e.setSize(data.length);
        tar.putArchiveEntry(e);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private static void zeros(TarArchiveOutputStream tar, String name, long size) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, true);
        e.setSize(size);
        tar.putArchiveEntry(e);
        byte[] chunk = new byte[64 * 1024];
        for (long written = 0; written < size; written += chunk.length) {
            tar.write(chunk, 0, (int) Math.min(chunk.length, size - written));
        }
        tar.closeArchiveEntry();
    }

    private void assertRejected(byte[] archive, int status, String type) {
        ProblemException e = assertThrows(ProblemException.class, () -> guard.check(archive));
        assertEquals(status, e.status(), e.detail());
        assertEquals(type, e.type());
    }

    @Test
    void decompressionBombIsRejectedWhileStreaming() throws IOException {
        // 10 MB of zeros compresses to a few KB: far beyond the 100x ratio and the 4 MB expanded limit.
        byte[] bomb = tgz(tar -> zeros(tar, "package/bomb.json", 10L * 1024 * 1024));
        assertTrue(bomb.length < 100_000);
        Runtime rt = Runtime.getRuntime();
        long before = rt.totalMemory() - rt.freeMemory();
        ProblemException e = assertThrows(ProblemException.class, () -> guard.check(bomb));
        assertTrue(e.status() == 413 || e.status() == 422, e.detail());
        long after = rt.totalMemory() - rt.freeMemory();
        assertTrue(after - before < 8L * 1024 * 1024, "the bomb must not be expanded in memory");
    }

    @Test
    void highRatioWithinSizeLimitsIsUnsafe() throws IOException {
        // 2 MB of zeros: under the 4 MB expanded limit, but a ratio far above 100.
        assertRejected(tgz(tar -> zeros(tar, "package/zeros.json", 2L * 1024 * 1024)), 422, "package-unsafe");
    }

    @Test
    void parentTraversalIsUnsafe() throws IOException {
        assertRejected(tgz(tar -> entry(tar, "package/../../etc/passwd", "x".getBytes())), 422, "package-unsafe");
    }

    @Test
    void absolutePathIsUnsafe() throws IOException {
        assertRejected(tgz(tar -> entry(tar, "/etc/x", "x".getBytes())), 422, "package-unsafe");
    }

    @Test
    void tooManyEntriesIsUnsafe() throws IOException {
        assertRejected(tgz(tar -> {
            for (int i = 0; i < 51; i++) {
                entry(tar, "package/f" + i + ".json", ("{\"i\":" + i + "}").getBytes());
            }
        }), 422, "package-unsafe");
    }

    @Test
    void oversizedArchiveIsTooLarge() {
        assertRejected(new byte[2 * 1024 * 1024], 413, "package-too-large");
    }

    @Test
    void notAnArchiveIsInvalidRequest() {
        assertRejected("not a tgz".getBytes(), 400, "invalid-request");
    }

    @Test
    void realPackagePasses() {
        PackageArchiveGuard defaults = PackageArchiveGuard.defaults();
        assertDoesNotThrow(() -> defaults.check(Fixtures.bytes(Fixtures.DK_CORE_IG)));
        assertDoesNotThrow(() -> defaults.check(Fixtures.bytes(Fixtures.US_CORE_IG)));
    }
}
