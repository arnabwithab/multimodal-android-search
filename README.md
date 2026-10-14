<h1 align="center">Multimodal Search for Android</h1>

<p align="center">Private, on-device search across your photos, PDFs, docs, audio and video.</p>

<p align="center">
  <img src="https://img.shields.io/badge/build-unknown-lightgrey" alt="build status"/>
  <img src="https://img.shields.io/badge/version-0.1.0-blue" alt="version"/>
  <img src="https://img.shields.io/badge/license-MIT-green" alt="license"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Jetpack_Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/LiteRT_LM-FF6F00" alt="LiteRT-LM"/>
  <img src="https://img.shields.io/badge/Room_•_SQLite-003B57?logo=sqlite&logoColor=white" alt="Room SQLite"/>
</p>

## What this is

An Android-native port of DigUp: pick folders, it indexes what's *in* your files with EmbeddingGemma 2 (LiteRT-LM, on-device), then finds them from a plain description, in any language.
## Status

Pre-implementation. Feature tracker: `docs/features.json`.

## Acknowledgements

Inspired by [DigUp](https://github.com/ARahim3/DigUp) by Abdur Rahim (MIT License).
Built on [EmbeddingGemma 2](https://huggingface.co/google/embeddinggemma-2) by Google DeepMind (Apache 2.0) and [LiteRT](https://ai.google.dev/edge/litert-lm) (Apache 2.0).
