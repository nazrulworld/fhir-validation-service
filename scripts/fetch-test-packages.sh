#!/bin/sh
# Spec 010 (T001): downloads the packages listed in src/test/resources/ig/packages/010-packages.lock once, into
# ${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}, and checks every sha256. Tests read them from there and stay
# offline; a missing package fails the test with a pointer to this script.
# usage: scripts/fetch-test-packages.sh
set -eu
REPO=$(cd "$(dirname "$0")/.." && pwd)
LOCK="$REPO/src/test/resources/ig/packages/010-packages.lock"
DIR=${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}
mkdir -p "$DIR"

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

grep -v '^#' "$LOCK" | grep -v '^[[:space:]]*$' | while read -r id url sum; do
    file="$DIR/$id.tgz"
    if [ -f "$file" ] && [ "$(sha256 "$file")" = "$sum" ]; then
        continue
    fi
    echo "fetching $id"
    tmp="$file.part"
    curl -fsSL --retry 3 -o "$tmp" "$url"
    actual=$(sha256 "$tmp")
    if [ "$actual" != "$sum" ]; then
        rm -f "$tmp"
        echo "sha256 mismatch for $id: expected $sum, got $actual" >&2
        exit 1
    fi
    mv "$tmp" "$file"
done
echo "test packages ready in $DIR"
