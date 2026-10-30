# Field test — background crawl (F018, SM8635)

> Device: Realme GT 6 (RMX3851), SoC SM8635, Android 16 (API 36).
> Battery 100% (status FULL) at run time. Date: 2026-10-30.
> Source: `android/src/androidTest/.../BackgroundFieldTest.kt` (5 tests),
> worker `android/src/main/.../IndexWorker.kt`, logcat tag `BackgroundField`.

## What ran

Expedited `IndexWorker` (WorkManager, `dataSync` foreground service) over **500
small fixture files** (~1KB each, deterministic `field-%04d.*`, mixed
txt/md/jpg/png/pdf/mp3/mp4/wav) generated in the **app-private** `filesDir`
— never user folders — then deleted (`deleteRecursively`, asserted gone).
Each file is fully read (real I/O) as a stand-in for decode; per file the run
consults the `shouldPause` guards (PowerManager thermal, BatteryManager
capacity/charger, Doze, charger-only) and the stuck-job watchdog.

## Numbers

| Check | Result |
|---|---|
| 500-file crawl, wall time | **925 ms** (earlier run: 1448 ms) |
| Throughput (1 KB files, read-only) | **540 files/s** (earlier run: 345) |
| Pause reason on clean run | `OK`, backoff 0 |
| Cold-start estimate (`MeasuredCosts`, no prior rates) | `0` (honest "unknown", per spec) |
| Pause path (forced `THERMAL_SEVERE`, no phone heating) | stops before file 1, `filesDone=0`, `nextBackoffMs=30000` |
| Watchdog (300 ms test override of the 5 min prod timeout) | stuck file marked stopped, `stopped=1 done=4`, wall 311 ms — partial kept |
| Backoff schedule | 30s / 60s / 120s / 240s …, capped 30 min |
| MediaStore observer stub | registers on `MediaStore.Files`, delivers `onChange` (logcat-only, no indexing yet) |

## Caveats

- Throughput measures I/O + guard plumbing only — no BitmapFactory/Pdfium/Media3
  decode yet, so this is an upper bound on crawl speed, not an indexing rate.
- Cold-start estimate is 0 by design; the estimator only learns after `record()`
  calls (covered in `estimatesAndBackoffRecorded`).
- Watchdog/pause overrides are test seams (`stuckTimeoutMs`, `stuckName`,
  `forcePause` inputs); production uses the 5 min timeout and live signals.
- `PowerPolicy.kt` mirrors engine `Power.kt` exactly (module split: `:android`
  cannot depend on kotlin-jvm `:app`); keep in sync.
