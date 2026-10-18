# On-device benchmark — SM8635, generic pack

Date: 2026-10-18. Phone: Realme GT 6 (RMX3851IN), Snapdragon SM8635,
Android 16, arm64-v8a, device `8316b289` (single-phone run, no second SoC).

## Model

- `litert-community/embeddinggemma-2-text-270m-litert-lm` →
  `embeddinggemma-2-text-270m.litertlm` (generic, no SM8635 AOT file exists —
  closest Qualcomm AOTs are SM8550/SM8650/SM8750, so the generic path is proven
  here as designed).
- Size 164,626,432 bytes; sha256
  `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb`
  matches the Hub LFS record. Pushed via adb to `/data/local/tmp/`, staged by
  the harness into the app-private files dir (app can read `/data/local/tmp`).
- SDK: `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (`EmbeddingEngine`).

## Method

`android/` harness (`EmbeddingBench.kt`, triggered by
`am start … --ez bench true`): try backends in picker order generic-NPU →
GPU → XNNPACK CPU, first accept wins; time init + ONE query embed
(`task: search result | query: sunset photo at the beach`); read RSS from
`/proc/self/status`. Smoke benchmark, not the eval.

## Result

| Backend verdict | Init | 1-query infer | Dim (native) | RSS |
|---|---|---|---|---|
| **NPU ACCEPTED** (first try; GPU/CPU not probed) | 9718 ms | 211 ms | 768 | 739 MB |

- Native dim is 768; the engine truncates + re-normalises to 256d v1
  (`Vectors.truncateRenorm`) — unchanged by this run.
- Single-query infer includes first-infer overhead; query budget is still met
  for interactive typing on the second query onward (to be confirmed by eval).
- RAM at run: MemAvailable ~2.0 GB, MemFree only ~0.3 GB. Re-runs on a fuller
  phone may need freed memory (init holds the 165 MB pack + NPU buffers).
- Rerun: `make bench-device` (see `scripts/bench-device.sh`).

## Verdict

Generic-path claim holds on SM8635: NPU accepts the generic 270M pack,
sub-second query embed after a ~10 s one-time init. Ship picker order on this
SoC: generic NPU → GPU → CPU.
