#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.recoverylock.dpc"
ACTIVITY=".MainActivity"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found"
  exit 1
fi

echo "Building (JAVA_HOME=$JAVA_HOME)..."
JAVA_HOME="$JAVA_HOME" ./gradlew assembleDebug

echo ""
echo "Devices:"
adb devices -l

echo ""
echo "Installing to USB device..."
adb -d install -r "$APK"

echo "Launching..."
adb -d shell am start -n "$PKG/$ACTIVITY" >/dev/null

echo "Done."

