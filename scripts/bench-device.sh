#!/usr/bin/env bash
# bench-device: capability baseline + one-query smoke benchmark (see docs/bench/).
# Usage: make bench-device  (honours DEVICE=..., default 8316b289; MODEL=... overrides)
set -euo pipefail
cd "$(dirname "$0")/.."
DEV="${DEVICE:-8316b289}"
ADB="${ANDROID_SDK_ROOT:-$HOME/android-sdk}/platform-tools/adb"
APK="android/build/outputs/apk/debug/android-debug.apk"
MODEL="${MODEL:-/tmp/opencode/models/embeddinggemma-2-text-270m.litertlm}"

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
echo "== smoke benchmark =="
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
./gradlew :android:assembleDebug --console=plain -q
$ADB -s "$DEV" install -r "$APK"
$ADB -s "$DEV" push "$MODEL" /data/local/tmp/embeddinggemma-2-text-270m.litertlm
$ADB -s "$DEV" shell am start -n com.filesearch.android/.MainActivity --ez bench true
echo "waiting for bench (~60s: ~10s init + copy)..."
sleep 60
$ADB -s "$DEV" shell "run-as com.filesearch.android cat files/bench-sm8635.json"
