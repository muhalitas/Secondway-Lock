#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

VENV_DIR=".codex-market-venv"

if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  python3 -m venv "$VENV_DIR"
fi

"$VENV_DIR/bin/pip" -q install --upgrade pip
"$VENV_DIR/bin/pip" -q install google-play-scraper

"$VENV_DIR/bin/python" scripts/market_scan.py "$@"

echo
echo "Market report:"
echo "  artifacts/market/market_scan_latest.md"
