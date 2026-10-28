#!/bin/sh
# License packaging check (specs/002-polyform-noncommercial-license/contracts/distribution-layout.md):
#   the jar, and optionally the container image, carry the project license, the Required Notice and the
#   third-party notices.
# Usage: sh scripts/check-license-packaging.sh <jar> [<image>]   (from the repository root; needs unzip, docker)
#        sh scripts/check-license-packaging.sh --image <image>   (checks the image and the jar inside it)
set -eu

NOTICE="Required Notice: Copyright 2025-2026 Md Nazrul Islam (https://github.com/nazrulworld)"
SPDX="PolyForm-Noncommercial-1.0.0"
AUTHORS="Md Nazrul Islam <email2nazrul@gmail.com>"

usage() { echo "usage: $0 <jar> [<image>] | --image <image>" >&2; exit 2; }
[ $# -ge 1 ] || usage
if [ "$1" = "--image" ]; then
  [ $# -eq 2 ] || usage
  IMAGE="$2"
  tmp="$(mktemp -d)"
  container="$(docker create "$IMAGE")"
  trap 'docker rm -f "$container" > /dev/null 2>&1 || true; rm -rf "$tmp"' EXIT
  docker cp "$container:/app/app.jar" "$tmp/app.jar" > /dev/null
  JAR="$tmp/app.jar"
else
  JAR="$1"
  IMAGE="${2:-}"
fi
[ -f "$JAR" ] || { echo "jar not found: $JAR" >&2; exit 2; }

failures=0
check() { # check <description> <command...>
  desc="$1"; shift
  if "$@" > /dev/null 2>&1; then echo "OK   $desc"; else echo "FAIL $desc"; failures=$((failures + 1)); fi
}

has_entry() { unzip -l "$JAR" "$1" | grep -q " $1\$"; }
same_license() { unzip -p "$JAR" LICENSE.md | cmp -s - LICENSE.md; }
notice_first() { [ "$(unzip -p "$JAR" META-INF/NOTICE | head -1)" = "$NOTICE" ]; }
has_texts() { unzip -l "$JAR" 'META-INF/third-party-licenses/*.txt' | grep -q 'META-INF/third-party-licenses/.*\.txt'; }

echo "==> jar $JAR"
check "LICENSE.md at the jar root" has_entry LICENSE.md
check "LICENSE.md identical to the repository's" same_license
check "META-INF/NOTICE starts with the Required Notice" notice_first
check "META-INF/THIRD-PARTY.txt" has_entry META-INF/THIRD-PARTY.txt
check "META-INF/THIRD-PARTY-CLARIFICATIONS.md" has_entry META-INF/THIRD-PARTY-CLARIFICATIONS.md
check "META-INF/third-party-licenses/*.txt" has_texts

if [ -n "$IMAGE" ]; then
  label() { docker image inspect -f "{{ index .Config.Labels \"$1\" }}" "$IMAGE"; }
  label_is() { [ "$(label "$1")" = "$2" ]; }
  in_image() { docker run --rm --entrypoint sh "$IMAGE" -c "$1"; }

  echo "==> image $IMAGE"
  check "label org.opencontainers.image.licenses = $SPDX" label_is org.opencontainers.image.licenses "$SPDX"
  check "label org.opencontainers.image.authors = $AUTHORS" label_is org.opencontainers.image.authors "$AUTHORS"
  check "files under /app/licenses" in_image 'test -f /app/LICENSE.md && test -f /app/licenses/NOTICE \
    && test -f /app/licenses/THIRD-PARTY.txt && test -f /app/licenses/THIRD-PARTY-CLARIFICATIONS.md \
    && test -f /app/licenses/IMAGE-COMPONENTS.md && test -d /app/licenses/third-party-licenses'
  check "/app/LICENSE.md starts with the Required Notice" in_image "[ \"\$(head -1 /app/LICENSE.md)\" = \"$NOTICE\" ]"
fi

if [ "$failures" -gt 0 ]; then
  echo "LICENSE PACKAGING CHECK FAILED: $failures check(s)" >&2
  exit 1
fi
echo "==> license packaging OK"
