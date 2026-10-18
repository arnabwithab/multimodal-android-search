#!/usr/bin/env bash
# Capability baseline v1 — no model file exists yet, so this records what the
# phone CAN do (SoC/SDK/NNAPI/CPU/RAM/thermal), not inference latency.
# shortcut: no .litertlm on device yet; latency numbers come with model work.
set -euo pipefail
ADB="${ADB:-adb}"
DEVICE="${DEVICE:-}"

if [ -n "$DEVICE" ]; then ADB="$ADB -s $DEVICE"; else
  # Pick the first online device automatically.
  DEV=$($ADB devices | awk '$2=="device"{print $1; exit}')
  [ -n "${DEV:-}" ] || { echo "bench-device: no device attached (adb devices empty)"; exit 1; }
  ADB="$ADB -s $DEV"
fi

echo "== device fingerprint =="
MODEL=$($ADB shell getprop ro.product.model | tr -d '\r')
SOC=$($ADB shell getprop ro.soc.model | tr -d '\r')
SDK=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
ABI=$($ADB shell getprop ro.product.cpu.abi | tr -d '\r')
BOARD=$($ADB shell getprop ro.board.platform | tr -d '\r')
echo "model=$MODEL soc=$SOC board=$BOARD sdk=$SDK abi=$ABI"

echo "== RAM =="
$ADB shell cat /proc/meminfo | head -3 | tr -d '\r'
FREE_KB=$($ADB shell "awk '/MemAvailable/ {print \$2}' /proc/meminfo" | tr -d '\r')
FREE_GB=$(awk "BEGIN{printf \"%.1f\", $FREE_KB/1024/1024}")
echo "free_ram_gb=$FREE_GB"
if [ "$FREE_KB" -lt 2000000 ]; then
  echo "WARNING: <2GB free RAM — any latency numbers from this phone right now would lie."
fi

echo "== NNAPI / delegate libs present =="
$ADB shell ls /system/lib64/libnnapi.so /vendor/lib64/libnnapi.so 2>&1 | tr -d '\r' || true
$ADB shell "ls /vendor/lib64/*[Nn]eural* /system/lib64/*[Nn]eural* 2>/dev/null || echo '(no vendor neural libs visible)'" | tr -d '\r'

echo "== thermal zones =="
$ADB shell "for z in /sys/class/thermal/thermal_zone*/type; do echo \"\$z: \$(cat \$z)\"; done" | tr -d '\r' | head -20

echo "== CPU =="
$ADB shell cat /proc/cpuinfo | grep -m4 -i 'processor\|Hardware\|model name' | tr -d '\r' || true

echo "baseline ok"
