#!/usr/bin/env python3
"""Golden-query shape gate for multilingual retrieval (F013).

Validates evals/golden.json (unique ids, required fields, >=6 scripts
plus >=2 mixed-language queries) and prints PASS. R@1 scoring needs the
JVM Indexer wiring (F014), so ranking is an honest SKIP, not a hollow gate.
"""
import json
import sys
from pathlib import Path

SCRIPT_OF = {
    "en": "Latin", "es": "Latin",
    "bn": "Bengali",
    "ar": "Arabic",
    "hi": "Devanagari",
    "zh": "CJK", "ja": "CJK",
    "th": "Thai",
}

REQUIRED_SCRIPTS = {"Latin", "Bengali", "Arabic", "Devanagari", "CJK", "Thai"}


def main() -> int:
    path = Path(__file__).with_name("golden.json")
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"FAIL: cannot read golden.json: {exc}")
        return 1

    if not isinstance(entries, list) or not entries:
        print("FAIL: golden.json must be a non-empty list")
        return 1

    ids = [e.get("id") for e in entries]
    if len(set(ids)) != len(ids):
        print("FAIL: duplicate ids in golden.json")
        return 1

    for e in entries:
        for field in ("id", "query", "lang", "expect_doc_contains"):
            if not e.get(field):
                print(f"FAIL: entry {e.get('id')!r} missing {field!r}")
                return 1

    unknown = sorted({e["lang"] for e in entries} - set(SCRIPT_OF) - {"mixed"})
    if unknown:
        print(f"FAIL: unknown lang tags: {unknown}")
        return 1

    scripts = {SCRIPT_OF.get(e["lang"]) for e in entries} - {None}
    missing = REQUIRED_SCRIPTS - scripts
    if missing:
        print(f"FAIL: scripts missing: {sorted(missing)} (have {sorted(scripts)})")
        return 1

    mixed = [e for e in entries if e["lang"] == "mixed"]
    if len(mixed) < 2:
        print(f"FAIL: need >=2 mixed-language queries, have {len(mixed)}")
        return 1

    print(f"PASS: {len(entries)} queries, scripts={sorted(scripts)}, mixed={len(mixed)}")
    print("SKIP R@1: JVM eval runner lands with Indexer wiring (F014)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
