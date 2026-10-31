# Security Policy

## Supported versions

Pre-1.0: `main` only. No backports — update to latest `main`.

## Reporting a vulnerability

Open a **private security advisory** on this repo (GitHub → Security → Advisories → New draft advisory). Include steps to reproduce, impact, and affected commit. We aim to acknowledge within 72h.

## What counts

- Voice API key leakage (logged, committed, or exposed on-device).
- PII or file contents in logs (paths beyond basename in release).
- Overbroad permissions (anything past what MediaStore/SAF/voice needs).
- Search index or database exposure via backup, export, or world-readable files.

## Out of scope

- Upstream LiteRT / EmbeddingGemma vulnerabilities — report those to Google upstream.
- Physical device compromise (unlocked bootloader, rooted device with granted access).

## Privacy posture

No telemetry by design. Nothing leaves the device except model download and user-enabled voice API. See README `## Privacy`.
