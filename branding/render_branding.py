"""Generate the existing site's mark as Android vector and launcher artwork.

Uses the public u-tv.ru header mark and Onest fonts; no video is bundled.
Run with Python, Pillow, numpy and fontTools installed.
"""
from pathlib import Path
from collections import defaultdict
import io
import urllib.request
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT.parent / 'deliverables'
CACHE = Path('/tmp/utv-branding')
BG = '#181821'
RED = (236, 26, 58)
PINK = (221, 0, 99)


def simplify(points, tolerance=.65):
    if len(points) <= 2:
        return points
    a, b = np.array(points[0]), np.array(points[-1])
    v = b-a
    q = np.array(points)
    if np.dot(v, v) == 0:
        distances = np.linalg.norm(q-a, axis=1)
    else:
        t = np.clip((q-a)@v/np.dot(v, v), 0, 1)
        distances = np.linalg.norm(q-(a+t[:, None]*v), axis=1)
    index = int(distances.argmax())
    if distances[index] > tolerance:
        return simplify(points[:index+1], tolerance)[:-1] + simplify(points[index:], tolerance)
    return [points[0], points[-1]]


def trace(mask, box):
    """Trace all filled contours, retaining circle holes with even-odd fill."""
    edges = defaultdict(list)
    h, w = mask.shape
    ys, xs = np.where(mask)
    for x, y in zip(xs.tolist(), ys.tolist()):
        if y == 0 or not mask[y-1, x]: edges[(x, y)].append((x+1, y))
        if x == w-1 or not mask[y, x+1]: edges[(x+1, y)].append((x+1, y+1))
        if y == h-1 or not mask[y+1, x]: edges[(x+1, y+1)].append((x, y+1))
        if x == 0 or not mask[y, x-1]: edges[(x, y+1)].append((x, y))
    paths = []
    while edges:
        first = next(iter(edges)); current = first; contour = [first]
        while True:
            nxt = edges[current].pop()
            if not edges[current]: del edges[current]
            current = nxt; contour.append(current)
            if current == first: break
        contour = simplify(contour)
        if len(contour) < 4: continue
        def point(p): return f'{box[0]+p[0]/w*box[2]:.3f},{box[1]+p[1]/h*box[3]:.3f}'
        paths.append('M'+point(contour[0])+'L'+' '.join(point(p) for p in contour[1:-1])+'Z')
    return ' '.join(paths)


def gradient(size):
    w, h = size; x = np.linspace(0, 1, w)[None, :]
    t = np.broadcast_to(x, (h, w))
    rgb = np.array(RED)[None, None, :]*(1-t[:, :, None])+np.array(PINK)[None, None, :]*t[:, :, None]
    return Image.fromarray(np.uint8(rgb), 'RGB')


def make_mark(source):
    data = np.array(source)
    alpha = source.getchannel('A')
    # Separate the original white glyph from the original red speech bubble.
    white = np.clip((data[:, :, 1].astype(float)-26)/(255-26), 0, 1)
    white = Image.fromarray(np.uint8(white*data[:, :, 3]), 'L')
    result = gradient(source.size).convert('RGBA'); result.putalpha(alpha)
    result.alpha_composite(Image.composite(Image.new('RGBA', source.size, 'white'),
                                         Image.new('RGBA', source.size), white))
    return result, np.array(alpha) > 127, np.array(white) > 127


def font(name, size):
    ttf = CACHE / f'Onest-{name}.ttf'
    if not ttf.exists():
        data = urllib.request.urlopen(f'https://www.u-tv.ru/static/fonts/Onest/Onest-{name}.woff', timeout=20).read()
        f = TTFont(io.BytesIO(data)); f.flavor = None; f.save(ttf)
    return ImageFont.truetype(str(ttf), size)


def main():
    OUT.mkdir(exist_ok=True); CACHE.mkdir(exist_ok=True)
    source = Image.open(ROOT/'branding/site-mark.png').convert('RGBA')
    source = source.crop(source.getbbox())
    mark, bubble_mask, white_mask = make_mark(source)
    ratio = source.height/source.width
    box = (10, 54-44*ratio, 88, 88*ratio)
    bubble = trace(bubble_mask, box); white = trace(white_mask, box)
    # Native vector bands keep the gradient self-contained without linked colors.
    bands=[]
    for i in range(128):
        t=(i+.5)/128; color=tuple(round(a*(1-t)+b*t) for a,b in zip(RED,PINK))
        fill='#'+''.join(f'{c:02X}' for c in color)
        x0=10+i*88/128; x1=10+(i+1)*88/128
        bands.append(f'<path android:fillColor="{fill}" android:pathData="M{x0:.4f},0H{x1:.4f}V108H{x0:.4f}Z"/>')
    vector = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
 android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
 <path android:fillColor="{BG}" android:pathData="M0,0h108v108h-108z"/>
 <group><clip-path android:pathData="{bubble}"/>
 {''.join(bands)}
 </group>
 <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="{white}"/>
</vector>'''
    (ROOT/'app/src/main/res/drawable/app_icon.xml').write_text(vector)
    icon = Image.new('RGBA', (1024, 1024), BG)
    width = round(1024*88/108); height = round(width*ratio)
    icon.alpha_composite(mark.resize((width, height), Image.Resampling.LANCZOS), ((1024-width)//2, (1024-height)//2))
    icon.convert('RGB').resize((512, 512), Image.Resampling.LANCZOS).save(OUT/'utv-app-icon-v2.png')

    banner = Image.new('RGBA', (1280, 720), BG)
    glow = Image.new('RGBA', banner.size)
    gd = ImageDraw.Draw(glow); gd.ellipse((-200, 40, 630, 900), fill=(221, 0, 99, 22))
    banner.alpha_composite(glow.filter(ImageFilter.GaussianBlur(105)))
    w = 405; h = round(w*ratio)
    banner.alpha_composite(mark.resize((w, h), Image.Resampling.LANCZOS), (85, (720-h)//2))
    draw = ImageDraw.Draw(banner)
    draw.text((565, 210), 'ШОУ НА БОЛЬШОМ ЭКРАНЕ', font=font('Bold', 24), fill='#F54354')
    draw.text((558, 266), 'Ю', font=font('Bold', 108), fill='#FAFAFA')
    # The site's Cyrillic font subset has no middle-dot outline.
    draw.ellipse((712, 328, 724, 340), fill='#F54354')
    draw.text((565, 410), 'Реалити и шоу', font=font('Regular', 40), fill='#CCCCDA')
    draw.text((565, 484), 'u-tv.ru', font=font('Regular', 28), fill='#6E6E81')
    banner = banner.convert('RGB')
    banner.save(OUT/'utv-tv-banner-v2.png')
    banner.resize((320, 180), Image.Resampling.LANCZOS).save(ROOT/'app/src/main/res/drawable-xhdpi/tv_banner.png')
    print('Icon and banner generated from u-tv.ru branding')


if __name__ == '__main__': main()
