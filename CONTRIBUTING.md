# Contributing

## Entry point

Root `Makefile` only — never call `gradlew` directly (except Makefile internals):

```bash
make setup   # idempotent env setup
make test    # JVM unit tests — must pass
make style   # ktlint + detekt — must pass
```

`make test` + `make style` are the gates before any work counts as done.

## TDD

Test first, then implementation. Tests mirror the module 1:1 (`engine/Searcher.kt` ↔ `engine/SearcherTest.kt`). No function ships without a test.

## Commits & tracking

- Conventional commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`).
- Update `docs/features.json` after each task.
- Run greptile review on each feature diff before pushing.

## Rules

- No language allow-lists in code. New script support = test fixture + eval query, never architecture change.
- Never commit models (`.litertlm`) or secrets. Voice keys live in EncryptedSharedPreferences, names documented only.
- Logic lives in `engine/` — Compose screens never embed, decode, or query directly.
- `AGENTS.md` wins on conflicts.
