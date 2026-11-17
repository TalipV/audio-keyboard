#!/usr/bin/env python3
"""Prints an `adb shell` script that glides WORD through My Board's key centres (tools/dev.sh glide).

Centres are for the emulator at 1080x2400, 420 dpi, number row on (measured from tools/dev.sh shot, 2026-10-22).
Usage: glide.py en|ar WORD [device_png_path_for_a_mid_stroke_screencap]
"""
import sys

ROWS = {
    "en": [("qwertyuiop", 1696, 59, 107.0), ("asdfghjkl", 1846, 112, 107.0), ("zxcvbnm", 1998, 218, 107.0)],
    "ar": [("ضصثقفغعهخحج", 1696, 54, 97.2), ("شسيبلاتنمكط", 1846, 54, 97.2), ("ذءؤرىةوزظد", 1998, 54, 97.2)],
}


def main():
    lang, word = sys.argv[1], sys.argv[2]
    pos = {c: (round(x0 + i * pitch), y) for letters, y, x0, pitch in ROWS[lang] for i, c in enumerate(letters)}
    pts = [pos[c] for c in word.lower()]
    cmds = [f"input motionevent DOWN {pts[0][0]} {pts[0][1]}"]
    for (ax, ay), (bx, by) in zip(pts, pts[1:]):
        for s in range(1, 5):
            cmds.append(f"input motionevent MOVE {round(ax + (bx - ax) * s / 4)} {round(ay + (by - ay) * s / 4)}")
    if len(sys.argv) > 3:
        cmds.append(f"screencap -p {sys.argv[3]}")
    cmds.append(f"input motionevent UP {pts[-1][0]} {pts[-1][1]}")
    print("; ".join(cmds))


if __name__ == "__main__":
    main()
