package nzi.fhir.validator.core.validator;

import nzi.fhir.validator.core.enums.SupportedFhirVersion;

import java.util.Objects;

/**
 * Identity of one validator context (spec 004, data-model §2.1): a FHIR version plus the one IG package it validates
 * against, or {@code null} for the core-only base context.
 *
 * @param igPackage a concrete {@code name#version}, or {@code null} for the base context
 */
public record SnapshotKey(SupportedFhirVersion fhirVersion, String igPackage) {

    public SnapshotKey {
        Objects.requireNonNull(fhirVersion, "fhirVersion");
        if (igPackage != null && igPackage.indexOf('#') <= 0) {
            throw new IllegalArgumentException("igPackage must be a concrete name#version");
        }
    }

    public static SnapshotKey base(SupportedFhirVersion fhirVersion) {
        return new SnapshotKey(fhirVersion, null);
    }

    public static SnapshotKey of(SupportedFhirVersion fhirVersion, String igPackage) {
        return new SnapshotKey(fhirVersion, igPackage);
    }

    public boolean isBase() {
        return igPackage == null;
    }

    /** {@code name#version}, or {@code base}: the value of the {@code X-Validation-Context} header. */
    public String contextLabel() {
        return igPackage == null ? "base" : igPackage;
    }

    @Override
    public String toString() {
        return fhirVersion.name() + ":" + contextLabel();
    }
}
