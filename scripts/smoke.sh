#!/usr/bin/env bash
# Smoke test for the native binary: runs every command against the generated
# fixture corpus and asserts exit codes and key JSON fields.
# Usage: scripts/smoke.sh <path-to-pdfgate-binary>
set -u

BIN=${1:?usage: smoke.sh <pdfgate-binary>}
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT
FAILURES=0

scala-cli run . --main-class pdfgate.testkit.GenFixtures -- "$DIR" >/dev/null

expect() { # expect <exit-code> <grep-pattern> <args...>
  local want_code=$1 pattern=$2; shift 2
  local out code
  out=$("$BIN" "$@" 2>/dev/null); code=$?
  if [ "$code" != "$want_code" ]; then
    echo "FAIL [$*] exit $code != $want_code"; FAILURES=$((FAILURES+1))
  elif ! grep -q "$pattern" <<<"$out"; then
    echo "FAIL [$*] output missing pattern: $pattern"; FAILURES=$((FAILURES+1))
  else
    echo "ok   [$*]"
  fi
}

expect 0 '"pages": 1'                      info "$DIR/simple.pdf"
expect 0 '"name": "Watermark"'             layers "$DIR/with-layers.pdf"
expect 0 '"name": "email"'                 forms "$DIR/with-form.pdf"
expect 0 '"keyLengthBits": 256'            crypto "$DIR/encrypted.pdf" --password user-secret
expect 0 '"passwordRequired": true'        crypto "$DIR/encrypted.pdf"
expect 0 '"javascript": 1'                 scan "$DIR/with-js.pdf"
expect 0 '"embedded-file"'                 scan "$DIR/with-attachment.pdf"
expect 0 'exfil'                           scan "$DIR/with-uri.pdf"
expect 0 '"ok": true'                      validate "$DIR/simple.pdf"
expect 0 '"findings": \[\]'                scan "$DIR/with-image.pdf"
expect 0 'Hello from pdfgate fixtures'     text "$DIR/with-image.pdf"
expect 0 '日本語のテキスト'                text "$DIR/with-japanese.pdf"
expect 0 '日本語タイトル'                  info "$DIR/with-japanese.pdf"
expect 0 '"findings": \[\]'                scan "$DIR/with-japanese.pdf"
expect 1 '"code": "javascript"'            validate "$DIR/with-js.pdf"
expect 1 '"code": "password-protected"'    validate "$DIR/encrypted.pdf"
expect 2 'parse-failure'                   validate "$DIR/broken.pdf"
expect 2 'parse-failure'                   info "$DIR/broken.pdf"
expect 0 'Hello from pdfgate fixtures'     text "$DIR/simple.pdf"

if [ "$FAILURES" -gt 0 ]; then echo "$FAILURES smoke test(s) failed"; exit 1; fi
echo "all smoke tests passed"
