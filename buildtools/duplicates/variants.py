"""Re-saved copies of a photo, as features.py makes them for the test."""
import io
from PIL import Image, ImageEnhance


def variants(img, rng):
    """Re-saved copies: what really happens to photos (sent through apps, edited, screenshotted)."""
    out = []
    def jpeg(im, q):
        b = io.BytesIO(); im.save(b, 'JPEG', quality=q); return Image.open(io.BytesIO(b.getvalue())).convert('RGB')
    out.append(('small', jpeg(img.resize((img.width // 2, img.height // 2), Image.BILINEAR), 70)))
    out.append(('lowq', jpeg(img, 35)))
    w, h = img.size
    dx, dy = round(w * 0.03), round(h * 0.03)
    out.append(('crop', jpeg(img.crop((dx, dy, w - dx, h - dy)).resize((w, h), Image.BICUBIC), 85)))
    out.append(('bright', jpeg(ImageEnhance.Contrast(ImageEnhance.Brightness(img).enhance(1.18)).enhance(1.1), 85)))
    bar = Image.new('RGB', (w, h + round(h * 0.16)), (12, 12, 12))
    bar.paste(img, (0, round(h * 0.08)))
    out.append(('screenshot', jpeg(bar, 85)))
    return out
