#!/usr/bin/env python3
"""生成界面用的小图标（纯标准库，不依赖 Pillow）。

为什么不用 VectorDrawable：本项目的 minSdk 是 19，
系统在 API 19/20 上无法解析 <vector>，会直接 inflate 失败。
用多密度 PNG 一次搞定，零运行时依赖。

输出：
  app/src/main/res/drawable-*/ic_arrow_down.png   下拉箭头（Material arrow_drop_down 形状）
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_icons import write_png  # noqa: E402  复用 PNG 编码器

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

SS = 4  # 超采样倍数

# dp -> 各密度像素
DENSITIES = {
    "drawable-mdpi": 1,
    "drawable-hdpi": 1.5,
    "drawable-xhdpi": 2,
    "drawable-xxhdpi": 3,
    "drawable-xxxhdpi": 4,
}

COLOR = (0x5F, 0x63, 0x68)  # on_surface_variant


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])

    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    has_neg = d1 < 0 or d2 < 0 or d3 < 0
    has_pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (has_neg and has_pos)


def render(size):
    """24dp 画布上的下三角，按 size 缩放。"""
    w = size * SS
    scale = w / 24.0
    a = (7.0 * scale, 9.5 * scale)
    b = (17.0 * scale, 9.5 * scale)
    c = (12.0 * scale, 15.0 * scale)

    acc = [[[0, 0, 0, 0] for _ in range(size)] for _ in range(size)]
    for py in range(w):
        for px in range(w):
            if in_triangle(px + 0.5, py + 0.5, a, b, c):
                cell = acc[py // SS][px // SS]
                cell[0] += COLOR[0]
                cell[1] += COLOR[1]
                cell[2] += COLOR[2]
                cell[3] += 255

    n = SS * SS
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            c2 = acc[y][x]
            row += bytes((c2[0] // n, c2[1] // n, c2[2] // n, c2[3] // n))
        rows.append(bytes(row))
    return rows


def main():
    for folder, mult in DENSITIES.items():
        size = int(round(24 * mult))
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "ic_arrow_down.png")
        write_png(path, size, render(size))
        print("wrote", os.path.join(folder, "ic_arrow_down.png"), size)


if __name__ == "__main__":
    main()
