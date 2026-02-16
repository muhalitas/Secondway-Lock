# SecondwayLock Agent Workflow

This repo is optimized for a no-prompt, automated UI iteration loop.

## Non-Negotiables (Dev Cycle)

- After any UI/UX change, generate fresh screenshots (no device required):
  - Run: `./scripts/screenshots.sh`
  - Review: `artifacts/screenshots/paparazzi/*.png`
- Treat Paparazzi as a fast "layout + theme" signal, not a full runtime truth:
  - WebView and some runtime-driven content will never be 1:1.
- Always keep a high-fidelity reference stream via emulator screenshots:
  - GitHub Actions workflow: `Android Screenshots`
  - Triggered on pushes to `main` that touch UI code/resources, and nightly.
  - Review artifacts named `screenshots` in the workflow run.
- Run a recurring market intelligence scan before major UX/growth changes:
  - Run: `./scripts/market_scan.sh`
  - Review: `artifacts/market/market_scan_latest.md`
  - Treat source availability explicitly: SensorTower is primary if reachable, and
    AppMagic/data.ai must be reported as `reachable` or `blocked`.

## What "Good" Means

- Colors, typography, spacing, and selection states must match emulator screenshots.
- Paparazzi screenshots must be representative (no blank placeholders that mislead).
- Any mismatch found in screenshots should be followed by immediate UI fixes until
  screenshots converge (within Paparazzi's limits).

## Screenshot Sources

- Fast (JVM): Paparazzi
  - Command: `./scripts/screenshots.sh`
  - Report: `app/build/reports/paparazzi/debug/index.html`
  - Exported PNGs: `artifacts/screenshots/paparazzi/`
- High fidelity (runtime): Emulator
  - GitHub Actions: `.github/workflows/android-screenshots.yml`

## Market Sources

- Automated report:
  - Command: `./scripts/market_scan.sh`
  - Output: `artifacts/market/market_scan_latest.md`
- CI automation:
  - GitHub Actions: `.github/workflows/market-intelligence.yml` (weekly + manual)
