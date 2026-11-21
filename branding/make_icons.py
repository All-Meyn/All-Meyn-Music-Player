import numpy as np, cv2, potrace
from PIL import Image

# Run from the repo root: python3 branding/make_icons.py (needs numpy, opencv, pillow, potracer).
# It writes PNGs and path text to the current directory; copy them into app/src/main/res.
COL = 'branding/logo-color-source.jpg'
MONO = 'branding/logo-monochrome-source.jpg'
GLYPH_W = 56.0            # glyph width in the 108-unit icon layer (inside the 66-unit safe circle)

# ---------- colored foreground: separate the copper glyph (and its glow) from the dark tile ----------
I = np.array(Image.open(COL).convert('RGB')).astype(np.float32)
R, G, Bc = I[..., 0], I[..., 1], I[..., 2]
warm = ((R - Bc) > 6).astype(np.uint8)                      # copper and its glow are warm; the tile is cool grey
region = np.zeros_like(warm); region[180:580, 430:990] = 1
warm *= region
mask = cv2.dilate(warm, np.ones((15, 15), np.uint8))
B = cv2.inpaint(I.clip(0, 255).astype(np.uint8), mask * 255, 25, cv2.INPAINT_TELEA).astype(np.float32)
B = cv2.GaussianBlur(B, (0, 0), 12)                          # the tile is a smooth gradient
D = I - B
d = D.max(axis=2)
a = np.clip((d - 4) / 110.0, 0, 1) * (mask > 0)
F = np.clip(B + D / np.maximum(a, 1e-3)[..., None], 0, 255)
core = (I.mean(axis=2) > 150)
ys, xs = np.where(core & (region > 0)); x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
cx, cy, w = (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0
print('colored core bbox', x0, x1, y0, y1)
rgba = np.dstack([F, a * 255]).astype(np.uint8)

def place(rgba_src, cx, cy, w, size):
    s = (GLYPH_W / 108.0 * size) / w                         # px per source px
    M = np.float32([[s, 0, size / 2 - cx * s], [0, s, size / 2 - cy * s]])
    im = Image.fromarray(rgba_src, 'RGBA').convert('RGBa')   # premultiplied: no dark fringes
    big = np.array(im).astype(np.float32)
    out = cv2.warpAffine(big, M, (size, size), flags=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return Image.fromarray(out.clip(0, 255).astype(np.uint8), 'RGBa').convert('RGBA')

for name, px in [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216), ('xxhdpi', 324), ('xxxhdpi', 432)]:
    place(rgba, cx, cy, w, px).save(f'fg_{name}.png', optimize=True)

# ---------- monochrome: trace the white glyph into vector paths ----------
M8 = np.array(Image.open(MONO).convert('L'))
sub = M8[230:530, 450:960].astype(np.float32)
up = cv2.resize(sub, None, fx=4, fy=4, interpolation=cv2.INTER_CUBIC)
up = cv2.GaussianBlur(up, (0, 0), 4)            # smooth JPEG wobble before tracing
bm = potrace.Bitmap(up <= 128)   # potracer fills the False pixels
plist = bm.trace(turdsize=20, alphamax=1.0, opticurve=True, opttolerance=1.5)
ys, xs = np.where(up > 128)
mx0, mx1, my0, my1 = xs.min(), xs.max(), ys.min(), ys.max()
mcx, mcy, mw = (mx0 + mx1) / 2, (my0 + my1) / 2, mx1 - mx0
print('mono glyph bbox (4x sub)', mx0, mx1, my0, my1)

def path_data(cx, cy, w, unit_w, origin):
    s = unit_w / w
    T = lambda p: (origin[0] + (p.x - cx) * s, origin[1] + (p.y - cy) * s)
    f = lambda xy: f'{xy[0]:.2f},{xy[1]:.2f}'
    out = []
    for curve in plist:
        out.append('M' + f(T(curve.start_point)))
        for seg in curve.segments:
            if seg.is_corner:
                out.append('L' + f(T(seg.c)) + 'L' + f(T(seg.end_point)))
            else:
                out.append('C' + f(T(seg.c1)) + ' ' + f(T(seg.c2)) + ' ' + f(T(seg.end_point)))
        out.append('Z')
    return ''.join(out)

# The monochrome layer matches the colored glyph's size and centre.
open('mono_path.txt', 'w').write(path_data(mcx, mcy, mw, GLYPH_W, (54, 54)))
# A tight brand mark for in-app use: 48-unit wide viewport.
bw = 48.0; bh = bw * (my1 - my0) / mw
open('mark_path.txt', 'w').write(path_data(mcx, mcy, mw, bw, (bw / 2, bh / 2)))
open('mark_size.txt', 'w').write(f'{bw:.2f} {bh:.2f}')
print('mark viewport', bw, round(bh, 2), 'paths', len(plist))
