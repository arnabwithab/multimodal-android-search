# Architecture — Multimodal Search for Android

> Kotlin-native port of DigUp. UI: Jetpack Compose. Engine: Kotlin + LiteRT-LM + SQLite/FTS5.

## 1. Big picture

```
Chosen folders (SAF + MediaStore observer)
  → Crawler (metadata only, Rules filter)
  → Sync (inode/size/mtime reconcile, move-aware)
  → Prepare (decode on IO dispatcher, 1 batch ahead)
  → Embed (LiteRT-LM .litertlm, selective encoders)
  → IndexStore (one SQLite: files + segments + FTS5, fp16/MRL vectors)
Query → text-only 270M encoder → brute-force cosine + FTS5 → fused rank → Compose list
```

Mirrors DigUp's `Indexer / LlamaRuntime / IndexStore / Searcher` split, minus the Swift/llama.cpp specifics.

## 2. Model layer (the important choice)

Verified on the Hub (Oct 2026 CPU probe): `google/embeddinggemma-300m` text
model exists (768d). No `embeddinggemma-2` id exists on the Hub — the
`litert-community/embeddinggemma-2-*-litert-lm` ids previously listed here
were unverified and are removed.

| HF repo | Params | Download | Use for |
|---|---|---|---|
| `google/embeddinggemma-300m` | 300M | size unverified (prior ~165MB was an estimate) | queries + doc/code text. Ship first, keep resident while typing |
| vision `.litertlm` variant | UNVERIFIED — verify against the Hub before shipping; F012 picker assumptions unconfirmed | estimate (prior ~388MB total was an estimate) | images, PDF renders, video frames. Lazy-load |
| audio `.litertlm` variant | UNVERIFIED — verify against the Hub before shipping; F012 picker assumptions unconfirmed | estimate (prior ~485MB total was an estimate) | audio windows, video soundtracks. Behind "enable audio search" |

Each repo has a generic CPU/GPU file + AOT files per SoC (UNVERIFIED filenames such as `Qualcomm_SM8xxx`, `Tensor_G5/G6`, `MT699x` — verify against the Hub before shipping; F012 picker assumptions unconfirmed). Picker at first run: exact SoC match → generic NPU → GPU → XNNPACK CPU. Backends: Pixel TPU/NPU → Qualcomm NPU → GPU → CPU, with auto-fallback when a delegate rejects ops (split execution hurts more than it helps — same lesson as NNAPI/GPU delegate docs).

Input contracts (from model cards + DigUp):

- Signatures: text tokens `128/256/512/1024/2048/8192`; vision soft tokens `70/140` (use 140 photo, 280 screenshot→140×2 equiv, 70 video frame); audio streamed, ~12 soft tokens.
- Task prefixes on text only: `task: search result | query:`, `title: {t} | text: {c}`, `task: code retrieval | query:` (later). Media inputs take no prefix.
- MRL truncation: store **256d** (re-normalised after slicing). 768d → 1.5KB/vec fp16; 256d → 0.5KB. Query and docs must share the dim. 128d is text-only.
- Never float16 compute (activations overflow → silent NaNs); LiteRT QAT files already handle this. Keep vectors fp16 for storage, float32 for scoring.
- Fingerprint = `model@rev runtime dtype dim budgets`. Change → `resetAll()` + re-embed. Ship a reference-vector test (fixed inputs → cosine ≥0.999 vs HF `sentence-transformers` output) that blocks model/SDK bumps.

## 3. Module map (Kotlin)

```
app/                 Compose: SearchScreen, StatusSheet, Settings, voice buttons
engine/
  Crawler.kt         walk (metadata only) + Rules.kt filter + GitIgnore (v2)
  Sync.kt            byPath/byInode reconcile, prune, dataset detect
  Prepare.kt         ImagePrepare, PdfPages, DocChunks, AudioWindows, VideoKeyframes
  Embedder.kt        LiteRT-LM interface: embedTexts / embedImages / embedAudio
  Indexer.kt         Job batching (images×8, else×1), overlap prepare+embed, finishLongFiles
  IndexStore.kt      Room/SQLite wrapper (files, segments, fts), scope support
  Searcher.kt        score(), meaning(), terms(), fusion, strongCount
  Vectors.kt         fp16 conv, mmap-ish buffer, block mat-vec
  SearchText.kt      NFKC + joiner/harakat strip + alef fold; hasWords (≥20% letters)
  Voice.kt           SpeechRecognizer/TTS + CloudVoice (opt-in, EncryptedSharedPrefs key)
  Power.kt           thermal/battery/Doze guards; MeasuredCosts estimator
```

Two-pass long files: `state pending → partial (read_to/read_end in info JSON) → done`. Each step commits one txn so kills lose nothing. `closeWorker()` frees the full model; text-only stays warm ~30s after typing.

Batching (copy DigUp): up to 16 texts, 4 images, 2 audio per inference. Prepare-next-while-embedding-current via coroutines (`Dispatchers.IO` + single embed mutex — LiteRT-LM session is single-threaded).

## 4. Per-modality extraction (Android APIs)

- **Images:** `BitmapFactory` (bounded `inSampleSize`) → respect `ExifInterface` orientation → longest side 1024 (1536 screenshot, PNG for screenshots). `<256px` → `skipped`. HEIC via `ImageDecoder`; RAW → skip in v1 with reason.
- **Screenshot detect:** `MediaStore` bucket / `Screenshot*` prefixes. **OCR:** ML Kit Text Recognition v2 → FTS-only segment (`modality=none`, no vector) — same rule as DigUp's Vision OCR.
- **PDF:** PdfiumAndroid (not framework `PdfRenderer` — no text layer). Text ≥200 chars → text segment; glyph-order (Indic vowel-sign misplaced ≥5%) → render-only + garbled-text vector; lost-letter Arabic → OCR retry. Render 1400px PNG to cache.
- **Docs:** `txt/md` direct; `html` strip scripts/styles; `docx/odt` unzip `word/document.xml` / `content.xml`; `rtf` strip control words. Chunker: ~1800 chars, 200 overlap, break at whitespace, cap 5000 chunks.
- **Audio:** Media3 `Transformer` → 16kHz mono s16 → 30s/25s windows, RMS silence gate ≈−50dB, tail ≥2s kept. Cap: whole file streams, never full PCM in RAM.
- **Video:** `MediaMetadataRetriever` @1fps → 8×8 aHash dedup (≤4 bits = same shot) → keep ~1/3s, cap 1200 frames, each frame's span = until next kept frame. Soundtrack → AudioWindows path. MTS without index: seek-back 12s fallback (DigUp's ffmpeg lesson).
- **Names/folders:** `name` segment (filename normalised) + folder-trail as low-weight FTS body — cheap, catches half the queries.

## 5. Index schema (same as DigUp, Room)

`files(id, path UNIQUE, kind, size, mtime, inode, device, state, detail, info JSON, indexed_at)` · `segments(id, file→FK CASCADE, kind, modality, loc, loc_end, excerpt, vec BLOB)` · `fts(rowid, name, body, original UNINDEXED, tokenize="porter unicode61 remove_diacritics 2 categories 'L* N* Co M*'")`.

`kind`: name/image/ocr/page/page_image/chunk/audio/frame. `modality`: text/image/audio/none. `loc`: PDF page or seconds. Vectors L2-normed fp16 (256d v1). `meta(fingerprint, vector_source, scans_ocr, costs)`.

Searcher (port `Searcher.kt` ~1:1): score all rows (block 1024), per-modality mean centre, global std, z-gate 2.0; FTS OR-query with prefix-last-while-typing + unindexed-word→prefix fallback; `isLookup` (codes/numbers) doubles word weight; kind-hint +1. Keep `loadVectors=false` idle mode: keyword works, vectors mmap back on first meaning query.

## 6. Background, power, storage

- `WorkManager` expedited worker for first pass + `ContentObserver` (MediaStore) for new files (index in ~1–2s). Foreground-service notification with pause — Play-compliant.
- Guards: stop on thermal `THROTTLING_SEVERE`, Low Power, Doze; charger-only option; retry with backoff. Watchdog: decoder stuck >5min → kill job, mark `stopped`, keep partial.
- Estimates: `MeasuredCosts` port — fixed M4-Pro numbers scaled by one on-device speed check + live tallies per kind; show "≈12 min left" honestly.
- Storage: models in `files/models/` (verifiable, deletable), index in `files/index/*.sqlite` (+WAL), thumbnails in `cache/`. Play Asset Delivery / split APK per SoC keeps base APK small.

## 7. Voice module (opt-in API)

`Voice.kt` behind `VoiceConfig(apiEnabled, provider, keyRef)`:

- Input: mic → (on-device `SpeechRecognizer` | cloud STT) → query string → normal search path. Show "heard: …" + language chip; fallback to typed query on error.
- Output: excerpt → (on-device `TTS` | cloud TTS) → audio. Stream, don't persist.
- Optional transcription boost: cloud transcript of audio/video files → extra `chunk` FTS rows (flagged `transcript=api`), vectors unchanged. Deleting the toggle deletes those rows.
- Keys in `EncryptedSharedPreferences`, per-minute metering UI, no audio logs. Offline = full search still works, voice falls back to on-device.

## 8. Testing — what runs on Linux, what needs a real phone

### A. Runs on Linux (do this first, in CI)

Pure-JVM unit tests (`./gradlew testDebugUnitTest`) — no emulator:

- `SearchTextTest`: NFKC, tatweel/harakat strip, alef fold, `hasWords`, snippet marking (port DigUp's `DigUpKitTests` cases).
- `RulesTest` / `CrawlerTest`: secret/extension/dataset/project-root classification with temp-dir fixtures.
- `ChunkTest`: 1800/200 splitter determinism (later steps resume exactly).
- `IndexStoreTest` (Robolectric + in-memory SQLite FTS5): insert/requeue/move/delete/prune, `pending()` order, `unfinished()` math, migrations 1→5.
- `SearcherTest`: fusion weights, z-gate, `strongCount`, kind-hints, multilingual normalisation — reuse DigUp `evals/*.json` as golden queries.
- `VectorsTest`: fp16 round-trip error <1e-3, block scorer vs naive, 256d truncate+renorm parity.
- `FingerprintTest`: model/settings change forces reset, never mixes dims.
- Embedding parity on Linux CPU: HF `sentence-transformers:google/embeddinggemma-2` vs LiteRT-LM Linux build on 20 fixed prompts (cos ≥0.99 text, ≥0.98 image). LiteRT-LM ships Linux/Windows/macOS too, so the `.litertlm` text signature smoke-runs here — catches tokenizer/prefix bugs before touching a phone.
- `ffmpeg`-based extractor parity: run `AudioWindows`/`VideoKeyframes` logic against DigUp's `scripts/make-testbed.sh` fixtures, compare window counts/timestamps.

Command: `scripts/test-linux.sh` → unit tests + `evals/run.py --backend litert-lm-linux --dim 256` → must hold DigUp's R@1 before merging.

### B. Needs a real Android device (or at least an emulator with Play APIs)

- **UI / interaction:** Compose search list, grid, filters, status sheet, settings, dark mode, TalkBack, rotation, low-end 4GB RAM jank. Screenshot tests (Paparazzi) catch layout, not feel — thumb-test on a real phone.
- **Permissions & scoped storage:** `READ_MEDIA_*`, `OPEN_DOCUMENT_TREE` grants, cloud-placeholder (`COLUMN_IS_DOWNLOAD_REQUIRED`) behaviour, SD-card unmount mid-crawl.
- **Accelerators:** NPU/GPU delegate accept-reject per SoC (Pixel vs Snapdragon vs Tensor vs MediaTek give different latencies in the model card table). Measure with `scripts/bench-device.sh` (init ms, infer ms, RSS) and pick the shipped AOT file per device. Emulator CPU numbers lie — always confirm on 2–3 real SoCs.
- **Media extraction:** `ImageDecoder` HEIC, PdfiumAndroid render vs desktop PDFium, `MediaMetadataRetriever` frame accuracy on MTS/HEVC, ML Kit OCR language gaps (Bengali vowel-sign order!), `SpeechRecognizer` offline packs per locale.
- **Background & power:** WorkManager expedite + Doze + thermal throttle + "index on charger only" — cannot be simulated on Linux. Field test: 500-file Downloads folder, measure drain + time, confirm pause/resume.
- **Voice API toggle:** end-to-end mic→cloud→results→TTS on real network, key storage, cost meter, airplane-mode fallback.
- **Play compliance:** foreground-service type (`dataSync`), photo-picker vs full access, 16KB-page + size (models as Play Asset packs, not in APK).

Rule of thumb: **logic + ranking on Linux, physics on phone.** If an eval passes on Linux but feels slow or wrong on a $200 device, the device wins — re-budget (fewer frames, 128d vectors, vision-only-on-WiFi) until the phone agrees.
