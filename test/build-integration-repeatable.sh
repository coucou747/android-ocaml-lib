#!/usr/bin/env bash
# Integration regression: a second build must replace the existing native library.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then echo 'Usage: test/build-integration-repeatable.sh <target-sdk>' >&2; exit 2; fi
scripts/build-integration.sh "$1"
scripts/build-integration.sh "$1"
test -s _build/gradle/integration/outputs/apk/debug/integration-debug.apk
