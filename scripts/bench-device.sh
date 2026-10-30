#!/usr/bin/env bash
# bench-device: full-modality benchmark, generic EmbeddingGemma 2 740M pack (see docs/bench/).
# Usage: make bench-device  (honours DEVICE=..., default 8316b289; MODEL=... overrides)
# Flow: device fingerprint -> pull one real DCIM photo (if none staged yet) ->
# push model + photo + 30s audio fixture -> am start --ez bench true -> poll for
# files/bench-sm8635-full.json (pulled to docs/bench/last-full.json).
set -euo pipefail
cd "$(dirname "$0")/.."
DEV="${DEVICE:-8316b289}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
ADB="${ANDROID_SDK_ROOT}/platform-tools/adb"
APK="android/build/outputs/apk/debug/android-debug.apk"
MODEL="${MODEL:-/tmp/opencode/models/embeddinggemma-2-740m.litertlm}"
MODEL_NAME="$(basename "$MODEL")"
PHOTO_LOCAL="/tmp/opencode/bench-photo.jpg"
AUDIO_LOCAL="${AUDIO_FIXTURE:-/tmp/opencode/bench-audio.wav}"
APP_ID="com.filesearch.android"

echo "== device fingerprint =="
PHONEMODEL=$($ADB -s "$DEV" shell getprop ro.product.model | tr -d '\r')
SOC=$($ADB -s "$DEV" shell getprop ro.soc.model | tr -d '\r')
SDK=$($ADB -s "$DEV" shell getprop ro.build.version.sdk | tr -d '\r')
ABI=$($ADB -s "$DEV" shell getprop ro.product.cpu.abi | tr -d '\r')
BOARD=$($ADB -s "$DEV" shell getprop ro.board.platform | tr -d '\r')
echo "model=$PHONEMODEL soc=$SOC board=$BOARD sdk=$SDK abi=$ABI"

echo "== RAM =="
$ADB -s "$DEV" shell cat /proc/meminfo | head -3 | tr -d '\r'
FREE_KB=$($ADB -s "$DEV" shell "awk '/MemAvailable/ {print \$2}' /proc/meminfo" | tr -d '\r')
FREE_GB=$(awk "BEGIN{printf \"%.1f\", $FREE_KB/1024/1024}")
echo "free_ram_gb=$FREE_GB"
if [ "$FREE_KB" -lt 2000000 ]; then
  echo "WARNING: <2GB free RAM — any latency numbers from this phone right now would lie."
fi

echo "== NNAPI / delegate libs present =="
$ADB -s "$DEV" shell ls /system/lib64/libnnapi.so /vendor/lib64/libnnapi.so 2>&1 | tr -d '\r' || true
$ADB -s "$DEV" shell "ls /vendor/lib64/*[Nn]eural* /system/lib64/*[Nn]eural* 2>/dev/null || echo '(no vendor neural libs visible)'" | tr -d '\r'

echo "== thermal zones =="
$ADB -s "$DEV" shell "for z in /sys/class/thermal/thermal_zone*/type; do echo \"\$z: \$(cat \$z)\"; done" | tr -d '\r' | head -20

echo "== CPU =="
$ADB -s "$DEV" shell cat /proc/cpuinfo | grep -m4 -i 'processor\|Hardware\|model name' | tr -d '\r' || true

echo "baseline ok"
echo "== full-modality benchmark (740M) =="

test -f "$MODEL" || { echo "missing model: $MODEL"; exit 1; }
test -f "$AUDIO_LOCAL" || python3 scripts/make-audio-fixture.py "$AUDIO_LOCAL"

if [ ! -f "$PHOTO_LOCAL" ]; then
  echo "pulling one real photo from DCIM (read-only, nothing added to the phone)..."
  CANDIDATE=$($ADB -s "$DEV" shell 'ls /sdcard/DCIM/Camera/*.jpg 2>/dev/null | head -1; ls /sdcard/DCIM/*.jpg 2>/dev/null | head -1' | tr -d '\r' | head -1)
  test -n "$CANDIDATE" || { echo "no JPEG found under /sdcard/DCIM"; exit 1; }
  echo "photo: $CANDIDATE"
  $ADB -s "$DEV" pull "$CANDIDATE" "$PHOTO_LOCAL"
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
./gradlew :android:assembleDebug --console=plain -q
$ADB -s "$DEV" install -r "$APK"
echo "pushing model ($(du -h "$MODEL" | cut -f1)) + fixtures..."
$ADB -s "$DEV" push "$MODEL" "/data/local/tmp/$MODEL_NAME"
$ADB -s "$DEV" push "$PHOTO_LOCAL" /data/local/tmp/bench-photo.jpg
$ADB -s "$DEV" push "$AUDIO_LOCAL" /data/local/tmp/bench-audio.wav
$ADB -s "$DEV" shell am start -n "$APP_ID/.MainActivity" --ez bench true
echo "waiting for bench (740M init + 3 infers can take several minutes)..."
for i in $(seq 1 40); do
  sleep 15
  if $ADB -s "$DEV" shell "run-as $APP_ID cat files/bench-sm8635-full.json" > docs/bench/last-full.json 2>/dev/null \
    && python3 -c "import json; json.load(open('docs/bench/last-full.json'))" 2>/dev/null; then
    echo "result pulled to docs/bench/last-full.json after ~$((i * 15))s"
    cat docs/bench/last-full.json
    exit 0
  fi
done
echo "timed out waiting for files/bench-sm8635-full.json — check logcat:"
echo "  $ADB -s $DEV logcat -d -s EmbeddingBench | tail -30"
exit 1
