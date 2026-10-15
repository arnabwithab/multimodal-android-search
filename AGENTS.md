# AGENTS.md

## Project Overview

On-device multimodal file search for Android — a Kotlin-native port of DigUp (`ARahim3/DigUp`). It indexes the user's chosen folders (photos, screenshots, PDFs, docs, audio, video) with EmbeddingGemma 2 via LiteRT-LM into one SQLite index (vectors + FTS5), then answers "describe what you remember" queries with hybrid meaning + exact-word search. For anyone whose files are messy and multilingual — every language handled, none gated. Private by default; cloud voice (STT/TTS) strictly opt-in.

Design truth: `docs/design.md` (behaviour, UX, privacy) and `docs/architecture.md` (modules, models, testing split). If a design doc covers what you're building, it wins.

## Development Philosophy

- TDD first: write the test, then the implementation. Never skip.
- Tests mirror the structure of the module they test (`engine/Searcher.kt` ↔ `engine/SearcherTest.kt`).
- No function ships without a test.
- Thin UI — logic lives in `engine/`; Compose screens never embed, decode, or query directly.
- Explicit over clever — readable code beats smart code.
- If it isn't runnable via `make`, it isn't done.
- Checkpoint commits + push: commit and `git push` at intervals — after each passing `make test` / `make style`, after each feature/subtask, and before switching tasks. Run greptile-reviewer on the feature diff before each push. Never end a run or session with unpushed work.
- Logic + ranking must pass on Linux before touching a phone; if the phone disagrees, the phone wins (re-budget, don't argue).

## Tech Stack

- App: Kotlin + Jetpack Compose (Material 3), single `app/` module, `minSdk 30`
- Language: Kotlin, coroutines + Flow; DI: Hilt (or manual if smaller — justify)
- AI: LiteRT-LM SDK (`.litertlm`: text-270M / text-vision-440M / full-740M), XNNPACK CPU fallback, per-SoC NPU AOT packs
- Database: Room over SQLite + FTS5 (`porter unicode61 remove_diacritics 2 categories 'L* N* Co M*'`), vectors as fp16/MRL-256 BLOBs
- Media: MediaStore + SAF, BitmapFactory/ImageDecoder + ExifInterface, PdfiumAndroid, Media3 Transformer, ML Kit Text Recognition v2, Android SpeechRecognizer + TextToSpeech
- Secrets: EncryptedSharedPreferences (voice API keys only; never logged)
- JVM tests: JUnit5 + Robolectric (no emulator); device tests: instrumented + Macrobenchmark
- Static analysis: ktlint + detekt via `make style`
- Build/Task Runner: **Make** — root `Makefile` is mandatory, single entry point wrapping Gradle.

## Key Commands

All commands MUST run via `make <target>` from the project root. Calling `gradlew` directly is for the Makefile's internals only.

```bash
make setup                       # JDK + Android SDK + NDK rev + pre-commit hooks; idempotent
make dev                         # installs debug APK on attached device (adb) + logcat tag filter
make test                        # JVM unit tests: ./gradlew testDebugUnitTest (Linux-safe, CI gate)
make test-device                  # instrumented tests on attached device/emulator
make eval                        # ranking evals on Linux (evals/*.json vs LiteRT-LM Linux build)
make bench-device                # NPU/GPU/CPU latency + RSS on attached device -> docs/bench/
make style                       # ktlint + detekt (format + lint in one)
make build                       # release AAB (Play Asset packs for .litertlm)
make clean                       # removes build/, .gradle caches, local model downloads
```

## Directory Structure

```
multimodal-search-for-android/
├── app/
│   ├── src/main/java/com/filesearch/android/
│   │   ├── app/                # Compose: SearchScreen, StatusSheet, Settings, voice buttons
│   │   ├── engine/             # Crawler, Sync, Prepare, Embedder, Indexer, IndexStore,
│   │   │                       # Searcher, Vectors, SearchText, Voice, Power
│   │   └── di/                 # Hilt modules
│   ├── src/test/java/.../      # mirrors engine/ 1:1 (JUnit5 + Robolectric, Linux-safe)
│   ├── src/androidTest/java/   # device-only: permissions, MediaStore, delegates, voice E2E
│   └── build.gradle.kts
├── docs/
│   ├── design.md               # product behaviour, UX, privacy (wins on conflicts)
│   ├── architecture.md         # modules, models, schema, test split
│   └── features.json           # canonical feature tracker — always kept up to date
├── evals/                      # golden queries (≥6 scripts) + run.py harness
├── scripts/
│   ├── test-linux.sh           # unit tests + eval gate
│   └── bench-device.sh         # adb latency/memory per SoC
├── Makefile                    # single entry point (see Key Commands)
├── settings.gradle.kts
├── AGENTS.md
└── README.md
```

## Conventions

### Makefile (required)

- Root `Makefile` is **mandatory**, thin wrappers over `./gradlew` — no business logic in Make.
- Required targets: `setup`, `dev`, `test`, `style`, `build`, `clean`. Added: `test-device`, `eval`, `bench-device`. Never remove the required set.
- `make setup` idempotent and safe to re-run. `make test` is the Linux CI gate (no device needed). Every target has a `## description` comment.

### Kotlin

- Formatter: ktlint; Linter: detekt (includes import order, complexity caps).
- Naming: camelCase functions/vars/properties, PascalCase classes/objects, SCREAMING_SNAKE constants; packages lowercase (`com.filesearch.android.engine`).
- One embed session at a time — LiteRT-LM calls serialised behind a single mutex; decode on `Dispatchers.IO`, never on Main.
- Never block the UI thread on embedding or DB; search exposes `Flow<List<SearchHit>>` with debounce + `prefixLast` FTS while typing.
- Logging via Timber (never `println`); no PII or file contents in logs — paths truncated to basename in release.
- Errors are values at trust boundaries: unreadable/locked/failed files get `state + detail` rows, never crash the queue.

### Naming

- No `digup` naming anywhere in code, paths, or identifiers — the package is `com.filesearch.android`. Docs may credit DigUp as inspiration (attribution prose only), never as a name.

### General

- Commits: conventional commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`).
- Secrets: never committed; voice API keys live in EncryptedSharedPreferences, `.env.example`-style key *names* documented only.
- **All setup/test/style/build steps run through the root `Makefile`.**
- **README badges**: HTML shield badges (shields.io) via raw `<img>` tags for build, version, license, tech stack.
- Language rule: no language allow-lists in code. New-script support = test fixture + eval query, never architecture change. Stopword/kind-hint tables are data files under `engine/lang/`, extensible without release.

## Deployment Philosophy

On-device app — no backend to host. Targets:

- **App**: Google Play internal track → production; release as AAB, 16KB-page compatible.
- **Models**: never in the APK — Play Asset Delivery packs per SoC (generic + `Qualcomm_SM8xxx` / `Tensor_G5/G6` / `MT699x` AOT `.litertlm`), Wi-Fi default download, SHA-verified, user-deletable.
- **No server component.** The old web-backend pattern (Render/Vercel/HF Spaces) does not apply; flag any proposal that adds one as out-of-scope unless the user asks.

## Multi-Agent Workflow

When `docs/features.json` holds 3+ independent features (different modules, no shared files), parallelise with subagents.

### Flow

1. **Plan**: Identify independent features from `features.json`. Same-file features batch sequentially.
2. **Build**: Up to 3 builder subagents at a time. When one completes, spawn the next pending feature.
3. **E2E** (if applicable): device/emulator instrumented tests after all builders complete.
4. **Review**: greptile-reviewer reviews each completed feature diff before its checkpoint push; plus ponytail-reviewer audits the combined diff every 3–4 features.
5. **Verify**: `make test && make style` (Linux gate) + `make eval` ranking gate.

Fewer than 3 independent features: implement directly, no subagents.

### Subagents

Defined in `~/.config/opencode/agents/`, all `model: opencode-go/deepseek-v4-flash`.

| Agent | File | Purpose | Permissions |
|-------|------|---------|-------------|
| builder | `builder.md` | TDD one feature, tests then implementation | edit: allow, bash: allow, task: { \*: deny, playwright-tester: allow } |
| playwright-tester | `playwright-tester.md` | E2E via playwright-cli (only if a web companion exists; else instrumented tests) | edit: deny, bash: allow |
| greptile-reviewer | `greptile-reviewer.md` | Adversarial review per-feature (pre-push gate) | edit: deny, bash: allow |
| ponytail-reviewer | `ponytail-reviewer.md` | Bloat audit on combined diff every 3–4 features | edit: deny, bash: allow |

### Edge cases

- **Dependent features** (same files): sequenced in one builder.
- **Greptile / ponytail finds issues**: main agent decides fix-now vs file-as-debt (`shortcut:` comment).
- **No E2E defined**: device-test step skipped, JVM + eval gates still required.
- **Ranking regression**: any `make eval` R@1 drop blocks merge, even if unit tests pass.

## Agent Guidelines

- Always run `make style` before considering code done; `make test` after changes — fix failures before moving on.
- Never use `println` / raw `Log` in app code — Timber only; never log file contents or keys.
- Never put embedding/DB/decode logic in Compose screens — belongs in `engine/`.
- Always check `docs/design.md` + `docs/architecture.md` before starting — they take precedence; missing design for a significant task → flag to user first.
- Always update `docs/features.json` after completing any task — statuses, test files, new features.
- Any new setup/test/build step becomes a Makefile target, not prose.
- Vector rule: fingerprint (`model@rev runtime dtype dim budgets`) change → full re-embed; never mix dims (query/doc must share 256d v1).
- Privacy rule: nothing leaves the device except model fetch + user-enabled voice API. Flag anything else as out-of-scope.
- If >=3 independent features in `docs/features.json`, spawn builders (max 3 concurrent).

`docs/features.json` — canonical tracker (abridged shape; full file in repo):

```json
{
  "project": "multimodal-search-for-android",
  "last_updated": "2026-10-14",
  "summary": { "total": 12, "completed": 0, "in_progress": 0, "planned": 12, "tests_passing": 0, "tests_failing": 0, "tests_missing": 12 },
  "features": [
    {
      "id": "F001",
      "name": "[Feature Name]",
      "description": "[What + why]",
      "status": "planned",
      "priority": "high",
      "module": "app/src/main/java/com/digup/android/engine",
      "design_doc": "docs/design.md",
      "tests": { "status": "missing", "files": [], "notes": "" },
      "subtasks": [{ "id": "F001-1", "name": "[Subtask]", "status": "planned" }],
      "notes": "",
      "added": "2026-10-14",
      "completed": null
    }
  ]
}
```

## Project-Specific Notes

- External models (Hugging Face, Apache-2.0): `litert-community/embeddinggemma-2-text-270m-litert-lm` (queries first), `...-text-vision-440m-litert-lm`, `...-740m-litert-lm` (+ per-SoC AOT). Never commit model files; scripts download + SHA-verify.
- Optional voice APIs (both OFF by default, keys in EncryptedSharedPreferences): STT Google Cloud / Whisper, TTS Google Cloud / ElevenLabs. Toggle-off must delete cloud transcript rows.
- Never touch: user's files (read-only via SAF/MediaStore), `docs/` unless asked, committed secrets (none exist).
- Gotchas: LiteRT-LM session is single-threaded (mutex); float16 compute is forbidden (silent NaNs — QAT files handle it); query/doc dims must match (256d v1); emulator CPU timings lie — confirm NPU/GPU on 2–3 real SoCs; ML Kit OCR has script gaps (image vector still matches); MTS seeks need 12s lead-back; 16KB-page + Asset packs for Play.
