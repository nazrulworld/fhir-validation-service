# Licenses in this image

- **The service**: `/app/LICENSE.md`. The FHIR Validation Service is licensed under the PolyForm Noncommercial License
  1.0.0. Commercial use requires a separate license from Md Nazrul Islam (email2nazrul@gmail.com).
- **Libraries bundled in `/app/app.jar`**: this directory. The same files are also inside the jar.
  - `THIRD-PARTY.txt`: every bundled library with its version and license (SPDX ids).
  - `third-party-licenses/`: the text of each license used.
  - `THIRD-PARTY-CLARIFICATIONS.md`: elected licenses for multi-licensed libraries, licenses asserted for libraries
    without license metadata, and source-availability notes.
  - `NOTICE`: the Required Notice followed by the merged `META-INF/NOTICE` content of the bundled libraries.
  - `NOTICE-ROOT`: the NOTICE content of libraries that keep it at the jar root (the `ph-*` libraries).
- **Java runtime (Eclipse Temurin JRE)**: its licenses and notices are in `/opt/java/openjdk/legal/`.
- **Operating system packages** (Ubuntu, including `tini` and `curl`): each package's copyright and license file is
  `/usr/share/doc/<package>/copyright`.

These components are not covered by the PolyForm Noncommercial License; each stays under its own license.
