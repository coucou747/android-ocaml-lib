#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then echo 'Usage: scripts/check-jni.sh <watchdog-seconds>' >&2; exit 2; fi
watchdog=$1
project_dir=$PWD
java_dir=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
work="$project_dir/_build/jni-smoke"
mkdir -p "$work"
cp lib/android.ml lib/android.mli test/jni/smoke.ml "$work/"
cd "$work"
export OCAMLRUNPARAM=b
ocaml_dir=$(ocamlc -where)
cc -fPIC -Wall -Wextra -D__ANDROID__ -I"$ocaml_dir" -I"$java_dir/include" -I"$java_dir/include/linux" -c "$project_dir/lib/bridge.c" -o bridge.o
ocamlfind ocamlopt -g -thread -package lwt.unix -c android.mli
ocamlfind ocamlopt -g -thread -package lwt.unix -linkpkg -runtime-variant _pic -output-obj android.ml smoke.ml -o runtime.o
lwt_dir=$(ocamlfind query lwt.unix)
read -r -a lwt_flags <<< "$(ocamlobjinfo "$lwt_dir/lwt_unix.cmxa" | sed -n 's/^Extra C object files: //p')"
cc -shared runtime.o bridge.o -L"$lwt_dir" "${lwt_flags[@]}" "$ocaml_dir/libthreadsnat.a" "$ocaml_dir/libunixnat.a" "$ocaml_dir/libasmrun_pic.a" -lpthread -lm -ldl -o libsmoke.so
javac -d . "$project_dir/test/jni/Bridge.java"
java -cp . org.ocaml.android.Bridge "$work/libsmoke.so" "$watchdog"
