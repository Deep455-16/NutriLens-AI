"""
Measures which OpenCV steps actually help the food classifier, on held-out photos.

    python vision_eval.py --parquet data/food101 --indian data/indian --n 1500

The same operations (and constants) are implemented in the app's ImageAnalyzer.kt, so the
numbers printed here are the reason each step is (or is not) used on the phone:

  * crops:   whole photo (as trained) vs centre square vs plate found with Hough circles
  * low light: photos darkened with gamma 2.2 x 0.6, then no fix / CLAHE / auto-gamma
  * blur:    Gaussian blur of increasing strength -> accuracy and Laplacian-variance score,
             to choose the "photo looks blurry" threshold from data instead of guessing
"""
import argparse
import io
import pathlib
import random

import cv2
import numpy as np
import pyarrow.parquet as pq
from PIL import Image

import verify_tflite as vt

ANALYSIS_SIDE = 512  # quality metrics are computed at this size so they do not depend on resolution


# ---- operations mirrored in ImageAnalyzer.kt -------------------------------------------

def for_analysis(rgb):
    h, w = rgb.shape[:2]
    s = ANALYSIS_SIDE / max(h, w)
    return cv2.resize(rgb, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else rgb


def blur_score(rgb):
    """Variance of the Laplacian: low = few sharp edges = blurry."""
    gray = cv2.cvtColor(for_analysis(rgb), cv2.COLOR_RGB2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def brightness(rgb):
    return float(cv2.cvtColor(for_analysis(rgb), cv2.COLOR_RGB2GRAY).mean())


def clahe(rgb, clip=2.0, tiles=8):
    """Contrast-limited adaptive histogram equalisation on the L channel only (keeps colours)."""
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)
    l, a, b = cv2.split(lab)
    l = cv2.createCLAHE(clipLimit=clip, tileGridSize=(tiles, tiles)).apply(l)
    return cv2.cvtColor(cv2.merge((l, a, b)), cv2.COLOR_LAB2RGB)


def auto_gamma(rgb, target=0.5):
    """Gamma that moves the mean brightness to [target]."""
    mean = max(brightness(rgb) / 255.0, 1e-3)
    gamma = np.log(target) / np.log(mean)
    lut = np.clip(((np.arange(256) / 255.0) ** gamma) * 255.0, 0, 255).astype(np.uint8)
    return cv2.LUT(rgb, lut)


def center_square(rgb):
    h, w = rgb.shape[:2]
    s = min(h, w)
    y, x = (h - s) // 2, (w - s) // 2
    return rgb[y:y + s, x:x + s]


def plate_crop(rgb):
    """Largest circle (a plate or bowl) covering a good part of the photo, else the whole photo."""
    small = for_analysis(rgb)
    gray = cv2.medianBlur(cv2.cvtColor(small, cv2.COLOR_RGB2GRAY), 5)
    m = min(gray.shape)
    circles = cv2.HoughCircles(gray, cv2.HOUGH_GRADIENT, dp=1.2, minDist=m,
                               param1=120, param2=60, minRadius=int(m * 0.3), maxRadius=int(m * 0.6))
    if circles is None:
        return rgb, False
    x, y, r = circles[0][0]
    scale = rgb.shape[1] / small.shape[1]
    x, y, r = x * scale, y * scale, r * scale * 1.05
    x0, y0 = int(max(0, x - r)), int(max(0, y - r))
    x1, y1 = int(min(rgb.shape[1], x + r)), int(min(rgb.shape[0], y + r))
    return rgb[y0:y1, x0:x1], True


def darken(rgb):
    lut = np.clip(((np.arange(256) / 255.0) ** 2.2) * 255.0 * 0.6, 0, 255).astype(np.uint8)
    return cv2.LUT(rgb, lut)


# ---- evaluation --------------------------------------------------------------------------

def sample(parquet, extras, labels, n, seed=0):
    """Up to n random held-out photos from each dataset, labelled with OUR label index."""
    out = []
    srcs = [(sorted(pathlib.Path(parquet).glob("validation-*.parquet")), parquet)]
    srcs += [(vt.test_files(e), e) for e in extras or ()]
    for files, folder in srcs:
        names = vt.names_of(files[0], folder)
        rows = [(img["bytes"], labels.index(vt.ALIASES.get(names[l], names[l])))
                for f in files for b in pq.ParquetFile(f).iter_batches(batch_size=256, columns=["image", "label"])
                for img, l in zip(b.column("image").to_pylist(), b.column("label").to_pylist())]
        out += random.Random(seed).sample(rows, min(n, len(rows)))
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--parquet", required=True)
    p.add_argument("--extra", action="append")
    p.add_argument("--n", type=int, default=1500, help="photos per dataset")
    p.add_argument("--model", default=vt.ASSETS / "food_model.tflite")
    p.add_argument("--labels", default=vt.ASSETS / "labels.txt")
    args = p.parse_args()

    labels, predict = vt.load(args.model, args.labels)
    data = sample(args.parquet, args.extra, labels, args.n)
    images = [(np.asarray(Image.open(io.BytesIO(b)).convert("RGB")), y) for b, y in data]
    print(f"{len(images)} held-out photos")

    def acc(transform):
        top1 = top3 = 0
        for rgb, y in images:
            ranked = [i for i, _ in predict(Image.fromarray(transform(rgb)))[:3]]
            top1 += ranked[0] == y
            top3 += y in ranked
        return top1 / len(images), top3 / len(images)

    def row(name, transform):
        a1, a3 = acc(transform)
        print(f"  {name:38s} top-1 {a1:.3f}   top-3 {a3:.3f}", flush=True)

    print("Cropping")
    row("whole photo (as trained)", lambda x: x)
    row("centre square", center_square)
    found = sum(plate_crop(x)[1] for x, _ in images)
    row(f"plate crop (circle found in {found}/{len(images)})", lambda x: plate_crop(x)[0])

    print("Low light (darkened copies)")
    print(f"  mean brightness: normal {np.mean([brightness(x) for x, _ in images]):.0f}, "
          f"darkened {np.mean([brightness(darken(x)) for x, _ in images]):.0f}")
    row("darkened, no fix", darken)
    row("darkened + CLAHE", lambda x: clahe(darken(x)))
    row("darkened + auto gamma", lambda x: auto_gamma(darken(x)))
    row("darkened + auto gamma + CLAHE", lambda x: clahe(auto_gamma(darken(x))))
    row("normal + CLAHE (must not hurt)", clahe)

    print("Blur (Gaussian sigma at 512 px)")
    for sigma in (0, 1.5, 3, 5):
        f = (lambda x: x) if sigma == 0 else (lambda x, s=sigma: cv2.GaussianBlur(for_analysis(x), (0, 0), s))
        scores = [blur_score(f(x)) for x, _ in images]
        a1, a3 = acc(f)
        print(f"  sigma {sigma:<4} top-1 {a1:.3f}  top-3 {a3:.3f}  Laplacian var median {np.median(scores):7.1f}"
              f"  p10 {np.percentile(scores, 10):7.1f}", flush=True)


if __name__ == "__main__":
    main()
