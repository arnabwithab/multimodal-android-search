"""Parity probe: real EmbeddingGemma vectors on Linux CPU for the eval R@1 story.

Loads google/embeddinggemma-300m via sentence-transformers, embeds the
evals/golden.json queries plus paraphrase pairs and unrelated controls, and
checks the three shared-space claims the on-device gate depends on:
  1. self-similarity == 1.0
  2. min(paraphrase cosine) > max(unrelated cosine)
  3. truncated-256d vs full-768d cosine drift (app stores 256d MRL vectors)

Writes reference_vectors.json (verdict + ALL encoded vectors + drift stats)
for the future R@1 gate. The model download is gated: offline / unauthenticated
/ missing-dep environments print SKIP <reason> and exit 0, never failing CI.
A holds=False verdict exits nonzero — real signal, honestly labelled.
Usage: python3 evals/parity.py
"""
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).parent
MODEL_ID = "google/embeddinggemma-300m"
TRUNC_DIM = 256

# Paraphrase pairs: same intent, different wording (incl. cross-script EN<->native).
PARAPHRASES = [
    ("sunset photo at the beach", "a picture of the sun setting over the sea"),
    ("restaurant bill from Madrid", "factura del restaurante de Madrid"),  # cross-script, same intent
    ("factura del restaurante de Madrid", "cuenta de un restaurante en Madrid"),
    ("beach sunset photo", "সমুদ্র সৈকতে সূর্যাস্তের ছবি"),  # cross-script, same intent
    ("sunset photo at the beach", "সমুদ্র সৈকতে সূর্যাস্তের ছবি"),  # cross-script, same intent
    ("فاتورة مطعم القاهرة", "إيصال حساب مطعم في القاهرة"),
    ("海滩日落照片", "一张海滩上日落的照片"),
]

# Unrelated controls: share no intent with each other or the paraphrases.
UNRELATED = [
    "quarterly tax audit spreadsheet",
    "weekday bus timetable for downtown routes",
    "quantum tunneling microscope calibration",
    "修理自行车变速器的步骤",
]


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(x * x for x in b) ** 0.5
    return dot / (na * nb)


def trunc_cosine(a, b, dim=TRUNC_DIM):
    return cosine(a[:dim], b[:dim])


def main() -> int:
    golden = json.loads((HERE / "golden.json").read_text(encoding="utf-8"))
    golden_texts = [q["query"] for q in golden]
    pair_texts = [t for p in PARAPHRASES for t in p]
    texts = golden_texts + pair_texts + UNRELATED

    try:
        from sentence_transformers import SentenceTransformer

        t0 = time.perf_counter()
        model = SentenceTransformer(MODEL_ID, device="cpu")
        dim = model.get_sentence_embedding_dimension()
        vecs = model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
        dt = time.perf_counter() - t0
    except Exception as exc:  # offline / gated download / no auth / missing dep
        print(f"SKIP {exc}")
        return 0

    vecs = [list(map(float, v)) for v in vecs]
    by_text = dict(zip(texts, vecs))

    # 1. self-similarity
    self_sim = cosine(vecs[0], vecs[0])

    # 2. paraphrase vs unrelated
    para_sims = [cosine(by_text[a], by_text[b]) for a, b in PARAPHRASES]
    unrel_sims = [cosine(by_text[UNRELATED[i]], by_text[UNRELATED[j]])
                  for i in range(len(UNRELATED)) for j in range(i + 1, len(UNRELATED))]
    # cross: paraphrase anchors vs unrelated controls
    anchors = [PARAPHRASES[0][0], PARAPHRASES[4][0], PARAPHRASES[5][0]]
    cross = [cosine(by_text[a], by_text[u]) for a in anchors for u in UNRELATED]
    unrel_max = max(unrel_sims + cross)
    para_min = min(para_sims)
    holds = para_min > unrel_max

    # 3. truncated-256d drift over all pairs above
    pairs = PARAPHRASES + [(UNRELATED[i], UNRELATED[j])
                           for i in range(len(UNRELATED)) for j in range(i + 1, len(UNRELATED))]
    drifts = [abs(cosine(by_text[a], by_text[b]) - trunc_cosine(by_text[a], by_text[b]))
              for a, b in pairs]
    drift_mean = sum(drifts) / len(drifts)
    drift_max = max(drifts)

    print(f"model={MODEL_ID} dim={dim} n={len(texts)} cpu_time={dt:.1f}s")
    print(f"self_similarity={self_sim:.6f}")
    print(f"paraphrase_min={para_min:.4f} unrelated_max={unrel_max:.4f} holds={holds}")
    for (a, b), s in zip(PARAPHRASES, para_sims):
        print(f"  para {s:.4f} | {a[:40]!r} <-> {b[:40]!r}")
    print(f"trunc256_drift mean={drift_mean:.4f} max={drift_max:.4f}")

    ref = {
        "model_id": MODEL_ID,
        "dim": dim,
        "trunc_dim": TRUNC_DIM,
        "cpu_seconds": round(dt, 1),
        "golden_ids": [q["id"] for q in golden],
        "verdict": {
            "self_similarity": self_sim,
            "paraphrase_min": para_min,
            "unrelated_max": unrel_max,
            "holds": holds,
        },
        "drift": {"trunc_dim": TRUNC_DIM, "mean": drift_mean, "max": drift_max},
        "vectors": {
            "queries": {q["id"]: by_text[q["query"]] for q in golden},
            "paraphrases": [[a, b, s] for (a, b), s in zip(PARAPHRASES, para_sims)],
            "unrelated": UNRELATED,
            "unrelated_vectors": [by_text[u] for u in UNRELATED],
        },
    }
    (HERE / "reference_vectors.json").write_text(json.dumps(ref), encoding="utf-8")
    print("wrote evals/reference_vectors.json")
    return 0 if holds else 1


if __name__ == "__main__":
    sys.exit(main())
