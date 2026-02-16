#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Generate emulator-free UI screenshots via Paparazzi and export them to stable filenames.
./gradlew :app:testDebugUnitTest
python3 scripts/export_paparazzi.py

echo
echo "Paparazzi report:"
echo "  app/build/reports/paparazzi/debug/index.html"
echo
echo "Exported screenshots:"
echo "  artifacts/screenshots/paparazzi/"

