#!/bin/bash
# Fiziksel cihazı çıkar, sadece emülatör açık olsun. Sonra: ./run-on-emulator.sh
set -e
cd "$(dirname "$0")"
APK=app/build/outputs/apk/debug/app-debug.apk
echo "Devices:"
adb devices -l
echo ""
echo "Installing to emulator..."
adb install -r "$APK"
echo "Launching Secondway Lock..."
adb shell am start -n com.recoverylock.dpc/.MainActivity
echo "Done."
