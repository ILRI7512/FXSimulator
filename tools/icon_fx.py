#!/usr/bin/env python3
"""生成 FX模拟器 的应用图标：深色底 + 三根 K 线 + 几何拼出的 FX 字样。

刻意不依赖字体：F 由三个矩形组成，X 是两条带厚度的斜杠。
这样在任何机器上渲染结果都完全一致，也不会因为缺字体而出方框。

输出：
  res/drawable/ic_launcher_foreground.xml   自适应图标前景（矢量，任意尺寸都清晰）
  res/mipmap-*/ic_launcher{,_round}.png     传统图标兜底（API<26 / 部分商店直接取这张）
  商店 512×512
"""
import os, sys, zlib, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_icons import write_png, resize, rounded_mask, apply_mask

# ---------------------------------------------------------------- 设计参数
# 画布坐标是 108×108（自适应图标的图层尺寸）。
# 只有中心 72×72 保证可见，所以所有内容都画在 [18,90] 之内。
BG      = (0x0B, 0x0E, 0x14, 0xFF)   # 与 App 主色一致的深蓝黑
GOLD    = (0xF0, 0xB9, 0x0B, 0xFF)
TEAL    = (0x26, 0xA6, 0x9A, 0xFF)
RED     = (0xEF, 0x53, 0x50, 0xFF)


def rect(x0, y0, x1, y1):
    return ('rect', (x0, y0, x1, y1))


def thick_line(ax, ay, bx, by, w):
    """把一条有厚度的线段表示成四边形（用作矢量路径 / 多边形填充）。"""
    dx, dy = bx - ax, by - ay
    L = (dx * dx + dy * dy) ** 0.5
    nx, ny = -dy / L * w / 2, dx / L * w / 2
    return ('quad', ((ax + nx, ay + ny), (bx + nx, by + ny), (bx - nx, by - ny), (ax - nx, ay - ny)))


# ---- 纯文字图标：只有一个元素，就是 FX。没有 K 线、没有装饰。----
CANDLES = []

# 以画布中心 (54,54) 为基准等比缩到 0.76。
# 三轮迭代的结论：占满 -> 0.89 -> 0.76。
# 0.76 时四角到中心约 25.6（含笔宽约 28.5），而启动器遮罩圆的半径是 36，
# 留出约 7.5 个单位的余量（约半径的 21%），任何遮罩形状都不会碰到笔画。
F = [
    rect(32.72, 41.08, 38.42, 66.92),   # 竖
    rect(32.72, 41.08, 48.68, 46.78),   # 上横
    rect(32.72, 52.48, 46.02, 58.18),   # 中横
]
X = [
    thick_line(56.28, 41.08, 74.52, 66.92, 5.7),
    thick_line(74.52, 41.08, 56.28, 66.92, 5.7),
]

SHAPES = []
for wick, body in CANDLES:
    SHAPES.append(([wick], TEAL))
    SHAPES.append(([body], TEAL))
SHAPES.append((F, GOLD))
SHAPES.append((X, GOLD))


# ---------------------------------------------------------------- 光栅化
def render(scale, bg=None):
    W = H = int(round(108 * scale))
    cv = bytearray(W * H * 4)
    if bg:
        cv[:] = bytes(bg) * (W * H)

    def put(x, y, rgba):
        if 0 <= x < W and 0 <= y < H:
            i = (y * W + x) * 4
            cv[i:i + 4] = bytes(rgba)

    for shapes, color in SHAPES:
        for kind, g in shapes:
            if kind == 'rect':
                x0, y0, x1, y1 = [v * scale for v in g]
                for y in range(max(0, int(y0)), min(H, int(y1 + 0.5))):
                    for x in range(max(0, int(x0)), min(W, int(x1 + 0.5))):
                        put(x, y, color)
            else:
                pts = [(x * scale, y * scale) for x, y in g]
                ys = [p[1] for p in pts]
                for y in range(max(0, int(min(ys))), min(H, int(max(ys)) + 1)):
                    yc = y + 0.5
                    xs = []
                    n = len(pts)
                    for i in range(n):
                        ax, ay = pts[i]
                        bx, by = pts[(i + 1) % n]
                        if (ay <= yc < by) or (by <= yc < ay):
                            xs.append(ax + (yc - ay) / (by - ay) * (bx - ax))
                    xs.sort()
                    for i in range(0, len(xs) - 1, 2):
                        for x in range(max(0, int(xs[i])), min(W, int(xs[i + 1]) + 1)):
                            put(x, y, color)
    return W, H, cv


# ---------------------------------------------------------------- 矢量前景
def q(pts):
    return 'M' + ' L'.join(f'{x:g},{y:g}' for x, y in pts) + ' z'


def vec_path(shapes):
    d = []
    for kind, g in shapes:
        if kind == 'rect':
            x0, y0, x1, y1 = g
            d.append(f'M{x0:g},{y0:g} h{x1 - x0:g} v{y1 - y0:g} h{-(x1 - x0):g} z')
        else:
            d.append(q(g))
    return ' '.join(d)


def hexcolor(c):
    return '#%02X%02X%02X' % c[:3]


def write_foreground_vector(path):
    body = '\n'.join(
        f'    <path\n        android:fillColor="{hexcolor(color)}"\n        android:pathData="{vec_path(shapes)}" />'
        for shapes, color in SHAPES
    )
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- FX模拟器 图标前景：三根 K 线 + 几何拼出的 FX。
     全部内容都画在中心 72×72 的安全区内（画布 108×108），
     所以无论启动器用圆形还是圆角方形遮罩，都不会切到任何笔画。
     刻意不使用字体：F 是三个矩形，X 是两条斜杠，任何机器上渲染结果一致。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}
</vector>
'''
    open(path, 'w', encoding='utf-8').write(xml)
    return xml


if __name__ == '__main__':
    RES = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/res'
    SS = 8                                  # 8 倍超采样，缩小时自然得到抗锯齿

    write_foreground_vector(f'{RES}/drawable/ic_launcher_foreground.xml')
    print('✓ drawable/ic_launcher_foreground.xml（矢量前景）')

    # 传统图标：把深色底一起烘进去（这些图不会被系统套底色）
    W, H, cv = render(SS, bg=BG)
    # 传统图标用「可见区」= 画布中心 72×72（即 18..90），保证与自适应版本一致
    inset = int(18 * SS)
    side = int(72 * SS)
    vis = bytearray(side * side * 4)
    for y in range(side):
        s = ((inset + y) * W + inset) * 4
        vis[y * side * 4:(y + 1) * side * 4] = cv[s:s + side * 4]

    for dens, sz in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
        d = f'{RES}/mipmap-{dens}'
        os.makedirs(d, exist_ok=True)
        small = resize(side, side, vis, sz, sz)
        write_png(f'{d}/ic_launcher.png', sz, sz, 4, small)
        write_png(f'{d}/ic_launcher_round.png', sz, sz, 4, apply_mask(sz, small, rounded_mask(sz, 0.5)))
    print('✓ mipmap-{mdpi..xxxhdpi}/ic_launcher{,_round}.png')

    write_png('/tmp/store_icon_512.png', 512, 512, 4, resize(side, side, vis, 512, 512))
    print('✓ /tmp/store_icon_512.png')

    # 预览：三档可见尺寸 + 三种遮罩，模拟真实启动器
    PV, gap = 200, 14
    cw = PV * 3 + gap * 2
    canvas = bytearray([0x1E, 0x21, 0x28, 0xFF] * (cw * PV))
    for i, ratio in enumerate([0.5, 0.30, 0.0]):
        masked = apply_mask(PV, resize(side, side, vis, PV, PV), rounded_mask(PV, ratio))
        ox = i * (PV + gap)
        for y in range(PV):
            dst = (y * cw + ox) * 4
            canvas[dst:dst + PV * 4] = masked[y * PV * 4:(y + 1) * PV * 4]
    write_png('/tmp/preview_fx.png', cw, PV, 4, canvas)
    print('✓ /tmp/preview_fx.png（左圆 中圆角方 右方）')

    # 第二张：真实桌面尺寸（48/72/96），用来判断小图标下是否还认得出
    sizes = [48, 72, 96]
    tail = [0x1E, 0x21, 0x28, 0xFF]
    strip_w = sum(sizes) + 16 * (len(sizes) + 1)
    strip_h = 96 + 32
    strip = bytearray(tail * (strip_w * strip_h))
    ox = 16
    for sz in sizes:
        img = resize(side, side, vis, sz, sz)
        for y in range(sz):
            dst = ((16 + y) * strip_w + ox) * 4
            strip[dst:dst + sz * 4] = img[y * sz * 4:(y + 1) * sz * 4]
        ox += sz + 16
    write_png('/tmp/preview_fx_small.png', strip_w, strip_h, 4, strip)
    print('✓ /tmp/preview_fx_small.png（48px / 72px / 96px 实际尺寸）')

    # 小尺寸检查：桌面图标实际就 48~96px
    small_row = bytearray([0x1E, 0x21, 0x28, 0xFF] * (48 * 4 + 96 * 2 + gap * 3))
    print('✓ 完成')
