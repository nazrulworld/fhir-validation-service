#!/bin/sh
# Spec 011 T019: tests scripts/docs-site-check-editor.sh with a stub `gh` (no network). Run: sh <this file>
set -u
here=$(cd "$(dirname "$0")" && pwd)
script="$here/../../docs-site-check-editor.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
cat > "$work/bin/gh" <<'EOF'
#!/bin/sh
# stub: records its arguments, answers with $STUB_PERMISSION, or fails when STUB_FAIL=1
printf '%s\n' "$@" > "$STUB_LOG"
[ "${STUB_FAIL:-0}" = 1 ] && { echo "HTTP 404" >&2; exit 1; }
printf '%s\n' "$STUB_PERMISSION"
EOF
chmod +x "$work/bin/gh"

failures=0
check() { # check <expected exit> <description> <login> <permission> [fail]
  expected=$1 description=$2
  : > "$work/log"
  output=$(cd "$work" && PATH="$work/bin:$PATH" STUB_LOG="$work/log" STUB_PERMISSION="$4" STUB_FAIL="${5:-0}" \
    EDITOR_LOGIN="$3" GITHUB_REPOSITORY=owner/repo sh "$script" 2>&1)
  actual=$?
  if [ "$actual" -ne "$expected" ]; then
    echo "FAIL: $description: exit $actual, expected $expected"; echo "$output"; failures=$((failures + 1))
  else
    echo "ok: $description"
  fi
  LAST_OUTPUT=$output
}

check 0 "write may deploy" alice write
check 0 "maintain may deploy" alice maintain
check 0 "admin may deploy" alice admin
grep -qx "repos/owner/repo/collaborators/alice/permission" "$work/log" || { echo "FAIL: API path"; failures=$((failures + 1)); }
check 1 "read may not deploy" mallory read
case $LAST_OUTPUT in *"mallory who has no push access"*) ;; *) echo "FAIL: message names the editor"; failures=$((failures + 1));; esac
check 1 "none may not deploy" mallory none
check 1 "API error fails closed" mallory write 1
check 1 "empty login" "" write
check 0 "app login" "dependabot[bot]" write

newline='alice
mallory'
for hostile in "$newline" 'a[bot][bot]' '-alice' '$(touch pwned)' '`touch pwned`' 'a;touch pwned' "a'b" 'a"b' '::error::x' 'alice/../../x'; do
  check 1 "hostile login rejected: $hostile" "$hostile" write
  [ -s "$work/log" ] && { echo "FAIL: gh was called for $hostile"; failures=$((failures + 1)); }
  case $LAST_OUTPUT in *"$hostile"*) echo "FAIL: hostile login echoed"; failures=$((failures + 1));; esac
done
[ -e "$work/pwned" ] && { echo "FAIL: a login was executed"; failures=$((failures + 1)); }

if [ "$failures" -ne 0 ]; then echo "$failures failure(s)"; exit 1; fi
echo "all editor-check tests passed"
