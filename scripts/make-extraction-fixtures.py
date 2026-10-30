#!/usr/bin/env python3
"""F017 fixture generator: PDF / Latin PNG / Bengali PNG / 30s WAV tone.

Deterministic, offline, stdlib + PIL only. MP4 + HEIC are generated outside
this script (needs ffmpeg + libheif, not stdlib/PIL):

  # 30s 320x240 @30fps test clip + 440Hz tone -> clip30s.mp4
  ffmpeg -f lavfi -i testsrc=size=320x240:rate=30:duration=30 \\
      -f lavfi -i "sine=frequency=440:duration=30" \\
      -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest \\
      android/src/androidTest/assets/f017/clip30s.mp4
  # HEIC sample from the Latin PNG -> sample.heic
  heif-enc android/src/androidTest/assets/f017/latin.png \\
      -o android/src/androidTest/assets/f017/sample.heic

shortcut: no make target yet, add one wrapping this script + the two commands above when ffmpeg/heif-enc exist in CI.
Idempotent: rewrites the same bytes every run.
"""
import math
import struct
import wave

from PIL import Image, ImageDraw, ImageFont

OUT = "android/src/androidTest/assets/f017"
LATIN_FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BENGALI_FONT = "/usr/share/fonts/truetype/noto/NotoSansBengali-Bold.ttf"

LATIN_TEXT = (
    "The quick brown fox jumps over the lazy dog. "
    "Pack my box with five dozen liquor jugs. "
    "Every good boy deserves fudge. "
    "Amazingly few discotheques provide jukeboxes. "
    "Weave a circle round him thrice and close your eyes with holy dread. "
    "Extraction parity probe: this page must clear the two-hundred char rule."
)
BENGALI_TEXT = (
    "আমার সোনার বাংলা, আমি তোমায় ভালোবাসি।\n"
    "চিরদিন তোমার আকাশ, তোমার বাতাস\n"
    "আমার প্রাণে বাজায় বাঁশি।"
)


def pdf_bytes(body: str) -> bytes:
    # Minimal 1-page PDF, uncompressed content stream (axet Pdfium reads it fine).
    # Wrapped to ~80 cols with T* line breaks: one giant Tj line overflows the
    # page width and some extractors drop the tail.
    words, lines, cur = body.split(" "), [], ""
    for w in words:
        if len(cur) + 1 + len(w) > 80:
            lines.append(cur)
            cur = w
        else:
            cur = f"{cur} {w}".strip()
    lines.append(cur)
    esc = [ln.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") for ln in lines]
    tjs = f"({esc[0]}) Tj " + "".join(f"T* ({ln}) Tj " for ln in esc[1:])
    stream = f"BT /F1 12 Tf 72 720 Td 14 TL {tjs}ET"
    objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objs, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{body}\nendobj\n".encode("ascii")
    xref_at = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode("ascii")
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode("ascii")
    out += (
        f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref_at}\n%%EOF\n"
    ).encode("ascii")
    return bytes(out)


def text_png(path: str, text: str, font_path: str, size: int, img_size=(1400, 900)):
    img = Image.new("RGB", img_size, "white")
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(font_path, size)
    # Wrap to pixels: ImageDraw.multiline_text clips overflow, so wrap first.
    words, lines, cur = text.replace("\n", " ").split(" "), [], ""
    for w in words:
        trial = f"{cur} {w}".strip()
        if d.textlength(trial, font=font) > img_size[0] - 80:
            lines.append(cur)
            cur = w
        else:
            cur = trial
    lines.append(cur)
    d.multiline_text((40, 40), "\n".join(lines), fill="black", font=font, spacing=12)
    img.save(path)


def tone_wav(path: str, secs: float = 30.0, rate: int = 16000, amp: float = 0.25, freq: float = 440.0):
    n = int(secs * rate)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        frames = bytearray()
        for i in range(n):
            s = int(amp * 32767 * math.sin(2 * math.pi * freq * i / rate))
            frames += struct.pack("<h", s)
        w.writeframes(bytes(frames))


def main():
    import os

    os.makedirs(OUT, exist_ok=True)
    latin = LATIN_TEXT
    print("latin chars:", len(latin.strip()))
    pdf = pdf_bytes(latin)
    open(f"{OUT}/fixture.pdf", "wb").write(pdf)
    print("fixture.pdf bytes:", len(pdf))
    text_png(f"{OUT}/latin.png", latin, LATIN_FONT, 36)
    text_png(f"{OUT}/bengali.png", BENGALI_TEXT, BENGALI_FONT, 44)
    tone_wav(f"{OUT}/tone30s.wav")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
