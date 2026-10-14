# Design — Multimodal Search for Android (DigUp port)

> Companion to `architecture.md`. Product behaviour, UX, privacy, and scope.
> Source of truth for behaviour: DigUp (`ARahim3/DigUp`) — folders → readers → EmbeddingGemma 2 → SQLite (vectors + FTS5) → hybrid search.

## 1. Goal

A clean, elegant, private search app: type (or speak) what you remember — *"zebra in a video", "where they talk about sleep", "pet clause in lease"* — and jump to the photo, PDF page, or audio moment. On-device by default. No account, no telemetry.

## 2. Non-goals (v1)

- No iOS / desktop. Android-only, Kotlin + Compose (see §9 for why not Flutter).
- No cloud index. Nothing leaves the phone except when the user flips the voice-API toggle.
- No generative answers / chat. Retrieval only.
- No code search in v1 (DigUp's `code:` index is a second SQLite file; port it later).
- No RAW photos, no legacy `.doc`, no editing files. Search only, never modify user files.

## 3. Who it's for — all languages, no exceptions

Someone with a messy Downloads / WhatsApp / lecture-recordings folder who remembers *what was in it*, not its name — in whatever language they think in.

There is no supported-languages list and no language picker. The app must accept queries, file names, and file contents in any language and never refuse, hide, or mistreat input because of its script:

- **Meaning layer:** EmbeddingGemma 2 covers 100+ languages in one shared space, so cross-lingual search (English query → Bengali note, Arabic query → English PDF) works out of the box. No per-language models, no language gate before embedding.
- **Keyword layer stays script-agnostic:** NFKC normalisation, invisible-format stripping, and the FTS5 `unicode61 … categories 'L* N* Co M*'` tokenizer keep marks (Bengali vowel signs, Arabic harakat) inside their words. Never split on what looks like "non-letters" to Latin eyes. Languages without spaces (Chinese, Japanese, Thai, Khmer) match as prefixes; zero-width space counts as a word break.
- **No hardcoded language caps:** stopword and kind-hint lists (`photo`, `screenshot`, `video`, …) ship with a starter set but are data, not code — extendable without a release. An unknown language simply gets: full meaning search + generic word search, no stemming bonus, no penalty.
- **Graceful gaps, never blocks:** where a helper is language-limited (ML Kit OCR ≈ Latin/CJK/Devanagari, on-device STT voice packs), the app degrades, not refuses — a Bengali screenshot still matches by its image vector; STT falls back to typing or the opt-in cloud toggle. Gaps are labelled in Settings ("OCR doesn't read Bengali yet — photos still match by sight"), never silent.
- **Tests prove it:** golden queries must span ≥6 scripts (Latin, Bengali, Arabic, Devanagari, CJK, Thai) plus mixed-language queries. Adding a language is a test fixture + eval query, never an architecture change.

## 4. Core UX (3 screens only)

1. **Search** — one bar, live results. `↓/↑`-equivalent is scroll; tap opens at the moment/page; long-press shows in Files; big-grid toggle (`⌘O` equivalent) with kind filters (photos / PDFs / audio / video).
2. **Indexing status** — bottom-sheet / menu equivalent of DigUp's menubar: progress per kind, pause button, "how long left" estimate, offline note.
3. **Settings** — folders to watch, excluded types, battery rule (index on battery vs charger-only), voice toggle, model storage, remove-all-data.

Global entry: App Shortcuts + share-to-search intent. No `⇧⌘Space` hotkey on Android; use launcher widget + notification tile instead.

### Result rows

Each row shows: thumbnail (frame/page/cover), file name, kind badge, one-line excerpt with `«match»` marks, timestamp (`p.5` / `02:31`). Weak matches (score < 0.5× top) fold under "weaker matches" — DigUp's `strongCount` rule, it cut 26 → 6 rows with zero answer loss.

## 5. What we index (and what we skip)

User picks folders (default: Downloads, Pictures, Documents, Music/Podcasts). Inside:

| Kind | We read | We skip |
|---|---|---|
| Photos, screenshots | downscaled copy + EXIF date; screenshot text via OCR | `<256px` icons, app caches |
| PDF | text per page; scans/slides as pictures (render 1400px) | password-locked, `>5000` pages tail |
| Docs (`txt md html docx odt rtf`) | chunked text | digit-dumps get keyword-only, no vector |
| Audio (any length) | 30s windows, 25s hop, skip silence | `<2s` / silent |
| Video | 1 frame / 3s deduped + soundtrack as audio | no-stream files |

Never: keys/certs (`.pem .key .p12`), `.env`/password files, hidden folders, app-private dirs, cloud-only placeholders (download would cost data — skip and label "not downloaded"), dataset folders (≥300 machine-named media in one dir), vendored code.

First pass order (DigUp's `indexingOrderSQL`): screenshots → images → PDFs → docs → audio → video — what people search for most, first. Long PDFs/docs do two passes: first 30 pages / 40 chunks now, rest after everything else is searchable.

## 6. Search behaviour

Hybrid, same as DigUp:

- **Meaning:** query embedded with `task: search result | query: …`, cosine vs every vector, normalised per modality (text scores run hotter than image/audio, so each modality is centred separately), z-gate `2.0` = "stands out or it doesn't count".
- **Words:** FTS5 with stemming over file names, folder names, OCR, page/chunk text. Exact codes/IDs/numbers get a boost (embeddings can't hold `K7Q2LM`).
- **Fusion:** `meaning + 2.0 × wordCoverage^1.5 + kindHint` ("…in a video" boosts video). A query made only of codes/numbers weighs words double.
- Multilingual: English query finds Bengali note and vice versa. CJK/Thai (no spaces) match as prefixes; OCR only covers ~25 languages so CJK screenshots depend on the image vector.

Empty state must be honest: "nothing stands out" rather than a random file.

## 7. Voice (optional API, off by default)

Two independent toggles in Settings → Voice, both **off** on install:

- **Voice input (STT):** mic button in search bar. Default = on-device Android `SpeechRecognizer` (free, offline packs, 50+ langs). Toggle = "Use cloud transcription (better accents, sends audio to …)". Cloud options: Google Cloud STT or Whisper API.
- **Read results aloud (TTS):** speaker button on a hit. Default = on-device `TextToSpeech` + downloaded voice. Toggle = cloud voice (Google Cloud TTS / ElevenLabs).

Why API at all: on-device STT struggles with heavy accents and long-form podcast transcription. An opt-in "transcribe my audio via API for better exact-word hits" upgrades audio from vibe-only to vibe + transcript keywords. The toggle screen states plainly: provider name, what is sent, per-minute cost borne by user's key, and one-tap "delete cloud history". Turning it off returns to fully offline with zero residue.

## 8. Privacy & permissions

- `READ_MEDIA_*` + `ACTION_OPEN_DOCUMENT_TREE` only for chosen folders. No broad `MANAGE_EXTERNAL_STORAGE` unless Play review forces it — avoid.
- Model download once (~165MB text, +223MB vision, +97MB audio) over Wi-Fi by default, checksum-verified, deletable.
- No network except model fetch + optional voice API + optional update check (off-switchable). "Remove app data" wipes models + index + logs.
- API keys stored in EncryptedSharedPreferences, never logged.

## 9. Why Kotlin, not Flutter

This UI is a search box + list. Flutter would still need Kotlin for *everything* hard (LiteRT, MediaStore, Pdfium, Media3, WorkManager, ML Kit). That leaves Flutter as a thin shell paying bridge + size + jank tax for zero reuse (Android-only v1). Kotlin + Jetpack Compose gives one language, direct LiteRT-LM SDK, native background APIs, smaller APK. Revisit Flutter only if an iOS twin is green-lit.

## 10. Performance budgets (from LiteRT model cards)

| Device | Text | +Vision | +Audio |
|---|---|---|---|
| Pixel NPU | ~8ms / 112MB | ~49ms / 125MB | ~194ms / 121MB |
| S-class CPU (XNNPACK) | ~27ms / 334MB | ~175ms / 694MB | ~305ms / 465MB |

Budgets: query <150ms on NPU, <400ms CPU; indexing pauses on thermal/low-power; model process released when idle (DigUp's `closeWorker` pattern).

## 11. Edge cases

- File moved/renamed → re-path by inode, no re-embed. Changed → re-queue. Deleted → drop rows. Drive/folder vanished mid-crawl → keep rows, don't nuke.
- Corrupt/unreadable file → mark `failed` with reason, never block the queue. Runaway decoder → watchdog marks `stopped`, keeps what's indexed so far.
- Model or settings changed → fingerprint mismatch → full re-embed with user notice (never mix vectors from different models).
- DigUp evals (`evals/*.json`: synthetic, multilingual, code) ported as regression queries; ranking change must not drop R@1.
