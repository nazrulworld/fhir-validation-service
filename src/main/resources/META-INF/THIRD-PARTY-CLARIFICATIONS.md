# Third-party license clarifications

The FHIR Validation Service is licensed under the PolyForm Noncommercial License 1.0.0 (`LICENSE.md`). The
third-party libraries bundled in this package are **not** covered by that license. Each one stays under its own
license, as listed in `META-INF/THIRD-PARTY.txt`, with the license texts in `META-INF/third-party-licenses/` and the
NOTICE content of the libraries merged into `META-INF/NOTICE`.

This file records where the listing needs explaining. That covers libraries offered under several licenses (and
which one this distribution relies on), libraries whose metadata names no license, and source-availability notes.
License ids are SPDX identifiers.

## Vert.x modules

- **Coordinates**: `io.vertx:*` (for example `io.vertx:vertx-core`, `io.vertx:vertx-web`, `io.vertx:vertx-web-openapi`)
- **Offered**: Apache-2.0, or EPL-2.0 (`vertx-core`, `vertx-web`) / EPL-1.0 (other modules)
- **Elected**: Apache-2.0
- **Reason**: The modules are dual-licensed at the recipient's choice. Apache-2.0 is the license used for most of
  this distribution.

## Javassist

- **Coordinates**: `org.javassist:javassist`
- **Offered**: Apache-2.0, LGPL-2.1, MPL-1.1
- **Elected**: Apache-2.0
- **Reason**: Tri-licensed at the recipient's choice.

## Jakarta Annotations API

- **Coordinates**: `jakarta.annotation:jakarta.annotation-api`
- **Offered**: EPL-2.0, or GPL-2.0 with the Classpath Exception
- **Elected**: EPL-2.0
- **Source**: Redistributed unmodified. The source code is available from
  <https://github.com/jakartaee/common-annotations-api> and as the `-sources.jar` on Maven Central.

## HdrHistogram

- **Coordinates**: `org.hdrhistogram:HdrHistogram`
- **Offered**: BSD-2-Clause, or CC0-1.0 (public domain dedication)
- **Elected**: BSD-2-Clause

## jakarta-regexp

- **Coordinates**: `jakarta-regexp:jakarta-regexp:1.4`
- **Metadata**: The POM declares no license.
- **Asserted**: Apache-2.0, via `src/license/THIRD-PARTY.properties` in the source repository.
- **Reason**: Jakarta Regexp is a project of the Apache Software Foundation (Apache Jakarta) and was distributed under
  the Apache License.

## Saxon-HE

- **Coordinates**: `net.sf.saxon:Saxon-HE`
- **License**: MPL-2.0
- **Source**: Redistributed unmodified. The source code form is available from Saxonica (<https://www.saxonica.com>)
  and as the `-sources.jar` on Maven Central.

## Eclipse Distribution License 1.0

- **Coordinates**: `jakarta.activation:jakarta.activation-api`, `jakarta.xml.bind:jakarta.xml.bind-api`
- **License**: Eclipse Distribution License 1.0, which is the BSD 3-Clause license. It is listed as BSD-3-Clause, and
  its text is `META-INF/third-party-licenses/BSD-3-Clause.txt`.

## Placeholders in license texts

The texts in `META-INF/third-party-licenses/` are the SPDX reference texts. Where a license has a copyright
placeholder (MIT, BSD), the actual copyright holders are the authors of each library, as named in the library's own
distribution and in `META-INF/NOTICE`.
