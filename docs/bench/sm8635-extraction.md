# Extraction parity on device — SM8635 (F017)

Date: 2026-10-30. Phone: Realme GT 6 (RMX3851IN), Snapdragon SM8635,
Android 16, arm64-v8a, device `8316b289` (shared phone; all adb under
`flock /tmp/opencode/adb.lock`).

## Method

`android/src/androidTest/.../ExtractionParityTest.kt` (5 tests, all green via
`:android:connectedDebugAndroidTest`): fixtures ship as `androidTest` assets,
staged to app-private cache, deleted after each probe — no real user files
touched. Generator: `scripts/make-extraction-fixtures.py` (PDF/PNG/WAV) +
ffmpeg (MP4) + libheif sample (HEIC). Design rules mirrored from
`engine/{PdfPages,AudioWindows,VideoKeyframes,ImagePrepare}` (no engine dep on
device). Each probe logs one `F017 <name> verdict=...` line (this table is
transcribed from the `adb logcat` stream, which survives the rotation that
eats `logcat -d` tails on verbose runs).

Dependency note: barteksc/mhiew PdfiumAndroid expose **no text API**
(verified by javap — render + links only), so probe (a) uses axet
`com.github.axet:pdfium:2.0.16`, the only Central Pdfium binding with
`FPDFText` (`Page.open()` → `Text.getText`). ML Kit is
`text-recognition:16.0.1` (`TextRecognizerOptions.DEFAULT_OPTIONS`, bundled
Latin model, works offline). Media3 `1.4.1` transformer + extractor.

## Result — 5/5 instrumented tests PASS (2 gaps recorded, 0 failed)

| Check | Result | Design-rule verdict |
|---|---|---|
| (a) Pdfium text layer, 1-page fixture | `pages=1 chars=307` | **PASS → TEXT**: 307 ≥ 200-char rule (`PdfPages.classifyPage`) |
| (a) Pdfium render @ longest side | `render=1081x1400` | **PASS**: longest == 1400px (`RENDER_LONGEST_PX`), bitmap non-empty |
| (b) HEIC `ImageDecoder` decode | `no-heic-decoder` | **RECORDED-skip**: `MediaCodecList` finds no `image/vnd.android.heic` decoder; `/vendor/etc/media_codecs*.xml` list only `c2.qti.heic.encoder` (encode yes, decode no). Probe returns early, does not fail |
| (c) ML Kit OCR, Latin fixture (304 chars) | `chars=304` | **PASS**: full text back, contains fixture sentence |
| (c) ML Kit OCR, Bengali fixture (Latin engine) | `outChars=0` | **RECORDED-gap**: ML Kit v2 ships latin/chinese/devanagari/japanese/korean — no Bengali model, Latin engine returns empty. Asserted fallback holds: fixture still decodes, so the image-vector path survives regardless of OCR quality |
| (d) Media3 `WavExtractor` PCM of 30s/16kHz/mono fixture | `samples=480000 rmsDb=-15.05` | **PASS**: sample count exact; RMS matches the -15.05 dBFS sine level to 0.01dB (same `20*log10(rms)` math as `AudioWindows.rmsDb`; duplicate is intentional — engine untouched) |
| (d) Gate verdict on that window | `kept=true` | **PASS**: −15.05 ≥ −50dB gate → window kept, matching `planWindows` |
| (d) Media3 `Transformer` WAV→AAC/MP4 | `bytes=364960` | **PASS**: full Media3 decode+encode path works on device (must run on the main thread — `verifyApplicationThread`, done via `runOnMainSync`) |
| (e) `MediaMetadataRetriever` @1fps over 30s clip | `durationMs=30000 frames=30` | **PASS**: 30/30 frames, no drops |
| (e) MTS 12s seek-back fallback | no MTS fixture | **RECORDED-untested**: `VideoKeyframes` doc fallback stays device-unproven |

Raw log lines (`adb logcat -v time | grep F017`, run 2026-10-30 16:34 IST):

```
audio-rms verdict=PASS samples=480000 rmsDb=-15.05 gate=-50.0 kept=true
audio-transformer verdict=PASS wav->mp4 bytes=364960
pdf verdict=PASS pages=1 chars=307 render=1081x1400
video-mmr verdict=PASS durationMs=30000 frames=30 mts-seekback=RECORDED-untested(no-mts-fixture)
ocr-latin verdict=PASS chars=304
ocr-bengali verdict=RECORDED-gap model=none(latin-engine) outChars=0 out= fallback=image-decode-ok
heic verdict=RECORDED-skip reason=no-heic-decoder
```

## Follow-ups (not this feature)

- Bengali OCR stays a known gap: either accept image-vector-only for Bengali
  script (current design already does) or evaluate a Bengali-capable OCR
  backend later. Vowel-sign order specifically untestable until a recognizer
  returns Bengali text.
- HEIC photos on this device decode nowhere in the pipeline (no still decoder
  in firmware): such files would need an app-side HEIC decoder or `skipped`
  with reason. ImagePrepare currently assumes ImageDecoder handles HEIC.
- MTS seek-back needs an MTS/TS fixture pushed the same way (`clip30s.mp4`
  proves the harness; only the container differs).
