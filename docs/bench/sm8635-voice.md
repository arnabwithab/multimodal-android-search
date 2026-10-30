# Voice E2E — Realme GT 6 (SM8635)

F019 device wiring: `VoiceService` (`android/.../VoiceService.kt`) + `VoiceE2ETest`
(5 instrumented tests, all PASS on-device, also re-run with airplane mode on).
Mic-live recognition is NOT asserted automatically — needs a human voice.

## Manual mic checklist (human, ~2 min)

1. [ ] Tap mic, say "zebra in a video" → search bar shows `heard: zebra in a video [..]`, results list appears. Heard: _______
2. [ ] Repeat in a non-English language (e.g. Bengali) → transcript appears, results still list. Heard: _______
3. [ ] Heavy-accent/long phrase → on-device result acceptable, or note it needs the cloud toggle. Heard: _______
4. [ ] Tap speaker on one hit → excerpt reads aloud to completion, no cut-off. Heard OK: _______
5. [ ] Airplane mode ON → mic falls back to typed query, no crash; mode restored after. Fallback OK: _______

Results: _blank — awaiting human pass_
