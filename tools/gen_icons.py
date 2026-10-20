#!/usr/bin/env python3
"""生成 Afterglow 几何镜头启动图标（纯标准库，不依赖 Pillow）。

输出：app/src/main/res/mipmap-*/ic_launcher.png
"""
import os
import math
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

SS = 4  # 超采样倍数，用于抗锯齿


def rounded_rect(x, y, x0, y0, x1, y1, r):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def render(size):
    """返回 size*size 的 RGBA 像素列表（每像素 (r,g,b,a)）。"""
    w = size * SS
    bg = (18, 27, 39)       # #121B27
    ring = (243, 215, 172)  # #F3D7AC
    lens = (239, 178, 102)  # #EFB266

    pad = w * 0.06
    radius = w * 0.22
    bx0, by0, bx1, by1 = pad, pad, w - pad, w - pad

    lens_cx, lens_cy = w * 0.50, w * 0.52
    # An open lens ring and a small warm light: legible even at 48 px.
    outer, inner = w * 0.255, w * 0.216
    dot_x, dot_y, dot_r = w * 0.682, w * 0.338, w * 0.045

    acc = [[[0, 0, 0, 0] for _ in range(size)] for _ in range(size)]

    for py in range(w):
        for px in range(w):
            r = g = b = a = 0
            if rounded_rect(px, py, bx0, by0, bx1, by1, radius):
                r, g, b, a = bg[0], bg[1], bg[2], 255
                d2 = (px - lens_cx) ** 2 + (py - lens_cy) ** 2
                angle = math.degrees(math.atan2(py - lens_cy, px - lens_cx))
                if inner ** 2 <= d2 <= outer ** 2 and not -68 < angle < -22:
                    r, g, b = ring
                if d2 <= (w * 0.126) ** 2:
                    r, g, b = lens
                if (px - w * 0.465) ** 2 + (py - w * 0.481) ** 2 <= (w * 0.026) ** 2:
                    r, g, b = (255, 243, 220)
                if (px - dot_x) ** 2 + (py - dot_y) ** 2 <= dot_r ** 2:
                    r, g, b = lens
            ox, oy = px // SS, py // SS
            cell = acc[oy][ox]
            cell[0] += r
            cell[1] += g
            cell[2] += b
            cell[3] += a

    n = SS * SS
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            c = acc[y][x]
            row += bytes((c[0] // n, c[1] // n, c[2] // n, c[3] // n))
        rows.append(bytes(row))
    return rows


def write_png(path, size, rows):
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def main():
    for folder, size in SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), size, render(size))
        print("wrote", os.path.join(folder, "ic_launcher.png"), size)


if __name__ == "__main__":
    main()
