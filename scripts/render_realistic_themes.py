"""Go boards and stones painted after real reference photos.

Every pixel is computed from what the photos show: the wood is built ring by ring (earlywood that
darkens into a thin latewood line, then an abrupt start of the next year), bamboo strip by strip
with its fibre bundles and nodes, and each stone is lit like a real object in a room (a window
softbox at the upper left, the ceiling, the board reflected in its rim) with the surface of the real
material (satin slate, polished clamshell with growth lines, frosted Yunzi, frosted glass).

Usage: python3 -I scripts/render_realistic_themes.py <out_dir> [set ...]
Writes <out>/<set>/board.png (2048 px, shown 1:1 by the app), black.png, white.png (+ variants)
and <out>/preview_<set>.png, a 1000 px board drawn the way the app draws it.
"""
import math
import os
import sys
import time

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ----------------------------------------------------------------------------- colour


def srgb2lin(c):
    c = np.asarray(c, np.float32) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4).astype(np.float32)


def lin2srgb(x):
    x = np.clip(x, 0, None)
    s = np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(x, 1 / 2.4) - 0.055)
    return np.clip(s * 255.0, 0, 255)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


# ----------------------------------------------------------------------------- noise


def noise2(h, w, cell_y, cell_x, seed):
    """Smooth gaussian noise with features about cell_y x cell_x pixels (bicubic grid)."""
    rng = np.random.default_rng(seed)
    gh = int(math.ceil(h / cell_y)) + 4
    gw = int(math.ceil(w / cell_x)) + 4
    g = rng.standard_normal((gh, gw)).astype(np.float32)
    big = np.asarray(Image.fromarray(g).resize((int(gw * cell_x), int(gh * cell_y)), Image.BICUBIC))
    oy, ox = int(cell_y * 2), int(cell_x * 2)
    return big[oy:oy + h, ox:ox + w]


def fbm2(h, w, cell_y, cell_x, octaves, seed, gain=0.5):
    out = np.zeros((h, w), np.float32)
    amp = 1.0
    for o in range(octaves):
        cy, cx = max(1.0, cell_y / 2 ** o), max(1.0, cell_x / 2 ** o)
        out += amp * noise2(h, w, cy, cx, seed * 31 + o)
        amp *= gain
    return out / (out.std() + 1e-6)


def noise1(n, cell, seed):
    rng = np.random.default_rng(seed)
    k = int(math.ceil(n / cell)) + 4
    knots = rng.standard_normal(k)
    xs = np.arange(n) / cell + 2
    i = np.floor(xs).astype(int)
    t = xs - i
    # cubic (catmull-rom) interpolation
    p0, p1, p2, p3 = knots[i - 1], knots[i], knots[i + 1], knots[np.minimum(i + 2, k - 1)]
    return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                  + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3)


def fbm1(n, cell, octaves, seed):
    out = np.zeros(n)
    amp = 1.0
    for o in range(octaves):
        out += amp * noise1(n, max(1.0, cell / 2 ** o), seed * 17 + o)
        amp *= 0.5
    return out / (out.std() + 1e-9)


def downsample(a, f):
    h, w = a.shape[0] // f, a.shape[1] // f
    a = a[:h * f, :w * f]
    if a.ndim == 2:
        return a.reshape(h, f, w, f).mean(axis=(1, 3))
    return a.reshape(h, f, w, f, a.shape[2]).mean(axis=(1, 3))


# ----------------------------------------------------------------------------- wood


def ring_bounds(length, mean, sigma, seed, slow_cell=40.0):
    """Annual ring boundaries along one axis: widths vary year to year and in slow growth periods."""
    rng = np.random.default_rng(seed)
    n = int(length / mean * 1.6) + 50
    slow = fbm1(n, slow_cell, 3, seed + 1)  # decades of good and poor growth
    year = rng.standard_normal(n)
    widths = mean * np.exp(sigma * (0.75 * slow + 0.45 * year) - 0.5 * sigma ** 2)
    b = np.concatenate([[-rng.uniform(0, mean * 2)], np.cumsum(widths)])
    b = b - mean * 2
    return b


def rings_field(xw, bounds):
    """Ring index and position t in [0,1) inside the ring for warped coordinates xw."""
    i = np.clip(np.searchsorted(bounds, xw, side="right") - 1, 0, len(bounds) - 2)
    lo, hi = bounds[i], bounds[i + 1]
    t = np.clip((xw - lo) / np.maximum(hi - lo, 1e-6), 0, 1)
    return i, t, hi - lo


def wood_masame(size, spec, ss=3):
    """Quarter-sawn wood: straight annual lines running top to bottom.

    Each year is drawn as a soft darker dip at the ring boundary (polished wood shows grain as a
    change of colour, not relief) on top of a gentle earlywood-to-latewood darkening, plus soft
    silky ribbons a fraction of a square wide."""
    H = W = size * ss
    rng = np.random.default_rng(spec["seed"])
    y = np.arange(H, dtype=np.float32)[:, None]
    x = np.arange(W, dtype=np.float32)[None, :]
    px = ss
    sway = (fbm1(H, 700 * px, 3, spec["seed"] + 2) * spec["sway"] * px).astype(np.float32)[:, None]
    wob = fbm2(H, W, spec.get("wob_y", 160) * px, spec.get("wob_x", 70) * px, spec.get("wob_oct", 3), spec["seed"] + 3) * spec["wobble"] * px
    wig = noise2(H, W, 45 * px, 35 * px, spec["seed"] + 15) * spec.get("wiggle", 0.0) * px
    xw = x + sway + wob + wig + spec["slant"] * y
    b = ring_bounds(W + 400 * px, spec["ring_px"] * px, spec["ring_sigma"], spec["seed"] + 4,
                    slow_cell=spec.get("slow_cell", 40.0))
    i, t, width = rings_field(xw + 200 * px, b)
    n = len(b)
    strength = np.exp(rng.normal(0, spec["late_var"], n)).astype(np.float32)
    strength *= 1 + spec.get("late_slow", 0.0) * fbm1(n, 12, 2, spec["seed"] + 9).astype(np.float32)
    strength = np.clip(strength, 0.2, 3.0)
    if spec.get("strong_every", 0):
        strong = rng.random(n) < 1.0 / spec["strong_every"]
        strength = np.where(strong, strength * spec.get("strong_gain", 2.0), strength)
    dist = np.minimum(t, 1 - t) * width  # supersampled px to the nearest ring boundary
    lw = spec["line_w"] * px
    dip = np.exp(-(dist / lw) ** 2) * strength[i]
    if spec.get("fade", 0) > 0:  # each line fades in and out slowly along its length
        fade = noise2(H, n, spec["fade_px"] * px, 1.0, spec["seed"] + 13)
        fade = 1 - spec["fade"] * (0.5 + 0.5 * np.tanh(fade))
        dip = dip * fade[np.arange(H)[:, None], i]
    grad = smoothstep(0.35, 1.0, t) * spec.get("year_grad", 0.25)
    d = dip + grad
    early = srgb2lin(spec["early"])
    late = srgb2lin(spec["late"])
    col = early + (late - early) * d[..., None] * spec["late_contrast"]
    broad = fbm2(H, W, 900 * px, 260 * px, 3, spec["seed"] + 5)
    col *= (1 + spec["broad"] * broad)[..., None]
    if spec.get("hue_bands", 0) > 0:  # lemon-gold to soft orange, in bands along the grain
        hb = fbm2(H, W, 1200 * px, 260 * px, 2, spec["seed"] + 16)
        col *= (1 + spec["hue_bands"] * hb[..., None] * np.array([0.3, -0.25, -1.0], np.float32))
    ribbons = fbm2(H, W, 420 * px, spec.get("ribbon_px", 24) * px, 2, spec["seed"] + 12)
    col *= (1 + spec.get("ribbons", 0.0) * ribbons)[..., None]
    fib = fbm2(H, W, 26 * px, 1.0 * px, 2, spec["seed"] + 6)
    col *= (1 + spec["fibre"] * fib)[..., None]
    if spec.get("pores", 0) > 0:  # non-directional micro texture of the wood surface
        col *= (1 + spec["pores"] * noise2(H, W, 1.1 * px, 1.1 * px, spec["seed"] + 14))[..., None]
    if spec.get("rays", 0) > 0:
        fl = noise2(H, W, 1.0 * px, 5.0 * px, spec["seed"] + 7)
        fl = np.clip(fl - 2.2, 0, None)
        col *= (1 + spec["rays"] * fl)[..., None]
    col = add_scratches(col, spec.get("scratches", 0), spec["seed"] + 10, px)
    col = add_mineral_streaks(col, spec.get("streaks", 0), spec["seed"] + 11, px, spec)
    return downsample(col, ss)


def add_scratches(col, count, seed, px):
    if count <= 0:
        return col
    H, W = col.shape[:2]
    rng = np.random.default_rng(seed)
    layer = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(layer)
    for _ in range(count):
        x0, y0 = rng.uniform(0, W), rng.uniform(0, H)
        ang = rng.uniform(0, math.pi)
        length = rng.uniform(30, 160) * px
        bend = rng.normal(0, 0.25)
        pts = []
        for k in range(9):
            a = ang + bend * (k / 8 - 0.5)
            pts.append((x0 + math.cos(a) * length * k / 8, y0 + math.sin(a) * length * k / 8))
        d.line(pts, fill=int(rng.uniform(80, 200)), width=max(1, int(px * 0.8)))
    s = np.asarray(layer.filter(ImageFilter.GaussianBlur(0.6 * px)), np.float32) / 255
    return col * (1 + 0.035 * s)[..., None]


def add_mineral_streaks(col, count, seed, px, spec):
    if count <= 0:
        return col
    H, W = col.shape[:2]
    rng = np.random.default_rng(seed)
    y = np.arange(H, dtype=np.float32)[:, None]
    x = np.arange(W, dtype=np.float32)[None, :]
    out = col
    for _ in range(count):
        cx = rng.uniform(0.1, 0.9) * W
        wid = rng.uniform(3, 9) * px
        y0, y1 = sorted(rng.uniform(0, H, 2))
        sway = fbm1(H, 300 * px, 2, int(cx)).astype(np.float32)[:, None] * 3 * px
        across = np.exp(-(((x - cx - sway) / wid) ** 2))
        along = smoothstep(y0 - 200 * px, y0, y) * (1 - smoothstep(y1, y1 + 200 * px, y))
        m = (across * along)[..., None]
        tint = srgb2lin(spec["late"]) / np.maximum(srgb2lin(spec["early"]), 1e-4)
        out = out * (1 - m * spec.get("streak_strength", 0.12) * (1 - tint))
    return out


def bamboo(size, spec, ss=2):
    """Carbonised laminated bamboo: pressed strips of constant width, each with its own tone and
    crisp parallel fibres, glued edge to edge; nodes are fibrous bands confined to one strip."""
    H = W = size * ss
    px = ss
    rng = np.random.default_rng(spec["seed"])
    y = np.arange(H, dtype=np.float32)[:, None]
    x = np.arange(W, dtype=np.float32)[None, :]
    edges = [0.0]
    while edges[-1] < W:
        edges.append(edges[-1] + round(rng.uniform(*spec["strip_px"])) * px)
    edges = np.asarray(edges)
    ns = len(edges)
    sid = np.clip(np.searchsorted(edges, x[0], side="right") - 1, 0, ns - 2)
    tone = rng.normal(0, spec["strip_tone"], ns).astype(np.float32)
    honey = (rng.random(ns) < spec.get("honey_p", 0.0)).astype(np.float32) * spec.get("honey", 0.0)
    col = np.broadcast_to(srgb2lin(spec["base"]), (H, W, 3)).astype(np.float32).copy()
    col *= (1 + tone[sid] - honey[sid])[None, :, None]
    # slow darker carbonisation zones that follow each strip
    zone = np.zeros((H, W), np.float32)
    for k in range(ns - 1):
        x0, x1 = int(edges[k]), int(min(W, edges[k + 1]))
        if x1 <= x0:
            continue
        zone[:, x0:x1] = fbm1(H, 500 * px, 2, spec["seed"] * 7 + k).astype(np.float32)[:, None]
    col *= (1 + spec["zones"] * zone)[..., None]
    # fibres: crisp parallel lines 1-2 px wide every 3-6 px inside each strip, mostly darker
    # (vascular bundles) with some paler ones, fading slowly along their length
    pos, fx, fs = 0.0, [], []
    while pos < W:
        pos += rng.uniform(*spec["fibre_gap"]) * px
        fx.append(pos)
        fs.append(rng.choice([-1.0, 1.0], p=[0.75, 0.25]) * rng.uniform(0.4, 1.0))
    fx, fs = np.asarray(fx, np.float32), np.asarray(fs, np.float32)
    k = np.clip(np.searchsorted(fx, x[0]), 1, len(fx) - 1)
    near = np.where(np.abs(x[0] - fx[k - 1]) < np.abs(x[0] - fx[k]), k - 1, k)
    d_ = np.abs(x[0] - fx[near])
    prof = np.exp(-(d_ / (spec.get("fibre_w", 0.65) * px)) ** 2) * fs[near]
    seg_n = noise2(H, len(fx), spec.get("fibre_seg", 45) * px, 1.0, spec["seed"] + 23)
    along = np.clip(0.45 + 0.75 * seg_n, 0, 1)
    fibre = prof[None, :] * along[np.arange(H)[:, None], near[None, :]]
    dark_w = np.array([1.0, 1.15, 1.8], np.float32)  # darks lose blue fastest: deeper amber
    pale_w = np.array([0.8, 1.1, 0.7], np.float32)   # highlights lean yellow
    f3 = fibre[..., None]
    col *= 1 + np.where(f3 < 0, spec["fibre"] * f3 * dark_w, spec["fibre"] * 0.6 * f3 * pale_w)
    soft = noise2(H, W, 120 * px, 2.5 * px, spec["seed"] + 21)
    col *= (1 + spec.get("streaks", 0.012) * soft)[..., None]  # paler and deeper streaks a few px wide
    # glue line: a crisp, very slightly darker 1 px edge between strips
    lo, hi = edges[sid], edges[np.minimum(sid + 1, ns - 1)]
    at_edge = (x[0] - lo) < px
    col[:, at_edge] *= 1 - spec["seam"]
    # nodes: a patch confined to one strip, slightly deeper caramel, mottled with short vertical
    # flecks, some pale cream-gold and some saturated amber; staggered between neighbouring strips
    prev_ys = []
    for k in range(ns - 1):
        x0, x1 = int(edges[k]), int(min(W, edges[k + 1]))
        if x1 - x0 < 4:
            continue
        yy = rng.uniform(0, spec["node_gap"][1]) * px
        for _ in range(20):  # keep away from the neighbour's nodes
            if all(abs(yy - p) > 40 * px for p in prev_ys):
                break
            yy = rng.uniform(0, spec["node_gap"][1]) * px
        ys_here = []
        while yy < H:
            ys_here.append(yy)
            hgt = rng.uniform(*spec["node_h"]) * px
            yi0, yi1 = int(max(0, yy - hgt)), int(min(H, yy + hgt))
            if yi1 > yi0:
                hh, ww = yi1 - yi0, x1 - x0
                ys = np.arange(yi0, yi1, dtype=np.float32)[:, None]
                dy = (ys - yy) / (hgt / 2.0)
                patch = np.exp(-dy ** 2 * 1.5) * (0.8 + 0.2 * noise2(hh, ww, 8 * px, 10 * px, int(abs(yy)) % 9973 + k))
                seed_ = int(abs(yy)) % 7919 + 3 * k
                fl = noise2(hh, ww, spec.get("fleck_h", 5.0) * px, spec.get("fleck_w", 1.2) * px, seed_)
                pale = np.clip(fl - 0.5, 0, None) * patch
                darkf = np.clip(-fl - 0.5, 0, None) * patch
                seg = col[yi0:yi1, x0:x1]
                seg *= 1 - spec["node_halo"] * patch[..., None] * np.array([0.6, 0.8, 1.6], np.float32)
                seg *= 1 + spec["node_pale"] * pale[..., None] * np.array([0.9, 1.1, 0.8], np.float32)
                seg *= 1 - spec["node_dark"] * darkf[..., None] * np.array([0.8, 1.0, 1.9], np.float32)
                col[yi0:yi1, x0:x1] = seg
            yy += rng.uniform(*spec["node_gap"]) * px
        prev_ys = ys_here
    return downsample(col, ss)


def tileable(make, size, band=128):
    """Render size+band px and cross-fade the last band onto what precedes the tile start, so the
    texture wraps seamlessly (only boards wider than size-band px ever show the blend)."""
    big = make(size + band)
    out = big[band:band + size, band:band + size].copy()
    w = np.linspace(0, 1, band, dtype=np.float32)
    # columns: last band blends toward the pixels just left of the tile start
    left = big[band:band + size, 0:band]
    out[:, size - band:] = out[:, size - band:] * (1 - w)[None, :, None] + left * w[None, :, None]
    top = np.concatenate([big[0:band, band:band + size - band], big[0:band, 0:band]], axis=1)
    top = big[0:band, band:band + size].copy()
    top[:, size - band:] = top[:, size - band:] * (1 - w)[None, :, None] + big[0:band, 0:band] * w[None, :, None]
    out[size - band:, :] = out[size - band:, :] * (1 - w)[:, None, None] + top * w[:, None, None]
    return out


def finish(col_lin, vignette=0.0):
    h, w = col_lin.shape[:2]
    if vignette:
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        r = np.sqrt(((xx - w * 0.45) / w) ** 2 + ((yy - h * 0.42) / h) ** 2)
        col_lin = col_lin * (1 - vignette * r ** 2)[..., None]
    assert np.isfinite(col_lin).all(), "non-finite pixels"
    return Image.fromarray(lin2srgb(col_lin).astype(np.uint8), "RGB")


# ----------------------------------------------------------------------------- stones

KEY = np.array([-0.40, -0.50, 0.766], np.float32)
KEY /= np.linalg.norm(KEY)


def _basis(n):
    """Two unit vectors perpendicular to each n (n: (...,3))."""
    up = np.where(np.abs(n[..., 2:3]) < 0.9, np.array([0, 0, 1.0], np.float32),
                  np.array([1.0, 0, 0], np.float32))
    t1 = np.cross(n, up)
    t1 /= np.linalg.norm(t1, axis=-1, keepdims=True) + 1e-9
    t2 = np.cross(n, t1)
    return t1, t2


K_T1, K_T2 = _basis(KEY[None, :])
K_T1, K_T2 = K_T1[0], K_T2[0]
FILL = np.array([0.62, -0.22, 0.75], np.float32)  # a second, dimmer window on the right
FILL /= np.linalg.norm(FILL)
F_T1, F_T2 = _basis(FILL[None, :])
F_T1, F_T2 = F_T1[0], F_T2[0]


def _box(d, axis, t1, t2, half_a, half_b, soft=0.06):
    dk = np.sum(d * axis, -1)
    a = np.arctan2(np.sum(d * t1, -1), np.maximum(dk, 1e-4))
    b = np.arctan2(np.sum(d * t2, -1), np.maximum(dk, 1e-4))
    return (smoothstep(half_a + soft, half_a - soft, np.abs(a))
            * smoothstep(half_b + soft, half_b - soft, np.abs(b)) * (dk > 0))


def env(d, board_lin, light):
    """Radiance of the room seen in direction d (...,3): window softbox, ceiling, the board."""
    z = d[..., 2]
    # two soft windows: the main one at the upper left, a dimmer one at the right
    box = _box(d, KEY, K_T1, K_T2, light["box_a"], light["box_b"]) \
        + light["fill_box"] * _box(d, FILL, F_T1, F_T2, light["box_a"] * 0.7, light["box_b"] * 1.3)
    hx = d[..., 0] / (np.sqrt(d[..., 0] ** 2 + d[..., 1] ** 2) + 1e-6)
    hy = d[..., 1] / (np.sqrt(d[..., 0] ** 2 + d[..., 1] ** 2) + 1e-6)
    kx, ky = KEY[0] / np.hypot(KEY[0], KEY[1]), KEY[1] / np.hypot(KEY[0], KEY[1])
    toward = hx * kx + hy * ky
    sky = light["sky"] * (0.62 + 0.38 * np.clip(z, 0, 1)) * (0.80 + 0.20 * toward)
    # windows and lit walls low on the light's side of the room
    horizon = light.get("horizon", 0.0) * (1 - smoothstep(0.0, 0.45, z)) * np.clip(toward, 0, 1) ** 1.5
    above = sky + light["box"] * box + horizon
    # below the horizon: the board, darker straight down where the stone shades it
    # the stone's shadow lies on the side away from the light; the lit side of the board stays bright
    steep = smoothstep(-0.97, -0.08, z)
    shaded = smoothstep(-0.2, 0.6, -toward)
    occl = shaded * (0.10 + 0.90 * steep) + (1 - shaded) * (0.92 + 0.08 * steep)
    lit_board = (0.82 + 0.18 * toward)[..., None] * board_lin * light["board"]
    below = lit_board * occl[..., None]
    w = smoothstep(-0.02, 0.02, z)[..., None]
    return above[..., None] * w + below * (1 - w)


def stone_normals(S, hc, fillet):
    c = (S - 1) / 2.0
    Rpx = c * 0.985
    y, x = np.mgrid[0:S, 0:S].astype(np.float32)
    u, v = (x - c) / Rpx, (y - c) / Rpx
    r = np.sqrt(u * u + v * v)
    Rc = (1 + hc * hc) / (2 * hc)
    e0 = 1 - fillet
    th0 = math.asin(min(e0 / Rc, 0.999))
    th_cap = np.arcsin(np.clip(r / Rc, 0, 0.999))
    s0 = math.sin(th0)
    sphi = s0 + np.clip((r - e0) / fillet, 0, 1) * (1 - s0)
    th_fil = np.arcsin(np.clip(sphi, 0, 0.9995))
    th = np.where(r <= e0, th_cap, th_fil)
    ru = np.where(r > 1e-6, u / np.maximum(r, 1e-6), 0)
    rv = np.where(r > 1e-6, v / np.maximum(r, 1e-6), 0)
    n = np.stack([np.sin(th) * ru, np.sin(th) * rv, np.cos(th)], -1).astype(np.float32)
    return u, v, r, n, Rpx


GAUSS = None


def gauss_samples(n, seed=5):
    rng = np.random.default_rng(seed)
    # stratified on a disc, then gaussian radius
    k = int(math.sqrt(n))
    pts = []
    for i in range(k):
        for j in range(k):
            a = (i + rng.random()) / k
            b = (j + rng.random()) / k
            rad = math.sqrt(-2 * math.log(max(1e-6, 1 - a)))
            pts.append((rad * math.cos(2 * math.pi * b), rad * math.sin(2 * math.pi * b)))
    return np.asarray(pts, np.float32)


def render_stone(mat, size=320, ss=3, seed=0, board_srgb=(232, 178, 104), light=None):
    S = size * ss
    u, v, r, n, Rpx = stone_normals(S, mat["hc"], mat.get("fillet", 0.12))
    rng = np.random.default_rng(seed)
    alb, rough, nn = MATERIALS[mat["kind"]](mat, u, v, r, S, rng)
    if nn is not None:  # micro-surface: frosted or satin grain perturbs the normal
        n = n + nn
        n /= np.linalg.norm(n, axis=-1, keepdims=True)
    board_lin = srgb2lin(board_srgb)
    grey = np.full(3, board_lin.mean(), np.float32)
    board_refl = (grey + (board_lin - grey) * mat.get("rim_sat", 0.25)) * mat.get("rim_board", 0.6)
    nz = np.clip(n[..., 2], 0, 1)
    ndl = np.sum(n * KEY, -1)
    wrap = mat.get("wrap", 0.0)
    key_d = np.clip((ndl + wrap) / (1 + wrap), 0, None)
    irr = (light["key"] * key_d)[..., None] + (light["sky_irr"] * (1 + n[..., 2]) / 2)[..., None] \
        + (light["bounce"] * mat.get("bounce", 1.0) * (1 - n[..., 2]) / 2 * _bounce_dir(mat, u, v, r))[..., None] * board_lin
    F0 = mat.get("F0", 0.04)
    F = F0 + (1 - F0) * (1 - nz) ** 5
    F = F * mat.get("spec", 1.0)
    # reflection seen by a camera looking straight down
    R = np.stack([2 * nz * n[..., 0], 2 * nz * n[..., 1], 2 * nz * nz - 1], -1)
    samples = gauss_samples(mat.get("samples", 144))
    rot = rng.uniform(0, 2 * np.pi, (S, S)).astype(np.float32)
    cr, sr = np.cos(rot)[..., None], np.sin(rot)[..., None]
    spec = np.zeros((S, S, 3), np.float32)
    rows = 64
    for y0 in range(0, S, rows):
        Rc_ = R[y0:y0 + rows]
        sig = rough[y0:y0 + rows][..., None]
        t1, t2 = _basis(Rc_)
        c_, s_ = cr[y0:y0 + rows], sr[y0:y0 + rows]
        t1, t2 = c_ * t1 + s_ * t2, -s_ * t1 + c_ * t2
        acc = np.zeros(Rc_.shape, np.float32)
        for (ox, oy) in samples:
            d = Rc_ + sig * (ox * t1 + oy * t2)
            d /= np.linalg.norm(d, axis=-1, keepdims=True)
            acc += env(d, board_refl, light)
        spec[y0:y0 + rows] = acc / len(samples)
    col = alb * irr * (1 - F)[..., None] + F[..., None] * spec
    if mat.get("core", 0) > 0:
        core = _box(R, KEY, K_T1, K_T2, mat.get("core_a", 0.07), mat.get("core_b", 0.05), soft=0.025)
        col = col + (F * mat["core"] * core)[..., None]
    if "translucent_rim" in mat:  # light passing through the thin edge glows warm
        t = smoothstep(1 - mat["translucent_rim"], 1.0, r)[..., None] * mat.get("rim_mix", 0.45)
        if mat.get("rim_away", False):
            kx, ky = KEY[0] / np.hypot(KEY[0], KEY[1]), KEY[1] / np.hypot(KEY[0], KEY[1])
            away = np.clip(-(u * kx + v * ky) / np.maximum(r, 1e-6), 0, 1)
            t = t * (0.15 + 0.85 * smoothstep(0.0, 0.8, away))[..., None]
        col = col * (1 - t) + t * srgb2lin(mat["rim_glow"])
    if "glow_band" in mat:
        kx, ky = KEY[0] / np.hypot(KEY[0], KEY[1]), KEY[1] / np.hypot(KEY[0], KEY[1])
        away = np.clip(-(u * kx + v * ky) / np.maximum(r, 1e-6), 0, 1)
        band = smoothstep(0.70, 0.86, r) * (1 - smoothstep(0.92, 1.0, r)) * away
        col = col * (1 - mat["glow_band"] * band[..., None]) + mat["glow_band"] * band[..., None] * srgb2lin(mat["glow"])
    if "tint" in mat:
        col = col * np.asarray(mat["tint"], np.float32)
    col *= light["exposure"]
    srgb = lin2srgb(col)
    # outside the disc: copy the colour just inside the rim at the same angle (no fringe)
    c = (S - 1) / 2.0
    y, x = np.mgrid[0:S, 0:S].astype(np.float32)
    k = np.where(r > 0.996, 0.996 / np.maximum(r, 1e-6), 1.0)
    sx = np.clip(np.rint(c + (x - c) * k), 0, S - 1).astype(int)
    sy = np.clip(np.rint(c + (y - c) * k), 0, S - 1).astype(int)
    srgb = srgb[sy, sx]
    alpha = np.clip((1.0 - r) * Rpx + 0.5, 0, 1)
    pre = np.dstack([srgb * alpha[..., None], alpha * 255])
    small = downsample(pre, ss)
    a = small[..., 3:4] / 255.0
    rgb = np.where(a > 0, small[..., :3] / np.maximum(a, 1e-6), 0)
    out = np.dstack([np.clip(rgb, 0, 255), small[..., 3]]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def _bounce_dir(mat, u, v, r):
    if not mat.get("bounce_away", False):
        return 1.0
    kx, ky = KEY[0] / np.hypot(KEY[0], KEY[1]), KEY[1] / np.hypot(KEY[0], KEY[1])
    away = np.clip(-(u * kx + v * ky) / np.maximum(r, 1e-6), 0, 1)
    return 0.3 + 0.7 * away


def mat_slate(m, u, v, r, S, rng):
    clouds = fbm2(S, S, S / 5, S / 5, 4, int(rng.integers(1e6)))
    grain = fbm2(S, S, 3.0, 3.0, 1, int(rng.integers(1e6)))
    alb = srgb2lin(m["albedo"]) * (1 + m["clouds"] * clouds + m["grain"] * grain)[..., None]
    # rare pale mineral specks, as Nachi slate shows under a lamp
    sp = np.zeros((S, S), np.float32)
    for _ in range(int(rng.integers(*m["specks"]))):
        cx, cy = rng.uniform(-0.8, 0.8, 2)
        rad = rng.uniform(0.003, 0.007)
        sp += np.exp(-(((u - cx) ** 2 + (v - cy) ** 2) / rad ** 2))
    alb = alb * (1 + 1.6 * np.clip(sp, 0, 1))[..., None]
    rough = m["rough"] * (1 + 0.02 * fbm2(S, S, S / 12, S / 12, 2, int(rng.integers(1e6))))
    nn = np.stack([noise2(S, S, 1.5, 1.5, int(rng.integers(1e6))),
                   noise2(S, S, 1.5, 1.5, int(rng.integers(1e6))),
                   np.zeros((S, S), np.float32)], -1) * m["micro"]
    return alb, rough.astype(np.float32), nn


def mat_shell(m, u, v, r, S, rng):
    """Clamshell: dense, slightly wavy growth lines (arcs of a far centre) with uneven spacing."""
    ang = rng.uniform(0, 2 * np.pi)
    D = rng.uniform(*m["arc_dist"])
    cx, cy = D * math.cos(ang), D * math.sin(ang)
    dist = np.sqrt((u - cx) ** 2 + (v - cy) ** 2)
    along = np.arctan2(v - cy, u - cx) * D  # coordinate along the lines
    dist = dist + m["wave"] * noise2(S, S, S / 6, S / 6, int(rng.integers(1e6)))
    lo, hi = D - 1.3, D + 1.3
    pos, lines = lo, []
    while pos < hi:
        cl = m.get("cluster", 0.9)
        cluster = 1 - cl / 2 + cl * (0.5 + 0.5 * math.sin(pos * rng.uniform(5, 11) + rng.uniform(0, 6)))
        pos += m["spacing"] * cluster * math.exp(rng.normal(0, m.get("spacing_sigma", 0.45)))
        lines.append((pos, rng.uniform(*m["line_w"]), math.exp(rng.normal(0, m.get("line_sigma", 0.6)))))
    shade = np.zeros((S, S), np.float32)
    strength = m["line_strength"] * rng.uniform(*m["variant_strength"])
    for p, w, s in lines:
        g = np.exp(-(((dist - p) / w) ** 2))
        shade += s * g
    shade = np.clip(shade, 0, 3)
    # a few fine, sharper lines among the soft bands
    for _ in range(int(rng.integers(*m.get("fine_lines", (0, 1))))):
        p = rng.uniform(D - 0.85, D + 0.85)
        shade += m.get("fine_strength", 1.0) / max(strength, 1e-6) * 0.02 * np.exp(-(((dist - p) / 0.005) ** 2))
    # broad translucent bands between whiter layers
    bands = noise1(4096, 4096 / 30, int(rng.integers(1e6)))
    bidx = np.clip(((dist - lo) / (hi - lo) * 4095).astype(int), 0, 4095)
    band = bands[bidx]
    base = srgb2lin(m["albedo"])
    tint = np.array([0.80, 0.95, 1.30], np.float32)  # growth lines are faintly beige
    alb = base * (1 - strength * shade[..., None] * tint)
    alb = alb * (1 + m["bands"] * band)[..., None]
    alb = alb * (1 + m["clouds"] * fbm2(S, S, S / 4, S / 4, 3, int(rng.integers(1e6))))[..., None]
    alb = alb * (1 - 0.02 * smoothstep(0.92, 1.0, r))[..., None]
    rough = np.full((S, S), m["rough"], np.float32)
    return alb.astype(np.float32), rough, None


def mat_frosted(m, u, v, r, S, rng):
    """Yunzi and frosted glass: a sugary matte surface (fine micro-facets), solid colour."""
    gc = S / m.get("grain_cells", 60)
    alb = srgb2lin(m["albedo"]) * (1 + m["grain"] * fbm2(S, S, gc, gc, 2, int(rng.integers(1e6))))[..., None]
    if "rim_tint" in m:
        t = smoothstep(1 - m["rim_width"], 1.0, r)[..., None]
        alb = alb * (1 - t) + srgb2lin(m["rim_tint"]) * t
    rough = m["rough"] * (1 + 0.02 * fbm2(S, S, S / 20, S / 20, 2, int(rng.integers(1e6))))
    fc = m.get("facet", 1.3)
    hf = lambda: noise2(S, S, fc, fc, int(rng.integers(1e6)))  # facet-sized sparkle
    nn = np.stack([hf(), hf(), np.zeros((S, S), np.float32)], -1) * m["micro"]
    return alb.astype(np.float32), rough.astype(np.float32), nn


MATERIALS = {"slate": mat_slate, "shell": mat_shell, "frosted": mat_frosted}

# ----------------------------------------------------------------------------- sets

LIGHT = {"key": 0.62, "sky_irr": 0.42, "bounce": 0.30, "sky": 0.60, "box": 3.4, "board": 0.50,
         "box_a": 0.26, "box_b": 0.18, "fill_box": 0.05, "horizon": 2.4, "exposure": 1.0}

SETS = {
    "kaya": {
        "title": "Hon-kaya board · Nachi slate · Hyuga clamshell",
        "board_kind": "masame",
        "board": {"seed": 101, "early": (252, 182, 99), "late": (216, 142, 70), "ring_px": 4.2,
                  "ring_sigma": 0.38, "line_w": 1.9, "year_grad": 0.25, "late_var": 0.35,
                  "late_slow": 0.35, "late_contrast": 0.19, "strong_every": 15, "strong_gain": 2.2,
                  "fade": 0.35, "fade_px": 260, "sway": 3.5, "wobble": 1.2, "wob_x": 80,
                  "wiggle": 0.4, "slant": 0.0015, "broad": 0.040, "hue_bands": 0.035,
                  "ribbons": 0.008, "ribbon_px": 24, "fibre": 0.010, "pores": 0.010,
                  "rays": 0.02, "scratches": 12, "streaks": 1, "streak_strength": 0.10},
        "board_srgb": (246, 177, 95),
        "black": {"kind": "slate", "hc": 0.40, "albedo": (14, 15, 16), "clouds": 0.015,
                  "grain": 0.06, "specks": (1, 4), "rough": 0.27, "micro": 0.02, "F0": 0.07,
                  "rim_sat": 0.05, "rim_board": 1.7},
        "white": {"kind": "shell", "hc": 0.40, "albedo": (243, 240, 237), "arc_dist": (18.0, 40.0),
                  "wave": 0.002, "spacing": 0.13, "line_w": (0.008, 0.016),
                  "line_strength": 0.030, "variant_strength": (0.8, 1.4), "bands": 0.010,
                  "clouds": 0.006, "rough": 0.06, "wrap": 0.9, "F0": 0.04, "core": 9.0, "spacing_sigma": 0.5,
                  "bounce": 4.6, "rim_sat": 1.0, "rim_board": 1.0,
                  "line_sigma": 0.4, "cluster": 0.3, "fine_lines": (0, 4), "fine_strength": 1.0},
        "white_variants": 8,
    },
    "bamboo": {
        "title": "Carbonised bamboo board · Yunzi stones",
        "board_kind": "bamboo",
        "board": {"seed": 202, "base": (240, 156, 54), "strip_px": (30, 48), "strip_tone": 0.045,
                  "honey_p": 0.12, "honey": 0.05, "zones": 0.04, "fibre": 0.42,
                  "fibre_w": 0.6, "streaks": 0.06, "fibre_gap": (4.0, 7.0), "fibre_seg": 45,
                  "bundles": 0.05, "seam": 0.03, "node_gap": (380, 620), "node_h": (40, 52),
                  "node_halo": 0.07, "node_pale": 0.65, "node_dark": 0.32,
                  "fleck_h": 2.6, "fleck_w": 0.8},
        "board_srgb": (228, 142, 58),
        "black": {"kind": "frosted", "hc": 0.52, "albedo": (10, 16, 12), "grain": 0.04,
                  "rough": 0.30, "micro": 0.05, "rim_tint": (12, 24, 16), "rim_width": 0.06,
                  "F0": 0.06, "tint": (0.975, 1.02, 0.98), "grain_cells": 200},
        "white": {"kind": "frosted", "hc": 0.52, "albedo": (238, 234, 222), "grain": 0.006,
                  "rough": 0.32, "micro": 0.02, "wrap": 0.6, "F0": 0.06, "grain_cells": 320,
                  "facet": 2.0, "bounce": 4.2, "bounce_away": True, "translucent_rim": 0.12,
                  "rim_glow": (222, 198, 166), "rim_mix": 0.25, "rim_away": True},
        "white_variants": 3,
    },
    "shinkaya": {
        "title": "Shin-kaya (spruce) board · frosted glass stones",
        "board_kind": "masame",
        "board": {"seed": 303, "early": (242, 170, 90), "late": (180, 88, 16), "ring_px": 10.0,
                  "ring_sigma": 0.75, "slow_cell": 6.0, "line_w": 1.1, "year_grad": 0.30,
                  "late_var": 0.40, "late_slow": 0.30, "late_contrast": 0.42, "fade": 0.30,
                  "fade_px": 320, "sway": 1.0, "wobble": 3.0, "wob_x": 220, "wob_y": 700,
                  "wob_oct": 2, "wiggle": 0.25,
                  "slant": 0.0, "broad": 0.045, "ribbons": 0.020, "ribbon_px": 18,
                  "fibre": 0.016, "pores": 0.012, "rays": 0.0, "scratches": 6, "streaks": 2,
                  "streak_strength": 0.18},
        "board_srgb": (226, 150, 76),
        "black": {"kind": "frosted", "hc": 0.42, "albedo": (6, 6, 7), "grain": 0.10,
                  "rough": 0.30, "micro": 0.04, "F0": 0.036, "rim_sat": 0.1, "grain_cells": 320, "facet": 1.0},
        "white": {"kind": "frosted", "hc": 0.42, "albedo": (234, 226, 214), "grain": 0.008,
                  "rough": 0.45, "micro": 0.03, "wrap": 1.9, "translucent_rim": 0.45,
                  "rim_glow": (226, 198, 160), "rim_mix": 0.32, "rim_away": True, "F0": 0.04,
                  "grain_cells": 320, "facet": 1.0, "bounce": 1.8},
        "white_variants": 3,
    },
}


def make_board(spec_set, size):
    kind = spec_set["board_kind"]
    gen = wood_masame if kind == "masame" else bamboo
    lin = tileable(lambda n: gen(n, spec_set["board"]), size)
    return finish(lin)


# ----------------------------------------------------------------------------- preview

POSITION = "pd dp pp dd fq qf nc qj cf fc dj kq co qn pk ok cq dq cp bp do".split()


def preview(board_img, blacks, whites, title, out_path, big_black, big_white):
    W, margin = 1000, 46
    cell = (W - 2 * margin) / 18
    canvas = Image.new("RGB", (W + 470, W), (36, 38, 42))
    canvas.paste(board_img.crop((0, 0, W, W)), (0, 0))  # texture at 1:1
    d = ImageDraw.Draw(canvas)
    for i in range(19):
        p = round(margin + i * cell)
        d.line([(margin, p), (W - margin, p)], fill=(0, 0, 0), width=1)
        d.line([(p, margin), (p, W - margin)], fill=(0, 0, 0), width=1)
    for sx in (3, 9, 15):
        for sy in (3, 9, 15):
            cx, cy = margin + sx * cell, margin + sy * cell
            d.ellipse([cx - 3.5, cy - 3.5, cx + 3.5, cy + 3.5], fill=(0, 0, 0))
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 15)
        tfont = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
    except OSError:
        font = tfont = ImageFont.load_default()
    letters = "ABCDEFGHJKLMNOPQRST"
    for i in range(19):
        p = margin + i * cell
        d.text((p - 5, 6), letters[i], fill=(0, 0, 0), font=font)
        d.text((4, p - 9), str(19 - i), fill=(0, 0, 0), font=font)
    size = int(cell * 0.96)
    rad = size / 2
    stones = []
    for k, mv in enumerate(POSITION):
        x, y = ord(mv[0]) - 97, ord(mv[1]) - 97
        stones.append((x, y, k % 2 == 0))
    # the app's soft drop shadow
    shadow = Image.new("L", canvas.size, 0)
    sd = ImageDraw.Draw(shadow)
    for x, y, _ in stones:
        cx, cy = margin + x * cell + rad * 0.10, margin + y * cell + rad * 0.14
        sd.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=255)
    shadow = shadow.filter(ImageFilter.GaussianBlur(rad * 0.20))
    canvas = Image.composite(Image.new("RGB", canvas.size, (0, 0, 0)), canvas,
                             shadow.point(lambda v: int(v * 0.50)))
    for x, y, is_black in stones:
        pool = blacks if is_black else whites
        img = pool[(x * 7 + y * 13) % len(pool)].resize((size, size), Image.BOX)
        cx, cy = margin + x * cell, margin + y * cell
        canvas.paste(img, (int(round(cx - size / 2)), int(round(cy - size / 2))), img)
    # the app's soft light falloff from the upper left: a warm multiply over board and stones
    region = np.asarray(canvas.crop((0, 0, W, W)), np.float32)
    yy, xx = np.mgrid[0:W, 0:W].astype(np.float32)
    t = (xx + yy) / (2 * W) + np.random.default_rng(3).uniform(-0.004, 0.004, (W, W))
    fall = 1 - t[..., None] * np.array([0.040, 0.050, 0.065], np.float32)
    canvas.paste(Image.fromarray(np.clip(region * fall, 0, 255).astype(np.uint8)), (0, 0))
    last = stones[-1]
    lx, ly = margin + last[0] * cell, margin + last[1] * cell
    d = ImageDraw.Draw(canvas)
    d.ellipse([lx - rad * 0.3, ly - rad * 0.3, lx + rad * 0.3, ly + rad * 0.3],
              outline=(255, 255, 255) if last[2] else (0, 0, 0), width=2)
    d.text((W + 15, 14), title, fill=(235, 235, 235), font=tfont)
    crop = canvas.crop((0, int(W * 0.62), int(W * 0.40), W))
    zoom = crop.resize((440, int(440 * crop.size[1] / crop.size[0])), Image.LANCZOS)
    canvas.paste(zoom, (W + 15, 52))
    d.text((W + 15, 58 + zoom.size[1]), "close-up at 2.2x (lower left)", fill=(170, 170, 170),
           font=font)
    yb = 80 + zoom.size[1]
    canvas.paste(big_black.resize((200, 200), Image.LANCZOS), (W + 20, yb),
                 big_black.resize((200, 200), Image.LANCZOS))
    canvas.paste(big_white.resize((200, 200), Image.LANCZOS), (W + 245, yb),
                 big_white.resize((200, 200), Image.LANCZOS))
    canvas.save(out_path)


def main():
    out = sys.argv[1]
    names = sys.argv[2:] or list(SETS)
    for name in names:
        t0 = time.time()
        s = SETS[name]
        os.makedirs(f"{out}/{name}", exist_ok=True)
        board = make_board(s, 2048)
        board.save(f"{out}/{name}/board.png")
        light = dict(LIGHT)
        base_seed = sum(ord(ch) for ch in name) * 1000
        black = render_stone(s["black"], seed=base_seed + 1, board_srgb=s["board_srgb"], light=light)
        black.save(f"{out}/{name}/black.png")
        whites = []
        for k in range(s["white_variants"]):
            w = render_stone(s["white"], seed=base_seed + 100 + k, board_srgb=s["board_srgb"], light=light)
            w.save(f"{out}/{name}/white{'' if k == 0 else '_' + str(k)}.png")
            whites.append(w)
        blacks = [black]
        if s["black"]["kind"] == "slate":
            for k in range(1, 4):
                b = render_stone(s["black"], seed=base_seed + 1 + k, board_srgb=s["board_srgb"], light=light)
                b.save(f"{out}/{name}/black_{k}.png")
                blacks.append(b)
        preview(board, blacks, whites, s["title"], f"{out}/preview_{name}.png", black, whites[0])
        print(f"ok {name} {time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    main()
