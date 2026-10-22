#!/usr/bin/env python3
"""从一张源图生成 Android 桌面图标资源。

本机没有 PIL / ImageMagick，所以 PNG 的读写、缩放、遮罩全部手写
（只依赖 Python 标准库的 zlib）。这样这个脚本在任何有 python3 的地方都能跑。

用法：
    python3 tools/make_icons.py <源图.png> [选项]

生成的资源直接写进 app/src/main/res/。
"""
import struct, sys, zlib, os

# ---------------------------------------------------------------- PNG 读写

def read_png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', '不是 PNG'
    pos, idat = 8, b''
    w = h = bd = ct = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, bd, ct, comp, filt, inter = struct.unpack('>IIBBBBB', data)
            assert inter == 0, '不支持隔行扫描的 PNG'
        elif typ == b'IDAT':
            idat += data
        elif typ == b'IEND':
            break
    assert bd == 8, '只支持 8 位深'
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride = w * ch
    out = bytearray(w * h * ch)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        if f == 1:
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, ch, out


def write_png(path, w, h, ch, px):
    ct = {1: 0, 3: 2, 4: 6}[ch]
    stride = w * ch
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += px[y * stride:(y + 1) * stride]

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    open(path, 'wb').write(
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, ct, 0, 0, 0))
        + chunk(b'IDAT', zlib.compress(bytes(raw), 9))
        + chunk(b'IEND', b'')
    )


# ---------------------------------------------------------------- 图像操作

def to_rgba(w, h, ch, px):
    if ch == 4:
        return px
    out = bytearray(w * h * 4)
    for i in range(w * h):
        s = i * ch
        d = i * 4
        if ch == 3:
            out[d:d + 3] = px[s:s + 3]; out[d + 3] = 255
        elif ch == 1:
            v = px[s]; out[d] = out[d + 1] = out[d + 2] = v; out[d + 3] = 255
        elif ch == 2:
            v = px[s]; out[d] = out[d + 1] = out[d + 2] = v; out[d + 3] = px[s + 1]
    return out


def crop(w, h, px, x0, y0, cw, chh):
    out = bytearray(cw * chh * 4)
    for y in range(chh):
        s = ((y0 + y) * w + x0) * 4
        out[y * cw * 4:(y + 1) * cw * 4] = px[s:s + cw * 4]
    return out


def resize(sw, sh, src, dw, dh):
    """盒式平均缩放。源像素只读一遍，1095→192 这种大比例降采样质量足够。"""
    out = bytearray(dw * dh * 4)
    for y in range(dh):
        y0 = y * sh // dh
        y1 = max(y0 + 1, (y + 1) * sh // dh)
        for x in range(dw):
            x0 = x * sw // dw
            x1 = max(x0 + 1, (x + 1) * sw // dw)
            acc = [0, 0, 0, 0]
            n = 0
            for yy in range(y0, y1):
                base = (yy * sw + x0) * 4
                for xx in range(x1 - x0):
                    o = base + xx * 4
                    acc[0] += src[o]; acc[1] += src[o + 1]
                    acc[2] += src[o + 2]; acc[3] += src[o + 3]
                    n += 1
            d = (y * dw + x) * 4
            out[d] = acc[0] // n; out[d + 1] = acc[1] // n
            out[d + 2] = acc[2] // n; out[d + 3] = acc[3] // n
    return out


def rounded_mask(size, radius_ratio, supersample=4):
    """圆角方形遮罩，边缘做超采样抗锯齿。"""
    m = bytearray(size * size)
    r = size * radius_ratio
    for y in range(size):
        for x in range(size):
            cov = 0
            for sy in range(supersample):
                for sx in range(supersample):
                    px = x + (sx + 0.5) / supersample
                    py = y + (sy + 0.5) / supersample
                    # 到最近圆角圆心的距离
                    cx = min(max(px, r), size - r)
                    cy = min(max(py, r), size - r)
                    dx, dy = px - cx, py - cy
                    if dx * dx + dy * dy <= r * r:
                        cov += 1
            m[y * size + x] = cov * 255 // (supersample * supersample)
    return m


def apply_mask(size, px, mask):
    out = bytearray(px)
    for i in range(size * size):
        out[i * 4 + 3] = out[i * 4 + 3] * mask[i] // 255
    return out


def mirror_index(i, n):
    """把任意整数反复镜像映射到 [0, n)。用来给图层补外圈。"""
    if n <= 1:
        return 0
    period = 2 * n
    i %= period
    return i if i < n else period - 1 - i


def build_adaptive_layer(v, vside, layer_px, inset_px):
    """把内容 v 缩到安全区并居中，外圈用**镜像**补满。

    自适应图标的图层是 108dp，但只有中心 72dp 保证可见；
    外圈(每边 18dp)只会在某些启动器的视差动画里露出来。
    直接留透明会在那些情况下露出锯齿边，所以镜像补边。
    """
    inner = layer_px - 2 * inset_px
    scaled = resize(vside, vside, v, inner, inner)
    out = bytearray(layer_px * layer_px * 4)
    row = layer_px * 4
    for y in range(layer_px):
        sy = mirror_index(y - inset_px, inner)
        srow = sy * inner * 4
        drow = y * row
        for x in range(layer_px):
            sx = mirror_index(x - inset_px, inner)
            si = srow + sx * 4
            out[drow + x * 4:drow + x * 4 + 4] = scaled[si:si + 4]
    return out
