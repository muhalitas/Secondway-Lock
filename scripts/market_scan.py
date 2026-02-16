#!/usr/bin/env python3
"""
Generate a richer market intelligence report for addiction-blocking products.

Outputs:
- artifacts/market/market_scan_latest.md
- artifacts/market/market_scan_latest.json
- artifacts/market/ui_ux_tasks_latest.md

Data sources:
- Google Play app discovery + metadata + newest reviews
- Reachability checks for Sensor Tower / AppMagic / data.ai
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    from google_play_scraper import Sort, app as gp_app, reviews as gp_reviews, search as gp_search
except Exception:  # pragma: no cover - handled by runtime checks
    Sort = None
    gp_app = None
    gp_reviews = None
    gp_search = None


SOURCE_URLS: list[tuple[str, str]] = [
    ("Sensor Tower Blog", "https://sensortower.com/blog"),
    (
        "Sensor Tower Q2 2025 Digital Market Index",
        "https://sensortower.com/blog/q2-2025-digital-market-index",
    ),
    ("Sensor Tower State of Mobile 2026", "https://sensortower.com/report/state-of-mobile-2026"),
    ("AppMagic", "https://appmagic.rocks"),
    ("data.ai", "https://www.data.ai"),
]

SEARCH_SEGMENTS: dict[str, list[str]] = {
    "porn_blocker": [
        "porn blocker",
        "adult blocker",
        "nsfw blocker",
        "website blocker porn",
    ],
    "gambling_blocker": [
        "gambling blocker",
        "bet blocker",
        "casino blocker",
        "sports betting blocker",
    ],
    "focus_blocker": [
        "app blocker",
        "website blocker",
        "stay focused app",
        "digital addiction blocker",
    ],
    "safe_browser": [
        "safe browser",
        "family safe browser",
        "privacy browser",
    ],
}

# Seed set keeps known competitors in scope even if query ranking changes.
SEED_PACKAGE_IDS: list[str] = [
    "cz.mobilesoft.appblock",
    "co.blocksite",
    "com.burockgames.timeclocker",
    "com.teqtic.lockmeout",
    "cc.forestapp",
    "com.stayfocused",
    "com.wverlaek.block",
    "com.blockerhero",
    "io.funswitch.blocker",
    "com.blockerplus.blockerplus",
    "com.familyfirsttechnology.pornblocker",
    "com.gamban.beanstalkhps.gambanapp",
    "mobile.betblocker",
    "gambling.site.blocker.goodbye.gambling",
    "com.moxyzstudio.gamblingblock",
    "com.brave.browser",
    "com.duckduckgo.mobile.android",
]

CATEGORY_PRIORITY = ["porn_blocker", "gambling_blocker", "focus_blocker", "safe_browser", "other"]

CATEGORY_KEYWORDS: dict[str, list[str]] = {
    "porn_blocker": ["porn", "adult", "nsfw", "xxx", "explicit"],
    "gambling_blocker": ["gambling", "casino", "bet", "sportsbook", "slots"],
    "focus_blocker": ["focus", "block", "productivity", "distraction", "habit", "self control"],
    "safe_browser": ["browser", "privacy", "safe search", "family"],
}

STRICT_PROTECTION_INTENT_KEYWORDS = [
    "block",
    "blocker",
    "self exclusion",
    "exclude",
    "recovery",
    "quit",
    "detox",
    "prevent",
    "restriction",
]

GENERAL_INTENT_KEYWORDS = STRICT_PROTECTION_INTENT_KEYWORDS + [
    "limit",
    "focus",
]

GAMBLING_OPERATOR_KEYWORDS = [
    "sportsbook",
    "casino game",
    "slots",
    "slot game",
    "poker",
    "fantasy sports",
    "sports betting",
]

SIGNALS: dict[str, list[str]] = {
    "paywall": ["paywall", "premium", "subscription", "subscribe", "paid", "too expensive"],
    "bugs": ["bug", "crash", "freeze", "broken", "not working", "doesn't work", "doesnt work"],
    "bypass": ["bypass", "force stop", "uninstall", "disable", "workaround", "circumvent"],
    "effective": ["works great", "helped", "effective", "saved", "life changing", "focus"],
    "ui_praise": ["easy to use", "simple", "clean", "intuitive", "user friendly"],
    "ui_confusion": ["confusing", "hard to use", "complicated", "difficult", "bad ui"],
    "porn_need": ["porn", "adult", "nsfw"],
    "gambling_need": ["gambling", "casino", "bet", "slots"],
}


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def safe_pct(numer: int, denom: int) -> float:
    if denom <= 0:
        return 0.0
    return round((numer / denom) * 100.0, 1)


def parse_installs_count(installs: str | None) -> int:
    if not installs:
        return 0
    digits = re.sub(r"[^0-9]", "", installs)
    if not digits:
        return 0
    try:
        return int(digits)
    except Exception:
        return 0


def normalize_text(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip().lower())


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


def discover_candidates(
    lang: str,
    country: str,
    hits_per_query: int,
) -> dict[str, dict[str, Any]]:
    candidates: dict[str, dict[str, Any]] = {}

    for app_id in SEED_PACKAGE_IDS:
        candidates[app_id] = {
            "package_name": app_id,
            "matched_segments": set(),
            "matched_queries": set(),
            "seed": True,
            "search_installs": 0,
        }

    for segment, queries in SEARCH_SEGMENTS.items():
        for query in queries:
            rows = gp_search(query, lang=lang, country=country, n_hits=hits_per_query)  # type: ignore[misc]
            for item in rows:
                app_id = item.get("appId")
                if not app_id:
                    continue
                current = candidates.setdefault(
                    app_id,
                    {
                        "package_name": app_id,
                        "matched_segments": set(),
                        "matched_queries": set(),
                        "seed": False,
                        "search_installs": 0,
                    },
                )
                current["matched_segments"].add(segment)
                current["matched_queries"].add(query)
                current["title_hint"] = item.get("title", "")
                current["score_hint"] = item.get("score")
                installs = parse_installs_count(item.get("installs"))
                if installs > current.get("search_installs", 0):
                    current["search_installs"] = installs

    return candidates


def infer_primary_category(meta: dict[str, Any], matched_segments: set[str]) -> str:
    title_text = normalize_text(str(meta.get("title", "")))
    text = normalize_text(
        " ".join(
            [
                str(meta.get("title", "")),
                str(meta.get("summary", "")),
                str(meta.get("description", ""))[:2000],
            ]
        )
    )

    if any(k in title_text for k in CATEGORY_KEYWORDS["porn_blocker"]) and has_strict_protection_intent(text):
        return "porn_blocker"

    if any(k in title_text for k in CATEGORY_KEYWORDS["gambling_blocker"]) and has_strict_protection_intent(text):
        return "gambling_blocker"

    if any(k in text for k in CATEGORY_KEYWORDS["safe_browser"]):
        return "safe_browser"

    if any(k in text for k in CATEGORY_KEYWORDS["focus_blocker"]) and has_general_protection_intent(text):
        return "focus_blocker"

    if "safe_browser" in matched_segments and any(k in text for k in CATEGORY_KEYWORDS["safe_browser"]):
        return "safe_browser"

    if "focus_blocker" in matched_segments and has_general_protection_intent(text):
        return "focus_blocker"

    return "other"


def relevance_score(meta: dict[str, Any], matched_segments: set[str]) -> int:
    text = normalize_text(
        " ".join(
            [
                str(meta.get("title", "")),
                str(meta.get("summary", "")),
                str(meta.get("description", ""))[:1200],
            ]
        )
    )
    score = 0
    if matched_segments:
        score += 3
    score += sum(1 for cat in CATEGORY_KEYWORDS.values() if any(k in text for k in cat))
    if "block" in text:
        score += 2
    if "porn" in text or "gambling" in text:
        score += 3
    return score


def row_text(row: dict[str, Any]) -> str:
    return normalize_text(
        " ".join(
            [
                str(row.get("title", "")),
                str(row.get("summary", "")),
                str(row.get("description", ""))[:1600],
            ]
        )
    )


def has_strict_protection_intent(text: str) -> bool:
    return any(k in text for k in STRICT_PROTECTION_INTENT_KEYWORDS)


def has_general_protection_intent(text: str) -> bool:
    return any(k in text for k in GENERAL_INTENT_KEYWORDS)


def is_relevant_row(row: dict[str, Any]) -> bool:
    cat = str(row.get("category", "other"))
    title_text = normalize_text(str(row.get("title", "")))
    text = row_text(row)

    if cat in {"porn_blocker", "gambling_blocker"} and not has_strict_protection_intent(text):
        return False

    if cat == "porn_blocker" and not any(k in title_text for k in CATEGORY_KEYWORDS["porn_blocker"]):
        return False

    if cat == "gambling_blocker":
        if not any(k in title_text for k in CATEGORY_KEYWORDS["gambling_blocker"]):
            return False
        has_operator = any(k in text for k in GAMBLING_OPERATOR_KEYWORDS)
        if has_operator and not has_strict_protection_intent(text):
            return False

    if "vpn" in text and cat in {"porn_blocker", "gambling_blocker"} and "block" not in text:
        return False

    return True


def fetch_metadata_for_candidates(
    candidates: dict[str, dict[str, Any]],
    lang: str,
    country: str,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []

    for app_id, cand in candidates.items():
        row: dict[str, Any] = {
            "package_name": app_id,
            "status": "ok",
            "seed": bool(cand.get("seed")),
            "matched_segments": sorted(list(cand.get("matched_segments", set()))),
            "matched_queries": sorted(list(cand.get("matched_queries", set()))),
        }
        try:
            meta = gp_app(app_id, lang=lang, country=country)  # type: ignore[misc]
            row["title"] = meta.get("title", app_id)
            row["developer"] = meta.get("developer", "")
            row["installs"] = meta.get("installs", "0")
            row["installs_count"] = parse_installs_count(meta.get("installs"))
            row["score"] = meta.get("score")
            row["ratings"] = meta.get("ratings")
            row["reviews_total"] = meta.get("reviews")
            row["genre"] = meta.get("genre", "")
            row["summary"] = meta.get("summary", "")
            row["description"] = meta.get("description", "")
            matched_segments = set(row["matched_segments"])
            row["category"] = infer_primary_category(meta, matched_segments)
            row["relevance"] = relevance_score(meta, matched_segments)
        except Exception as e:
            row["status"] = "error"
            row["error"] = f"{type(e).__name__}: {e}"
        rows.append(row)

    return rows


def select_apps(
    rows: list[dict[str, Any]],
    max_apps: int,
    min_installs: int,
) -> list[dict[str, Any]]:
    ok = [
        r
        for r in rows
        if r.get("status") == "ok"
        and int(r.get("installs_count") or 0) >= min_installs
        and int(r.get("relevance") or 0) >= 2
        and is_relevant_row(r)
        and r.get("category") in {"porn_blocker", "gambling_blocker", "focus_blocker", "safe_browser"}
    ]

    ok.sort(
        key=lambda r: (
            int(r.get("installs_count") or 0),
            float(r.get("score") or 0.0),
            int(r.get("ratings") or 0),
        ),
        reverse=True,
    )

    target_by_category = {
        "porn_blocker": 5,
        "gambling_blocker": 5,
        "focus_blocker": 6,
        "safe_browser": 3,
    }

    selected: list[dict[str, Any]] = []
    used: set[str] = set()
    selected_by_cat: dict[str, int] = defaultdict(int)

    for cat, needed in target_by_category.items():
        picks = [r for r in ok if r.get("category") == cat and r.get("package_name") not in used][:needed]
        for p in picks:
            used.add(str(p.get("package_name")))
            selected.append(p)
            selected_by_cat[cat] += 1

    for r in ok:
        pkg = str(r.get("package_name"))
        if pkg in used:
            continue
        cat = str(r.get("category"))
        cap = target_by_category.get(cat, 3)
        if selected_by_cat.get(cat, 0) >= cap:
            continue
        selected.append(r)
        used.add(pkg)
        selected_by_cat[cat] = selected_by_cat.get(cat, 0) + 1
        if len(selected) >= max_apps:
            break

    return selected[:max_apps]


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


def enrich_with_review_data(
    rows: list[dict[str, Any]],
    lang: str,
    country: str,
    reviews_per_app: int,
) -> None:
    for row in rows:
        pkg = str(row.get("package_name"))
        try:
            review_items = collect_reviews(
                package_name=pkg,
                lang=lang,
                country=country,
                target_count=reviews_per_app,
            )
            row["review_sample_size"] = len(review_items)
            row["review_signals"] = analyze_review_signals(review_items)
        except Exception as e:
            row["review_sample_size"] = 0
            row["review_signals"] = {}
            row["review_error"] = f"{type(e).__name__}: {e}"


def category_rollup(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    bucket: dict[str, dict[str, Any]] = {}
    for row in rows:
        cat = str(row.get("category", "other"))
        sig = row.get("review_signals", {})
        pct = sig.get("signal_pct", {}) if isinstance(sig, dict) else {}
        sample = int(row.get("review_sample_size") or 0)
        if sample <= 0:
            continue
        b = bucket.setdefault(
            cat,
            {
                "category": cat,
                "apps": 0,
                "reviews": 0,
                "sum_low": 0.0,
                "sum_high": 0.0,
                "sum_paywall": 0.0,
                "sum_bugs": 0.0,
                "sum_bypass": 0.0,
                "sum_effective": 0.0,
                "sum_ui_praise": 0.0,
                "sum_ui_confusion": 0.0,
            },
        )
        b["apps"] += 1
        b["reviews"] += sample
        b["sum_low"] += float(sig.get("low_star_pct", 0.0)) * sample
        b["sum_high"] += float(sig.get("high_star_pct", 0.0)) * sample
        b["sum_paywall"] += float(pct.get("paywall", 0.0)) * sample
        b["sum_bugs"] += float(pct.get("bugs", 0.0)) * sample
        b["sum_bypass"] += float(pct.get("bypass", 0.0)) * sample
        b["sum_effective"] += float(pct.get("effective", 0.0)) * sample
        b["sum_ui_praise"] += float(pct.get("ui_praise", 0.0)) * sample
        b["sum_ui_confusion"] += float(pct.get("ui_confusion", 0.0)) * sample

    out: list[dict[str, Any]] = []
    for cat, b in bucket.items():
        reviews = int(b["reviews"])
        if reviews <= 0:
            continue
        out.append(
            {
                "category": cat,
                "apps": int(b["apps"]),
                "reviews": reviews,
                "low_star_pct": round(b["sum_low"] / reviews, 1),
                "high_star_pct": round(b["sum_high"] / reviews, 1),
                "paywall_pct": round(b["sum_paywall"] / reviews, 1),
                "bugs_pct": round(b["sum_bugs"] / reviews, 1),
                "bypass_pct": round(b["sum_bypass"] / reviews, 1),
                "effective_pct": round(b["sum_effective"] / reviews, 1),
                "ui_praise_pct": round(b["sum_ui_praise"] / reviews, 1),
                "ui_confusion_pct": round(b["sum_ui_confusion"] / reviews, 1),
            }
        )

    out.sort(key=lambda x: x["reviews"], reverse=True)
    return out


def app_takeaways(rows: list[dict[str, Any]], limit: int = 12) -> list[str]:
    ranked = sorted(
        [r for r in rows if r.get("status") == "ok"],
        key=lambda r: (int(r.get("installs_count") or 0), float(r.get("score") or 0.0)),
        reverse=True,
    )[:limit]

    out: list[str] = []
    for row in ranked:
        sig = row.get("review_signals", {})
        pct = sig.get("signal_pct", {}) if isinstance(sig, dict) else {}
        notes: list[str] = []
        if float(pct.get("paywall", 0.0)) >= 10.0:
            notes.append("high paywall friction")
        if float(pct.get("bugs", 0.0)) >= 8.0:
            notes.append("stability complaints")
        if float(pct.get("bypass", 0.0)) >= 7.0:
            notes.append("bypass risk mentions")
        if float(pct.get("effective", 0.0)) >= 14.0:
            notes.append("strong effectiveness feedback")
        if float(pct.get("ui_confusion", 0.0)) >= 5.0:
            notes.append("UX complexity complaints")

        if not notes:
            notes.append("balanced signal mix")

        out.append(
            "- "
            + f"{row.get('title')} ({row.get('installs')}, score {round(float(row.get('score') or 0.0), 2)}): "
            + ", ".join(notes)
        )

    return out


def build_priority_tasks(rows: list[dict[str, Any]], rollups: list[dict[str, Any]]) -> list[dict[str, str]]:
    # Aggregate blocker segments for thresholds.
    blocker_rows = [r for r in rollups if r.get("category") in {"porn_blocker", "gambling_blocker", "focus_blocker"}]
    if blocker_rows:
        total_reviews = sum(int(r.get("reviews", 0)) for r in blocker_rows)
        def wavg(field: str) -> float:
            if total_reviews <= 0:
                return 0.0
            return round(sum(float(r.get(field, 0.0)) * int(r.get("reviews", 0)) for r in blocker_rows) / total_reviews, 1)

        paywall = wavg("paywall_pct")
        bugs = wavg("bugs_pct")
        bypass = wavg("bypass_pct")
        ui_confusion = wavg("ui_confusion_pct")
    else:
        paywall = bugs = bypass = ui_confusion = 0.0

    tasks: list[dict[str, str]] = []

    tasks.append(
        {
            "priority": "P0",
            "title": "Protection Health strip on main surfaces",
            "why": "Users need immediate trust signals (protection, permissions, browser shield) to believe blocking is active.",
            "metric": "Increase day-1 activation and reduce support questions about whether protection is ON.",
        }
    )

    if bypass >= 6.0:
        tasks.append(
            {
                "priority": "P0",
                "title": "Bypass friction UX hardening",
                "why": f"Bypass complaints are elevated ({bypass}%).",
                "metric": "Reduce bypass-related low-star reviews by 30%.",
            }
        )

    if bugs >= 6.0:
        tasks.append(
            {
                "priority": "P0",
                "title": "Reliability-first UX states",
                "why": f"Bug complaints are meaningful ({bugs}%).",
                "metric": "Increase 4-5 star review ratio and reduce crash/bug mentions.",
            }
        )

    if paywall >= 8.0:
        tasks.append(
            {
                "priority": "P1",
                "title": "Free-core value before premium gates",
                "why": f"Paywall friction appears in reviews ({paywall}%).",
                "metric": "Improve conversion from install to week-1 retention before monetization prompt.",
            }
        )

    if ui_confusion >= 4.0:
        tasks.append(
            {
                "priority": "P1",
                "title": "Simplify high-frequency flows",
                "why": f"UX confusion signal is visible ({ui_confusion}%).",
                "metric": "Shorten time-to-first-protection and reduce task abandonment.",
            }
        )

    tasks.extend(
        [
            {
                "priority": "P1",
                "title": "Mission-first onboarding for porn + gambling recovery",
                "why": "Positioning should match the target struggle from first launch.",
                "metric": "Higher onboarding completion and stronger day-3 retention.",
            },
            {
                "priority": "P2",
                "title": "Premium packaging clarity",
                "why": "Users should clearly understand what stays free vs what is advanced.",
                "metric": "Lower subscription backlash and better upgrade intent.",
            },
        ]
    )

    return tasks


def emit_uiux_tasks(
    output_path: Path,
    generated_at: str,
    tasks: list[dict[str, str]],
) -> None:
    lines: list[str] = []
    lines.append("# UI/UX Task Backlog")
    lines.append("")
    lines.append(f"- Generated at (UTC): `{generated_at}`")
    lines.append("")
    lines.append("## Priority List")
    lines.append("")
    for t in tasks:
        lines.append(f"- [{t['priority']}] {t['title']}")
        lines.append(f"  Why: {t['why']}")
        lines.append(f"  Success metric: {t['metric']}")
    lines.append("")
    lines.append("## Feature Packaging Draft")
    lines.append("")
    lines.append("### Free core")
    lines.append("- Porn + gambling risk reduction defaults (safe search, minimal browser, lock guard).")
    lines.append("- App launch blocking with clear lock duration and unblock countdown.")
    lines.append("- Waitlist-based allowlist flow to slow impulsive relapses.")
    lines.append("- Core safety dashboard showing protection status and required permissions.")
    lines.append("")
    lines.append("### Premium (candidate)")
    lines.append("- Advanced schedules/profiles (night mode, travel mode, trigger windows).")
    lines.append("- Accountability features (trusted contact reports, relapse checkpoints).")
    lines.append("- Enhanced anti-bypass modes (PIN delay, stricter lock transitions).")
    lines.append("- Deep analytics (urge windows, relapse trend, intervention effectiveness).")
    lines.append("")

    output_path.write_text("\n".join(lines), encoding="utf-8")


def emit_markdown(
    output_path: Path,
    generated_at: str,
    source_status: list[dict[str, Any]],
    selected_rows: list[dict[str, Any]],
    rollups: list[dict[str, Any]],
    app_notes: list[str],
    country: str,
    lang: str,
    reviews_per_app: int,
    hits_per_query: int,
    max_apps: int,
) -> None:
    lines: list[str] = []
    lines.append("# Market Intelligence Report")
    lines.append("")
    lines.append(f"- Generated at (UTC): `{generated_at}`")
    lines.append(f"- Scope: Play Store `{country.upper()}` / `{lang}`")
    lines.append(f"- Discovery depth: `{hits_per_query}` hits/query, up to `{max_apps}` apps")
    lines.append(f"- Review sample: newest `{reviews_per_app}` reviews/app")
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

    lines.append("## Selected App Universe")
    lines.append("")
    lines.append("| Category | App | Package | Installs | Score | Ratings | Reviews sampled |")
    lines.append("|---|---|---|---:|---:|---:|---:|")
    for row in sorted(
        selected_rows,
        key=lambda r: (
            CATEGORY_PRIORITY.index(r.get("category", "other")) if r.get("category", "other") in CATEGORY_PRIORITY else 99,
            int(r.get("installs_count") or 0),
        ),
        reverse=False,
    ):
        lines.append(
            f"| {row.get('category')} | {row.get('title')} | `{row.get('package_name')}` | {row.get('installs')} | "
            f"{row.get('score', 'n/a')} | {row.get('ratings', 'n/a')} | {row.get('review_sample_size', 0)} |"
        )
    lines.append("")

    lines.append("## Category Signal Rollup")
    lines.append("")
    lines.append("| Category | Apps | Reviews | Low-star % | High-star % | Paywall % | Bugs % | Bypass % | Effective % | UI confusion % |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for r in rollups:
        lines.append(
            f"| {r['category']} | {r['apps']} | {r['reviews']} | {r['low_star_pct']} | {r['high_star_pct']} | "
            f"{r['paywall_pct']} | {r['bugs_pct']} | {r['bypass_pct']} | {r['effective_pct']} | {r['ui_confusion_pct']} |"
        )
    lines.append("")

    lines.append("## App-Level Takeaways")
    lines.append("")
    lines.extend(app_notes)
    lines.append("")

    lines.append("## Strategic Implications")
    lines.append("")
    lines.append("- Mission clarity should be explicit: the product serves porn + digital gambling recovery first.")
    lines.append("- The first session must prove protection is actually active (permission + lock state visibility).")
    lines.append("- Competitor backlash often clusters around paywalls and bypass leaks; free core must stay strong.")
    lines.append("- Visual polish should prioritize readability under dark mode fatigue (contrast, hierarchy, spacing).")
    lines.append("")

    output_path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate market intelligence report for addiction-blocking apps.")
    parser.add_argument("--country", default="us", help="Play Store country code (default: us)")
    parser.add_argument("--lang", default="en", help="Play Store language code (default: en)")
    parser.add_argument("--reviews-per-app", type=int, default=120, help="Newest reviews sampled per app")
    parser.add_argument("--hits-per-query", type=int, default=12, help="Number of discovery hits per query")
    parser.add_argument("--apps", type=int, default=22, help="Max apps selected for deep analysis")
    parser.add_argument("--min-installs", type=int, default=10000, help="Minimum installs count filter")
    parser.add_argument("--out-dir", default="artifacts/market", help="Output directory")
    args = parser.parse_args()

    generated_at = now_iso()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    source_status: list[dict[str, Any]] = []
    for name, url in SOURCE_URLS:
        row = fetch_url_status(url)
        row["name"] = name
        source_status.append(row)

    report_md = out_dir / "market_scan_latest.md"
    report_json = out_dir / "market_scan_latest.json"
    tasks_md = out_dir / "ui_ux_tasks_latest.md"

    play_error = ""
    selected_rows: list[dict[str, Any]] = []
    rollups: list[dict[str, Any]] = []
    notes: list[str] = []
    tasks: list[dict[str, str]] = []

    if gp_app is None or gp_reviews is None or gp_search is None or Sort is None:
        play_error = "google_play_scraper not installed"
    else:
        candidates = discover_candidates(
            lang=args.lang,
            country=args.country,
            hits_per_query=args.hits_per_query,
        )
        meta_rows = fetch_metadata_for_candidates(candidates, lang=args.lang, country=args.country)
        selected_rows = select_apps(meta_rows, max_apps=args.apps, min_installs=args.min_installs)
        enrich_with_review_data(selected_rows, lang=args.lang, country=args.country, reviews_per_app=args.reviews_per_app)
        rollups = category_rollup(selected_rows)
        notes = app_takeaways(selected_rows, limit=14)
        tasks = build_priority_tasks(selected_rows, rollups)

    if not selected_rows and play_error:
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
        tasks_md.write_text("# UI/UX Task Backlog\n\nNo data (market scan failed).\n", encoding="utf-8")
    else:
        emit_markdown(
            output_path=report_md,
            generated_at=generated_at,
            source_status=source_status,
            selected_rows=selected_rows,
            rollups=rollups,
            app_notes=notes,
            country=args.country,
            lang=args.lang,
            reviews_per_app=args.reviews_per_app,
            hits_per_query=args.hits_per_query,
            max_apps=args.apps,
        )
        emit_uiux_tasks(tasks_md, generated_at=generated_at, tasks=tasks)

    payload = {
        "generated_at": generated_at,
        "country": args.country,
        "lang": args.lang,
        "reviews_per_app": args.reviews_per_app,
        "hits_per_query": args.hits_per_query,
        "max_apps": args.apps,
        "sources": source_status,
        "play_error": play_error,
        "selected_apps": selected_rows,
        "category_rollups": rollups,
        "uiux_tasks": tasks,
    }
    report_json.write_text(json.dumps(payload, ensure_ascii=True, indent=2), encoding="utf-8")

    print(f"Wrote: {report_md}")
    print(f"Wrote: {report_json}")
    print(f"Wrote: {tasks_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
