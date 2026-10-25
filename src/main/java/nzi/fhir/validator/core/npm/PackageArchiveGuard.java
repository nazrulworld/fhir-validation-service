package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.web.ProblemException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Checks a package archive before it is parsed (FR-014): compressed size, expanded size, entry count, expansion
 * ratio and entry names (no absolute paths, no {@code ..}). Streams the archive and stops at the first violation, so
 * a decompression bomb never expands in memory. Run it on a worker thread.
 */
public class PackageArchiveGuard {
    private static final long MB = 1024L * 1024L;

    private final long maxCompressed;
    private final long maxExpanded;
    private final int maxEntries;
    private final int maxRatio;

    public PackageArchiveGuard(long maxCompressedMb, long maxExpandedMb, int maxEntries, int maxRatio) {
        this.maxCompressed = maxCompressedMb * MB;
        this.maxExpanded = maxExpandedMb * MB;
        this.maxEntries = maxEntries;
        this.maxRatio = maxRatio;
    }

    public static PackageArchiveGuard from(ApplicationConfig config) {
        return new PackageArchiveGuard(config.packageMaxCompressedMb(), config.packageMaxExpandedMb(),
                config.packageMaxEntries(), config.packageMaxRatio());
    }

    /**
     * Default limits (contracts/configuration.md).
     */
    public static PackageArchiveGuard defaults() {
        return new PackageArchiveGuard(50, 500, 20_000, 100);
    }

    public long maxCompressedBytes() {
        return maxCompressed;
    }

    /**
     * @throws ProblemException 413 {@code package-too-large} or 422 {@code package-unsafe}
     */
    public void check(byte[] archive) {
        check(new ByteArrayInputStream(archive), archive.length);
    }

    /**
     * Checks an archive read from a stream (e.g. an uploaded file), without holding it in memory.
     *
     * @param compressedSize size of the archive in bytes
     */
    public void check(InputStream archive, long compressedSize) {
        if (compressedSize > maxCompressed) {
            throw tooLarge("The archive exceeds " + maxCompressed / MB + " MB");
        }
        long expanded = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream gzip = new GzipCompressorInputStream(archive);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw unsafe("The archive has more than " + maxEntries + " entries");
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*")
                        || ("/" + name.replace('\\', '/') + "/").contains("/../")) {
                    throw unsafe("The archive contains an unsafe entry path");
                }
                int read;
                while ((read = tar.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > maxExpanded) {
                        throw tooLarge("The archive expands beyond " + maxExpanded / MB + " MB");
                    }
                    if (expanded > (long) maxRatio * Math.max(compressedSize, 1)) {
                        throw unsafe("The archive expands more than " + maxRatio + " times its size");
                    }
                }
            }
        } catch (IOException e) {
            throw new ProblemException(400, "invalid-request", "The content is not a valid FHIR NPM package (.tgz)");
        }
    }

    private static ProblemException tooLarge(String detail) {
        return new ProblemException(413, "package-too-large", detail);
    }

    private static ProblemException unsafe(String detail) {
        return new ProblemException(422, "package-unsafe", detail);
    }
}
