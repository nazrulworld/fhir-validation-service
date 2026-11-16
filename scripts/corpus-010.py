#!/usr/bin/env python3
"""Spec 010 (T002): builds the differential corpus src/test/resources/corpus/010/ from the locked test packages.

Deterministic: the same packages give the same files. Run with `python3 -I scripts/corpus-010.py` after
scripts/fetch-test-packages.sh. Layout (one context per top-level folder name, see README.md there):

  examples/<context>/<file>.json             every example resource of the context's own package
  invalid/<context>/<mutation>-<file>.json   invalid variants derived from those examples (>= 50 in total)
  duplicate-canonical/                      a test package redefining a DK Core canonical, plus a resource
"""
import copy
import io
import json
import os
import sys
import tarfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "src", "test", "resources", "corpus", "010")
PACKAGES = os.environ.get("FV_TEST_PACKAGES") or os.path.join(os.path.expanduser("~"), ".cache", "fv-test-packages")
CONTEXTS = {"dk-core": "hl7.fhir.dk.core#3.5.0", "medcom-core": "medcom.fhir.dk.core#4.0.0"}
DK_PATIENT = "http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient"
DK_OBSERVATION = "http://hl7.dk/fhir/core/StructureDefinition/dk-core-observation"
UNKNOWN_EXTENSION = "http://example.org/fhir/StructureDefinition/unknown-extension"
STATUS_REQUIRED = {"Observation", "Encounter", "Condition", "MedicationStatement", "Procedure", "DiagnosticReport",
                   "CarePlan", "CareTeam", "Communication", "ServiceRequest", "Task", "DocumentReference",
                   "Appointment", "Consent", "Device", "Flag", "Goal", "List", "Provenance", "QuestionnaireResponse"}


def examples(package_id):
    path = os.path.join(PACKAGES, package_id + ".tgz")
    if not os.path.isfile(path):
        sys.exit(f"{path} is missing: run scripts/fetch-test-packages.sh")
    found = []
    with tarfile.open(path) as tar:
        for member in sorted(tar.getmembers(), key=lambda m: m.name):
            if member.name.startswith("package/example/") and member.name.endswith(".json") and member.isfile():
                resource = json.load(tar.extractfile(member))
                if isinstance(resource, dict) and "resourceType" in resource:
                    found.append((os.path.basename(member.name), resource))
    return found


def first_coding(node):
    """The first Coding (a dict with system and code) found depth-first, outside meta and extensions."""
    if isinstance(node, dict):
        if "system" in node and "code" in node and isinstance(node.get("code"), str):
            return node
        for key in sorted(node):
            if key in ("meta", "extension", "modifierExtension", "contained", "text"):
                continue
            hit = first_coding(node[key])
            if hit is not None:
                return hit
    elif isinstance(node, list):
        for item in node:
            hit = first_coding(item)
            if hit is not None:
                return hit
    return None


def mutations(resource):
    """(name, mutated resource) pairs that apply to this resource; each is a known kind of invalid input."""
    out = []
    rtype = resource["resourceType"]
    if rtype == "Bundle":
        return out
    bad = copy.deepcopy(resource)
    coding = first_coding(bad)
    if coding is not None:
        coding["code"] = "not-a-real-code-010"
        out.append(("bad-code", bad))
    if rtype in STATUS_REQUIRED and "status" in resource:
        missing = copy.deepcopy(resource)
        del missing["status"]
        out.append(("missing-status", missing))
    ext = copy.deepcopy(resource)
    ext.setdefault("extension", []).append({"url": UNKNOWN_EXTENSION, "valueString": "x"})
    out.append(("unknown-extension", ext))
    wrong = copy.deepcopy(resource)
    wrong.setdefault("meta", {})["profile"] = [DK_OBSERVATION if rtype != "Observation" else DK_PATIENT]
    out.append(("wrong-profile", wrong))
    if rtype == "Patient" and resource.get("identifier"):
        slice_bad = copy.deepcopy(resource)
        for identifier in slice_bad["identifier"]:
            identifier["value"] = "abc"
        out.append(("bad-identifier-slice", slice_bad))
    return out


def write(path, resource):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(resource, f, indent=2, ensure_ascii=False, sort_keys=True)
        f.write("\n")


def duplicate_canonical_package():
    """A package that redefines dk-core-patient (same canonical URL) to require birthDate (spec SC-004, FR-021)."""
    with tarfile.open(os.path.join(PACKAGES, "hl7.fhir.dk.core#3.5.0.tgz")) as tar:
        sd = json.load(tar.extractfile("package/StructureDefinition-dk-core-patient.json"))
    sd = copy.deepcopy(sd)
    sd["id"] = "dk-core-patient"
    sd["publisher"] = "fhir-validation-service test (spec 010)"
    for section in ("snapshot", "differential"):
        for element in sd.get(section, {}).get("element", []):
            if element.get("path") == "Patient.birthDate":
                element["min"] = 1
    package_json = {"name": "test.dup.canonical", "version": "1.0.0", "fhirVersions": ["4.0.1"],
                    "dependencies": {"hl7.fhir.r4.core": "4.0.1", "hl7.fhir.dk.core": "3.5.0"}}
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz", format=tarfile.USTAR_FORMAT) as tar:
        for name, content in (("package/package.json", package_json),
                              ("package/StructureDefinition-dk-core-patient.json", sd)):
            data = json.dumps(content, indent=2, sort_keys=True).encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mtime = 0
            tar.addfile(info, io.BytesIO(data))
    folder = os.path.join(OUT, "duplicate-canonical")
    os.makedirs(folder, exist_ok=True)
    with open(os.path.join(folder, "test.dup.canonical-1.0.0.tgz"), "wb") as f:
        f.write(buffer.getvalue())
    write(os.path.join(folder, "resources", "Patient-no-birthdate.json"), {
        "resourceType": "Patient", "id": "no-birthdate", "meta": {"profile": [DK_PATIENT]},
        "name": [{"family": "Test", "given": ["Spec"]}], "gender": "female"})


def main():
    total_invalid = 0
    for context, package_id in CONTEXTS.items():
        for name, resource in examples(package_id):
            write(os.path.join(OUT, "examples", context, name), resource)
            for mutation, mutated in mutations(resource):
                write(os.path.join(OUT, "invalid", context, f"{mutation}-{name}"), mutated)
                total_invalid += 1
    duplicate_canonical_package()
    if total_invalid < 50:
        sys.exit(f"only {total_invalid} invalid variants; spec SC-004 needs at least 50")
    print(f"corpus written to {OUT}: {total_invalid} invalid variants")


if __name__ == "__main__":
    main()
