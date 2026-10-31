<h1 align="center">Multimodal Search for Android</h1>

<p align="center">Private, on-device search across your photos, PDFs, docs, audio and video.</p>

<p align="center">
  <img src="https://img.shields.io/github/actions/workflow/status/arnabwithab/multimodal-android-search/ci.yml?branch=main" alt="build status"/>
  <img src="https://img.shields.io/badge/version-0.1.0-blue" alt="version"/>
  <img src="https://img.shields.io/badge/license-MIT-green" alt="license"/>
  <img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/LiteRT_LM-0.18.0-FF6F00" alt="LiteRT-LM"/>
  <img src="https://img.shields.io/badge/EmbeddingGemma-2_%26_300M-FFD21F?logo=huggingface&logoColor=black" alt="EmbeddingGemma"/>
</p>

## What this is

An Android-native port of DigUp: pick folders, it indexes what's *in* your files with EmbeddingGemma 2 (LiteRT-LM, on-device), then finds them from a plain description, in any language.

## How to run it

```bash
make setup   # JDK + Android SDK + hooks; safe to re-run
make test    # JVM unit tests, Linux-safe CI gate
make dev     # install debug APK on attached device + logcat
make eval    # ranking evals on Linux (evals/*.json)
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full workflow.

## Privacy

Everything stays on your phone. Nothing leaves the device except the one-time model download and the voice API you explicitly opt into (STT/TTS, both OFF by default). Voice keys live in EncryptedSharedPreferences and are never logged.

## Acknowledgements

Inspired by [DigUp](https://github.com/ARahim3/DigUp) by Abdur Rahim (MIT License).
Built on [EmbeddingGemma 2](https://huggingface.co/google/embeddinggemma-2) by Google DeepMind (Apache 2.0) and [LiteRT](https://ai.google.dev/edge/litert-lm) (Apache 2.0).
