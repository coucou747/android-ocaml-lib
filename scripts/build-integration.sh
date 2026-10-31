#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then echo 'Usage: scripts/build-integration.sh <explicit-test-target-sdk>' >&2; exit 2; fi
scripts/build-android.sh
export OCAMLRUNPARAM=b
opam exec -- dune build --workspace dune-workspace.android _build/default.android/test/native/integration.so
mkdir -p _build/test-jni/arm64-v8a
install -m 644 _build/default.android/test/native/integration.so _build/test-jni/arm64-v8a/libintegration.so
export GRADLE_USER_HOME="$PWD/_build/gradle-home"
export ANDROID_USER_HOME="$PWD/_build/.android"
export ANDROID_PREFS_ROOT="$PWD/_build"
export ANDROID_SDK_HOME="$PWD/_build"
mkdir -p "$ANDROID_USER_HOME"
android/gradlew --no-daemon -p android --project-cache-dir "$PWD/_build/gradle-project-cache" -PtestTargetSdk="$1" :integration:assembleDebug
