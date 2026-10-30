# On-device benchmark — SM8635, full-modality 740M pack

Date: 2026-10-30. Phone: Realme GT 6 (RMX3851IN), Snapdragon SM8635,
Android 16, arm64-v8a, device `8316b289` (same phone as the 270M baseline).

## Model

- `litert-community/embeddinggemma-2-740m-litert-lm` →
  `embeddinggemma-2-740m.litertlm` (generic; no SM8635 AOT file exists).
- Size 484,622,336 bytes; sha256
  `e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c`
  matches the Hub LFS record. Pushed via adb to `/data/local/tmp/`, staged by
  the harness into the app-private files dir.
- SDK: `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (`EmbeddingEngine`,
  per-modality `backend`/`visionBackend`/`audioBackend` all set to the same
  candidate backend per attempt).

## Method

`android/` harness (`EmbeddingBench.kt`, triggered by
`am start … --ez bench true`): for each backend in picker order NPU → GPU →
CPU, init ONE 740M engine, then embed each still-pending modality (text query
with the same task prefix as the 270M run, one real 3072×4096 DCIM photo
pulled read-only via adb, one 30s 16kHz-mono s16 WAV fixture standing in for
one AudioWindows window). Success removes the modality from the pending set;
engine closed before the next backend so at most one 740M engine is resident.
RSS from `/proc/self/status`. Rerun: `make bench-device`
(see `scripts/bench-device.sh`).

## Result — GO: all three modalities embed on-device with real vectors

| Backend verdict | Init | Text infer | Image infer | Audio infer | Dim (native) | RSS |
|---|---|---|---|---|---|---|
| **NPU REJECTED** (audio executor won't compile, 609ms) | — | — | — | — | — | — |
| **GPU ACCEPTED** (text+image+audio) | 11942 ms | 58 ms | 932 ms | 3525 ms | 768 | 361 MB |

- CPU not probed (nothing left pending after GPU — first-accept-wins per modality).
- NPU reject detail: `Failed to create EmbeddingEngineImpl … ERROR:
  [embedding_engine_impl.cc] └ ERROR: […/executor/audio_litert_compiled …`
  (truncated at 200 chars in the JSON). Text/vision encoders may still work on
  the NPU, but one engine serves all three encoders and init fails as a unit —
  so the NPU path is unusable for the full 740M model, not just for audio.
- Native dim is 768; the engine truncates + re-normalises to 256d v1
  (`Vectors.truncateRenorm`) — unchanged by this run.
- Raw JSON: `docs/bench/last-full.json` (scratch artifact, regenerated per run).
- Confirmation: a second `make bench-device` run the same day reproduced this
  result bit-for-bit (same init/infer/RSS); run heap split native ~12 MB /
  Java ~10 MB, MemAvailable ~2.4 GB re-read post-run.

## Verdict

**GO (GPU).** The staged-download plan holds: ship 270M first (NPU, 211ms
queries), lazy-load 740M for media behind "enable audio search" with GPU
fallback. Audio windows cost ~3.5s each on GPU — batch indexing overnight /
on-charger, as `Power` guards already assume. NPU rejection is a recorded
negative result (blocks nothing: GPU covers all three modalities).
