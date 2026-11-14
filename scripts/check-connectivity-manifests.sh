#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -ne 1 ]]; then echo 'Usage: scripts/check-connectivity-manifests.sh <target-sdk>' >&2; exit 2; fi
export GRADLE_USER_HOME="$PWD/_build/gradle-home" ANDROID_USER_HOME="$PWD/_build/.android" ANDROID_PREFS_ROOT="$PWD/_build" ANDROID_SDK_HOME="$PWD/_build"
for mode in baseline bluetooth audio all location; do
  extra=()
  if [[ $mode != baseline ]]; then extra=("-PconnectivityCapabilities=$mode"); fi
  android/gradlew --no-daemon -p android --project-cache-dir "$PWD/_build/gradle-project-cache" "-PtestTargetSdk=$1" "${extra[@]}" :integration:processDebugMainManifest
  python3 - "$mode" <<'PY'
import sys, xml.etree.ElementTree as E
from pathlib import Path
mode=sys.argv[1]; candidates=list(Path('_build/gradle/integration/intermediates').glob('merged_manifests/debug/processDebugMainManifest/AndroidManifest.xml'))
if not candidates: candidates=list(Path('_build/gradle/integration/intermediates').glob('merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml'))
assert len(candidates)==1, candidates
r=E.parse(candidates[0]).getroot();a='{http://schemas.android.com/apk/res/android}'
p={n.get(a+'name'):n for n in r.findall('uses-permission')};services={n.get(a+'name'):n.get(a+'enabled','true') for n in r.find('application').findall('service')}
bt=mode in ('bluetooth','all','location');audio=mode in ('audio','all')
assert ('android.permission.BLUETOOTH_CONNECT' in p)==bt
assert ('android.permission.BLUETOOTH_SCAN' in p)==bt
assert ('android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE' in p)==bt
assert ('android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK' in p)==audio
assert 'android.permission.RECORD_AUDIO' not in p
assert services['org.ocaml.android.BluetoothConnectionService']==str(bt).lower()
assert services['org.ocaml.android.AudioPlaybackService']==str(audio).lower()
if bt: assert ('neverForLocation' in p['android.permission.BLUETOOTH_SCAN'].get(a+'usesPermissionFlags','')) == (mode!='location')
print('Connectivity manifest checked:',mode)
PY
done
# Restore the baseline artifact after exercising overlays.
android/gradlew --no-daemon -p android --project-cache-dir "$PWD/_build/gradle-project-cache" "-PtestTargetSdk=$1" :integration:assembleDebug
