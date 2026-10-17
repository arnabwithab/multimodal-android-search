#!/usr/bin/env python3
"""Meaning-only R@1 over the paraphrase corpus with real EmbeddingGemma weights.

Loads google/embeddinggemma-300m via sentence-transformers (CPU), embeds the
evals/golden.json queries prefixed exactly as Embedder.QUERY_PREFIX does
("task: search result | query: ") and every evals/corpus.json doc prefixed as
Embedder.docPrefix does ("title: {t} | text: {c}"), then cosine-ranks each
query against all docs. No FTS, no word-overlap backoff: pure embedding space.

Writes evals/r_at_1_report.json
{model, dim, r_at_1, r_at_3, mean_rank, per_script, n_docs,
 meaning_only:true, timestamp} plus per-query details.

Ranks with full 768d vectors here; the app stores native MRL-256
(naive-truncation drift for that is measured by parity.py).

Scope honesty: this measures the embedding space + paraphrase corpus, NOT the
JVM code path — the JVM EvalRunner cannot load weights (no runtime dep). The
JVM-wire-up (real weights through Embedder on the LiteRT-LM Linux build) is
still pending; see evals/README.md.

Usage: python3 evals/r_at_1.py   (CPU-only; offline/model-gated envs SKIP)
"""
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).parent
MODEL_ID = "google/embeddinggemma-300m"

# Mirrors Embedder.kt exactly (Embedder.QUERY_PREFIX + docPrefix).
QUERY_PREFIX = "task: search result | query: "


def doc_prefix(title: str, text: str) -> str:
    return f"title: {title} | text: {text}"


SCRIPT_OF = {
    "en": "Latin", "es": "Latin",
    "bn": "Bengali",
    "ar": "Arabic",
    "hi": "Devanagari",
    "zh": "CJK", "ja": "CJK",
    "th": "Thai",
}


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(x * x for x in b) ** 0.5
    return dot / (na * nb) if na and nb else 0.0


def main() -> int:
    try:
        golden = json.loads((HERE / "golden.json").read_text(encoding="utf-8"))
        corpus = json.loads((HERE / "corpus.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"FAIL cannot read eval json: {exc}")
        return 1
    if len(golden) != 12 or any(q["id"] != f"q{i + 1:02d}" for i, q in enumerate(golden)):
        print("FAIL golden.json shape changed (expect 12 queries q01..q12)")
        return 1
    by_id = {d["id"]: d for d in corpus}
    expected = {}
    for i, q in enumerate(golden):  # corpus ids 1..12 mirror golden order
        if i + 1 not in by_id:
            print(f"FAIL corpus missing expected doc id {i + 1}")
            return 1
        expected[q["id"]] = i + 1

    try:
        from sentence_transformers import SentenceTransformer

        t0 = time.perf_counter()
        model = SentenceTransformer(MODEL_ID, device="cpu")
        q_vecs = model.encode(
            [QUERY_PREFIX + q["query"] for q in golden],
            normalize_embeddings=True, show_progress_bar=False)
        d_vecs = model.encode(
            [doc_prefix(d["title"], d["text"]) for d in corpus],
            normalize_embeddings=True, show_progress_bar=False)
        dim = len(q_vecs[0])
        dt = time.perf_counter() - t0
    except Exception as exc:  # offline / gated download / missing dep: honest SKIP
        print(f"SKIP {exc}")
        return 0

    order = [d["id"] for d in corpus]
    vec = {d["id"]: list(map(float, v)) for d, v in zip(corpus, d_vecs)}
    details, script_ranks = [], {}
    for q, qv in zip(golden, q_vecs):
        qv = list(map(float, qv))
        scored = sorted(((cosine(qv, vec[i]), i) for i in order), reverse=True)
        rank = next(n for n, (_, i) in enumerate(scored, 1) if i == expected[q["id"]])
        top_score = scored[0][0]
        # A top score of 0.0 never counts (mirrors EvalRunner: no tie-luck).
        hit1 = rank == 1 and top_score > 0.0
        details.append({"id": q["id"], "lang": q["lang"], "rank": rank,
                        "top_doc": scored[0][1], "expect_doc": expected[q["id"]],
                        "hit_at_1": hit1, "hit_at_3": rank <= 3 and top_score > 0.0})
        script_ranks.setdefault(SCRIPT_OF.get(q["lang"], q["lang"]), []).append((rank, top_score))

    n = len(golden)
    r_at_1 = sum(d["hit_at_1"] for d in details) / n
    r_at_3 = sum(d["hit_at_3"] for d in details) / n
    mean_rank = sum(d["rank"] for d in details) / n
    per_script = {s: {"n": len(r), "r_at_1": sum(1 for x, t in r if x == 1 and t > 0.0) / len(r),
                      "mean_rank": sum(x for x, _ in r) / len(r)}
                  for s, r in sorted(script_ranks.items())}

    print(f"model={MODEL_ID} dim={dim} docs={len(corpus)} cpu_time={dt:.1f}s")
    print(f"R@1={r_at_1:.3f} R@3={r_at_3:.3f} mean_rank={mean_rank:.2f}")
    for d in details:
        mark = "HIT " if d["hit_at_1"] else f"rank={d['rank']:<3}"
        print(f"  {mark} {d['id']} [{d['lang']}] expect={d['expect_doc']} top={d['top_doc']}")
    for s, st in per_script.items():
        print(f"  script {s}: R@1={st['r_at_1']:.3f} mean_rank={st['mean_rank']:.2f} n={st['n']}")

    (HERE / "r_at_1_report.json").write_text(json.dumps({
        "model": MODEL_ID, "dim": dim, "r_at_1": r_at_1, "r_at_3": r_at_3,
        "mean_rank": mean_rank, "per_script": per_script, "n_docs": len(corpus),
        "n_queries": n, "meaning_only": True,
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "details": details,
    }, indent=2), encoding="utf-8")
    print("wrote evals/r_at_1_report.json")
    return 0  # a low score is data, never a failure


if __name__ == "__main__":
    sys.exit(main())
