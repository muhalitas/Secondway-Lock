#!/usr/bin/env python3
"""
Generate a lightweight weekly market intelligence report.

Sources:
- Google Play metadata + newest user reviews for key competitors
- Reachability checks for Sensor Tower, AppMagic, and data.ai endpoints
"""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    from google_play_scraper import Sort, app as gp_app, reviews as gp_reviews
except Exception:  # pragma: no cover - handled in runtime checks
    Sort = None
    gp_app = None
    gp_reviews = None


COMPETITORS: list[tuple[str, str]] = [
    ("AppBlock", "cz.mobilesoft.appblock"),
    ("BlockSite", "co.blocksite"),
    ("StayFree", "com.burockgames.timeclocker"),
    ("Lock Me Out", "com.teqtic.lockmeout"),
    ("Forest", "cc.forestapp"),
    ("Brave", "com.brave.browser"),
    ("DuckDuckGo", "com.duckduckgo.mobile.android"),
]

SOURCE_URLS: list[tuple[str, str]] = [
    ("Sensor Tower Blog", "https://sensortower.com/blog"),
    ("Sensor Tower Q2 2025 Digital Market Index", "https://sensortower.com/blog/q2-2025-digital-market-index"),
    ("Sensor Tower State of Mobile 2026", "https://sensortower.com/report/state-of-mobile-2026"),
    ("AppMagic", "https://appmagic.rocks"),
    ("data.ai", "https://www.data.ai"),
]

SIGNALS: dict[str, list[str]] = {
    "paywall": ["paywall", "premium", "subscription", "subscribe", "too expensive", "paid"],
    "bugs": ["bug", "crash", "freeze", "not working", "broken", "doesn't work", "doesnt work"],
    "bypass": ["bypass", "uninstall", "disable", "force stop", "circumvent", "workaround"],
    "effective": ["works great", "helped", "effective", "focus", "productive", "clean"],
}


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def safe_pct(numer: int, denom: int) -> float:
    if denom <= 0:
        return 0.0
    return round((numer / denom) * 100.0, 1)


def fetch_url_status(url: str, timeout_s: int = 15) -> dict[str, Any]:
    req = Request(
        url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/122.0.0.0 Safari/537.36"
            )
        },
    )
    try:
        with urlopen(req, timeout=timeout_s) as resp:
            status = int(resp.getcode() or 0)
            final_url = resp.geturl()
            reachable = 200 <= status < 400
            login_gate = "login" in final_url.lower()
            return {
                "url": url,
                "status": status,
                "reachable": reachable,
                "data_accessible": reachable and not login_gate,
                "final_url": final_url,
                "error": "",
            }
    except HTTPError as e:
        return {
            "url": url,
            "status": int(e.code),
            "reachable": False,
            "data_accessible": False,
            "final_url": url,
            "error": f"http_error:{e.code}",
        }
    except URLError as e:
        return {
            "url": url,
            "status": 0,
            "reachable": False,
            "data_accessible": False,
            "final_url": url,
            "error": f"url_error:{e.reason}",
        }
    except Exception as e:  # pragma: no cover
        return {
            "url": url,
            "status": 0,
            "reachable": False,
            "data_accessible": False,
            "final_url": url,
            "error": f"error:{type(e).__name__}",
        }


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip().lower())


def collect_reviews(
    package_name: str,
    lang: str,
    country: str,
    target_count: int,
) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    continuation_token = None

    while len(items) < target_count:
        batch_count = min(200, target_count - len(items))
        batch, continuation_token = gp_reviews(  # type: ignore[misc]
            package_name,
            lang=lang,
            country=country,
            sort=Sort.NEWEST,  # type: ignore[union-attr]
            count=batch_count,
            continuation_token=continuation_token,
        )
        if not batch:
            break
        items.extend(batch)
        if continuation_token is None:
            break

    return items


def analyze_review_signals(review_items: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(review_items)
    counts = {k: 0 for k in SIGNALS}
    low_star = 0
    high_star = 0

    for row in review_items:
        text = normalize_text(str(row.get("content", "")))
        score = int(row.get("score", 0) or 0)
        if score <= 2:
            low_star += 1
        if score >= 4:
            high_star += 1
        for signal, keywords in SIGNALS.items():
            if any(keyword in text for keyword in keywords):
                counts[signal] += 1

    return {
        "total_reviews": total,
        "low_star_pct": safe_pct(low_star, total),
        "high_star_pct": safe_pct(high_star, total),
        "signal_pct": {k: safe_pct(v, total) for k, v in counts.items()},
    }


def collect_competitor_data(
    lang: str,
    country: str,
    reviews_per_app: int,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for name, package_name in COMPETITORS:
        row: dict[str, Any] = {
            "name": name,
            "package_name": package_name,
            "status": "ok",
        }
        try:
            meta = gp_app(package_name, lang=lang, country=country)  # type: ignore[misc]
            row["title"] = meta.get("title", name)
            row["installs"] = meta.get("installs", "n/a")
            row["score"] = meta.get("score")
            row["ratings"] = meta.get("ratings")
            row["reviews_total"] = meta.get("reviews")

            review_items = collect_reviews(
                package_name=package_name,
                lang=lang,
                country=country,
                target_count=reviews_per_app,
            )
            row["review_sample_size"] = len(review_items)
            row["review_signals"] = analyze_review_signals(review_items)
        except Exception as e:
            row["status"] = "error"
            row["error"] = f"{type(e).__name__}: {e}"
        rows.append(row)
    return rows


def emit_markdown(
    output_path: Path,
    generated_at: str,
    source_status: list[dict[str, Any]],
    competitor_rows: list[dict[str, Any]],
    country: str,
    lang: str,
    reviews_per_app: int,
) -> None:
    lines: list[str] = []
    lines.append("# Market Intelligence Report")
    lines.append("")
    lines.append(f"- Generated at (UTC): `{generated_at}`")
    lines.append(f"- Scope: Play Store country `{country.upper()}`, language `{lang}`, newest `{reviews_per_app}` reviews/app")
    lines.append("")
    lines.append("## Source Reachability")
    lines.append("")
    lines.append("| Source | HTTP | Data access | Notes |")
    lines.append("|---|---:|:---:|---|")
    for item in source_status:
        note = item["error"] if item["error"] else item["final_url"]
        lines.append(
            f"| {item['name']} | {item['status']} | {'yes' if item.get('data_accessible') else 'no'} | {note} |"
        )
    lines.append("")
    lines.append("## Competitor Snapshot")
    lines.append("")
    lines.append("| App | Package | Installs | Score | Ratings | Review sample |")
    lines.append("|---|---|---:|---:|---:|---:|")
    for row in competitor_rows:
        if row.get("status") != "ok":
            lines.append(
                f"| {row['name']} | `{row['package_name']}` | n/a | n/a | n/a | n/a |"
            )
            continue
        lines.append(
            f"| {row['name']} | `{row['package_name']}` | {row.get('installs', 'n/a')} | "
            f"{row.get('score', 'n/a')} | {row.get('ratings', 'n/a')} | {row.get('review_sample_size', 0)} |"
        )
    lines.append("")
    lines.append("## Newest Review Signals")
    lines.append("")
    lines.append("| App | Low-star % | High-star % | Paywall % | Bugs % | Bypass % | Effective % |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    for row in competitor_rows:
        sig = row.get("review_signals", {})
        pct = sig.get("signal_pct", {})
        if not sig:
            lines.append(f"| {row['name']} | n/a | n/a | n/a | n/a | n/a | n/a |")
            continue
        lines.append(
            f"| {row['name']} | {sig.get('low_star_pct', 0)} | {sig.get('high_star_pct', 0)} | "
            f"{pct.get('paywall', 0)} | {pct.get('bugs', 0)} | {pct.get('bypass', 0)} | {pct.get('effective', 0)} |"
        )
    lines.append("")
    lines.append("## Product Implications (UI/UX + Growth)")
    lines.append("")
    lines.append("- Onboarding value must be visible in the first session: lock state, default browser state, and next action.")
    lines.append("- Trust and reliability are conversion-critical: show clear permission status and real-time protection state.")
    lines.append("- Paywall friction is a known competitor complaint: keep free value obvious before asking for upgrades.")
    lines.append("- Bypass complaints indicate weak guard design: emphasize tamper-resistance UX and transparent lock timing.")
    lines.append("")

    output_path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate market intelligence report for mobile competitors.")
    parser.add_argument("--country", default="us", help="Play Store country code (default: us)")
    parser.add_argument("--lang", default="en", help="Play Store language code (default: en)")
    parser.add_argument(
        "--reviews-per-app",
        type=int,
        default=300,
        help="Newest reviews sampled per app (default: 300)",
    )
    parser.add_argument(
        "--out-dir",
        default="artifacts/market",
        help="Output directory (default: artifacts/market)",
    )
    args = parser.parse_args()

    generated_at = now_iso()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    source_status = []
    for name, url in SOURCE_URLS:
        row = fetch_url_status(url)
        row["name"] = name
        source_status.append(row)

    competitor_rows: list[dict[str, Any]] = []
    play_error = ""
    if gp_app is None or gp_reviews is None or Sort is None:
        play_error = "google_play_scraper not installed"
    else:
        competitor_rows = collect_competitor_data(
            lang=args.lang,
            country=args.country,
            reviews_per_app=args.reviews_per_app,
        )

    report_md = out_dir / "market_scan_latest.md"
    report_json = out_dir / "market_scan_latest.json"

    if not competitor_rows and play_error:
        fallback = [
            "# Market Intelligence Report",
            "",
            f"- Generated at (UTC): `{generated_at}`",
            f"- Error: `{play_error}`",
            "",
            "Install dependency and rerun:",
            "",
            "```bash",
            "python3 -m pip install google-play-scraper",
            "python3 scripts/market_scan.py",
            "```",
            "",
        ]
        report_md.write_text("\n".join(fallback), encoding="utf-8")
    else:
        emit_markdown(
            output_path=report_md,
            generated_at=generated_at,
            source_status=source_status,
            competitor_rows=competitor_rows,
            country=args.country,
            lang=args.lang,
            reviews_per_app=args.reviews_per_app,
        )

    payload = {
        "generated_at": generated_at,
        "country": args.country,
        "lang": args.lang,
        "reviews_per_app": args.reviews_per_app,
        "sources": source_status,
        "play_error": play_error,
        "competitors": competitor_rows,
    }
    report_json.write_text(json.dumps(payload, ensure_ascii=True, indent=2), encoding="utf-8")

    print(f"Wrote: {report_md}")
    print(f"Wrote: {report_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
