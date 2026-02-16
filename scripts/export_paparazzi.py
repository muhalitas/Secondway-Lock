#!/usr/bin/env python3
"""
Export Paparazzi report images to stable, human-friendly filenames.

Paparazzi's HTML report stores images under hashed names. This script reads the
`runs/*.js` metadata and copies the latest snapshot for each `name` to e.g.
`artifacts/screenshots/paparazzi/{name}.png`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


RUN_RE = re.compile(r'window\.runs\["(?P<run_id>[^"]+)"\]\s*=\s*(?P<payload>\[.*\])\s*;?\s*\Z', re.S)


@dataclass(frozen=True)
class Snapshot:
    name: str
    test_name: str
    timestamp: datetime
    rel_file: str


def parse_ts(ts: str) -> datetime:
    # Example: "2026-02-16T02:00:04.835Z"
    if ts.endswith("Z"):
        ts = ts[:-1] + "+00:00"
    return datetime.fromisoformat(ts).astimezone(timezone.utc)


def parse_run_js(path: Path) -> list[Snapshot]:
    text = path.read_text(encoding="utf-8")
    m = RUN_RE.search(text.strip())
    if not m:
        raise ValueError(f"Unrecognized run format: {path}")
    payload = m.group("payload")
    data = json.loads(payload)
    out: list[Snapshot] = []
    for item in data:
        out.append(
            Snapshot(
                name=item["name"],
                test_name=item.get("testName", ""),
                timestamp=parse_ts(item["timestamp"]),
                rel_file=item["file"],
            )
        )
    return out


def ensure_dir(p: Path) -> None:
    p.mkdir(parents=True, exist_ok=True)


def safe_filename(name: str) -> str:
    # Keep stable and filesystem-friendly.
    s = name.strip().replace(" ", "_")
    s = re.sub(r"[^A-Za-z0-9._-]+", "_", s)
    return s


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--report-dir",
        default="app/build/reports/paparazzi/debug",
        help="Paparazzi report directory (default: %(default)s)",
    )
    ap.add_argument(
        "--out",
        default="artifacts/screenshots/paparazzi",
        help="Output directory (default: %(default)s)",
    )
    args = ap.parse_args()

    report_dir = Path(args.report_dir).resolve()
    out_dir = Path(args.out).resolve()

    runs_dir = report_dir / "runs"
    if not runs_dir.exists():
        raise SystemExit(f"Missing runs dir: {runs_dir}")

    best: dict[str, Snapshot] = {}
    for run_js in sorted(runs_dir.glob("*.js")):
        try:
            snaps = parse_run_js(run_js)
        except Exception as e:
            raise SystemExit(f"Failed parsing {run_js}: {e}") from e
        for s in snaps:
            prev = best.get(s.name)
            if prev is None or s.timestamp >= prev.timestamp:
                best[s.name] = s

    ensure_dir(out_dir)

    exported: list[tuple[str, Path]] = []
    for name, snap in sorted(best.items(), key=lambda kv: kv[0]):
        src = report_dir / snap.rel_file
        if not src.exists():
            # Be permissive: skip missing files so CI still uploads partial output.
            continue
        dst = out_dir / f"{safe_filename(name)}.png"
        shutil.copyfile(src, dst)
        exported.append((name, dst))

    # Small index for quick browsing in CI artifacts.
    index_md = out_dir / "INDEX.md"
    with index_md.open("w", encoding="utf-8") as f:
        f.write("# Paparazzi Screenshots\n\n")
        f.write(f"Source report: `{report_dir}`\n\n")
        for name, dst in exported:
            f.write(f"- `{name}` -> `{dst.name}`\n")

    print(f"Exported {len(exported)} screenshot(s) to {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

