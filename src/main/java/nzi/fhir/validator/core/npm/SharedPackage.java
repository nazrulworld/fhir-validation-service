package nzi.fhir.validator.core.npm;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;
import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The parsed definitions of one package content for one FHIR version (spec 010 FR-017, data-model §8), shared by
 * every validator context that contains the same content. Immutable once published: contexts only read it, and any
 * definition a context completes or keeps incomplete is a private copy (FR-020).
 *
 * @param resources     the package's conformance resources, in package file order (unmodifiable)
 * @param expandedBytes the unpacked size of the package, for metrics and heap accounting
 */
public record SharedPackage(Key key, List<IBaseResource> resources, long expandedBytes) {
    public SharedPackage {
        Objects.requireNonNull(key, "key");
        resources = List.copyOf(resources);
    }

    /** One package content: {@code name#version} and its content hash, per FHIR version. */
    public record Key(SupportedFhirVersion fhirVersion, String nameVersion, byte[] sha256) {
        public Key {
            Objects.requireNonNull(fhirVersion, "fhirVersion");
            Objects.requireNonNull(nameVersion, "nameVersion");
            Objects.requireNonNull(sha256, "sha256");
            sha256 = sha256.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && fhirVersion == k.fhirVersion && nameVersion.equals(k.nameVersion)
                    && Arrays.equals(sha256, k.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fhirVersion, nameVersion, Arrays.hashCode(sha256));
        }

        @Override
        public String toString() {
            return fhirVersion + " " + nameVersion + " " + HexFormat.of().formatHex(sha256, 0, Math.min(6, sha256.length));
        }
    }
}
