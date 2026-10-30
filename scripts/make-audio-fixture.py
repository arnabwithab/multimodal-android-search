#!/usr/bin/env python3
"""Deterministic 30s / 16kHz / mono / s16 WAV fixture for the 740M audio bench.

Stands in for one AudioWindows 30s window (16kHz mono s16 PCM) wrapped in a WAV
container — the SDK example passes `.wav` file bytes to `InputData.Audio`, so we
push exactly those bytes. Sine sweep + steady tone, two brief silence gaps.
No dependencies beyond the stdlib. Output path defaults to /tmp/opencode/.
"""
import math
import struct
import sys
import wave

SR = 16_000
SECS = 30
N = SR * SECS


def sample(i: int) -> float:
    t = i / SR
    # Silence gates (kept short so the window is mostly signal): 10.0-10.5s, 20.0-20.5s.
    if (10.0 <= t < 10.5) or (20.0 <= t < 20.5):
        return 0.0
    sweep = math.sin(2 * math.pi * (200 + (1800 * t / SECS)) * t)  # 200Hz -> 2kHz
    tone = 0.5 * math.sin(2 * math.pi * 440 * t)
    return 0.4 * (sweep + tone)


def main(path: str) -> None:
    frames = struct.pack("<%dh" % N, *(max(-32768, min(32767, int(sample(i) * 32767))) for i in range(N)))
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(frames)
    print(f"wrote {path} ({N} samples, {SECS}s @ {SR}Hz mono s16)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/opencode/bench-audio.wav")
