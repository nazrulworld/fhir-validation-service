# Test IG packages

- `hl7/fhir/us/core/hl7.fhir.us.core-7.0.0.tgz`: committed fixture for archive and parsing tests
  (source: https://packages.fhir.org/hl7.fhir.us.core/7.0.0).
- `010-packages.lock`: packages used by the spec 010 shared-definitions tests (DK Core 3.5.0 and MedCom Core 4.0.0
  with their full dependency closure). They are **not** committed. `scripts/fetch-test-packages.sh` downloads them
  once from packages.fhir.org into `${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}` and checks every sha256.
  Tests read them through `Fixtures.lockedPackage(...)` and stay offline; a missing package fails the test with a
  pointer to the script. CI caches the folder, keyed by the lockfile's hash.
