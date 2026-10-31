#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f android/local.properties ]]; then
  echo 'Missing android/local.properties: define sdk.dir and ndk.dir explicitly.' >&2
  exit 2
fi
target=$(opam exec -- ocamlfind -toolchain android ocamlopt -config-var target)
case "$target" in aarch64*android*) ;; *) echo "Android ARM64 toolchain unavailable: $target" >&2; exit 2 ;; esac
export GRADLE_USER_HOME="$PWD/_build/gradle-home"
export ANDROID_USER_HOME="$PWD/_build/.android"
export ANDROID_PREFS_ROOT="$PWD/_build"
export ANDROID_SDK_HOME="$PWD/_build"
mkdir -p "$ANDROID_USER_HOME"
export OCAMLRUNPARAM=b
opam exec -- dune build --workspace dune-workspace.android _build/default.android/lib/android.cmxa
android/gradlew --no-daemon -p android --project-cache-dir "$PWD/_build/gradle-project-cache" :library:assembleRelease "$@"
