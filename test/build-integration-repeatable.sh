#!/usr/bin/env bash
# Integration regression: a second build must replace the existing native library.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then echo 'Usage: test/build-integration-repeatable.sh <target-sdk>' >&2; exit 2; fi
mkdir -p _build
for pass in 1 2; do
    log="_build/integration-repeatable-$pass.log"
    scripts/build-integration.sh "$1" 2>&1 | tee "$log"
    if rg -q '\[CXX1104\]' "$log"; then
        echo 'NDK version mismatch in Android integration build' >&2
        exit 1
    fi
done
test -s _build/gradle/integration/outputs/apk/debug/integration-debug.apk
