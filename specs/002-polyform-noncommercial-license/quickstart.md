# Quickstart: Validating the Licensing Feature

**Prerequisites**: JDK 17, Docker (with buildx for the arm64 check), and a repository checkout.
The expected layout is in [contracts/distribution-layout.md](./contracts/distribution-layout.md).

## 1. License text is official and carries the notice (US1, FR-001/002, SC-002)

```sh
head -1 LICENSE.md
# → Required Notice: Copyright 2025-2026 Md Nazrul Islam (https://github.com/nazrulworld)
tail -n +3 LICENSE.md | shasum -a 256
# → c0ea4a896d2c8c394b29f9427589996db826cd501c512279ff0ed3ef48fabbe5
```

The same check runs automatically as `LicenseFileTest` in `./mvnw verify`.

## 2. Build generates the third-party notices and still passes (FR-013–015, SC-005, SC-006)

```sh
./mvnw -B verify
```

**Expected**:
- All existing tests pass, plus `LicenseFileTest` and `ThirdPartyNoticesTest`.
- `target/classes/META-INF/THIRD-PARTY.txt` exists and contains no `Unknown license`.

**Negative check (FR-015)**: Temporarily remove the `jakarta-regexp` line from
`src/license/THIRD-PARTY.properties` and run `./mvnw -B -q package -DskipTests`. The build
MUST fail and name the artifact. Restore the line afterwards.

## 3. Jar carries license and notices (US3, FR-008, SC-004)

```sh
sh scripts/check-license-packaging.sh target/fhir-validation-service-*.jar
unzip -p target/fhir-validation-service-*.jar META-INF/NOTICE | head -1   # Required Notice
```

## 4. Image carries label and files (US3, FR-009)

```sh
docker build -t fhir-validation-service:lic .
sh scripts/check-license-packaging.sh target/fhir-validation-service-*.jar fhir-validation-service:lic
docker inspect -f '{{ index .Config.Labels "org.opencontainers.image.licenses" }}' fhir-validation-service:lic
# → PolyForm-Noncommercial-1.0.0
docker run --rm --entrypoint ls fhir-validation-service:lic /app/licenses
```

## 5. Service behaviour unchanged (SC-005)

```sh
sh scripts/smoke.sh   # existing smoke test, as in CI
```

## 6. Documentation (US2, US4, FR-004–006, FR-010–011)

- README: the badge is visible at the top. The `## License` section names the license, links
  `LICENSE.md` and the official page, states that commercial use requires a separate license
  and gives **Md Nazrul Islam, email2nazrul@gmail.com** as the contact. It also points to
  `/app/licenses` and the jar's `META-INF/` for third-party notices.
- `CONTRIBUTING.md` exists, and `CHANGELOG.md` → `[Unreleased]` has the license entry.
- After merge, the GitHub repository sidebar shows a license link to `LICENSE.md`. GitHub
  classifies it as *Other*, because PolyForm isn't in GitHub's license list (research R3).
