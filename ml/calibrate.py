"""
Temperature scaling for the classifier, plus the app's model_info.json.

    python calibrate.py --parquet data/food101 --extra data/indian --extra data/indian2

Fits one temperature T on half of a held-out sample (minimising negative log-likelihood) and
reports Expected Calibration Error (ECE) on the other half, before and after. A well-calibrated
"70%" is right about 70% of the time, which is what the app's "Not sure" threshold relies on.
"""
import argparse
import io
import json

import numpy as np
from PIL import Image

import verify_tflite as vt
import vision_eval as ve


def scale(p, t):
    z = np.log(np.clip(p, 1e-12, 1)) / t
    z -= z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def nll(p, y):
    return float(-np.mean(np.log(np.clip(p[np.arange(len(y)), y], 1e-12, 1))))


def ece(p, y, bins=10):
    conf, pred = p.max(axis=1), p.argmax(axis=1)
    total = 0.0
    for lo in np.linspace(0, 1, bins, endpoint=False):
        m = (conf > lo) & (conf <= lo + 1 / bins)
        if m.any():
            total += m.mean() * abs((pred[m] == y[m]).mean() - conf[m].mean())
    return total


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--parquet", required=True)
    a.add_argument("--extra", action="append")
    a.add_argument("--n", type=int, default=3000)
    a.add_argument("--accuracy", default="export_v3/tflite_eval.txt", help="output of verify_tflite --parquet")
    a.add_argument("--out", default=vt.ASSETS / "model_info.json")
    args = a.parse_args()

    labels, predict = vt.load(vt.ASSETS / "food_model.tflite", vt.ASSETS / "labels.txt")
    data = ve.sample(args.parquet, args.extra, labels, args.n, seed=1)
    probs = np.zeros((len(data), len(labels)), np.float32)
    ys = np.array([y for _, y in data])
    for i, (b, _) in enumerate(data):
        for j, p in predict(Image.open(io.BytesIO(b))):
            probs[i, j] = p
    fit, test = np.arange(len(ys)) % 2 == 0, np.arange(len(ys)) % 2 == 1

    grid = np.arange(0.5, 3.01, 0.05)
    t = float(min(grid, key=lambda g: nll(scale(probs[fit], g), ys[fit])))
    print(f"{len(ys)} photos; fitted on {fit.sum()}, checked on {test.sum()}")
    print(f"temperature T = {t:.2f}")
    print(f"ECE before {ece(probs[test], ys[test]):.3f}  after {ece(scale(probs[test], t), ys[test]):.3f}")
    print(f"NLL before {nll(probs[test], ys[test]):.3f}  after {nll(scale(probs[test], t), ys[test]):.3f}")
    after = scale(probs[test], t)
    for lo, hi in [(0, .4), (.4, .7), (.7, 1.01)]:
        m = (after.max(1) >= lo) & (after.max(1) < hi)
        print(f"  calibrated confidence {lo:.0%}-{min(hi,1):.0%}: {m.sum():4d} photos, actually correct {((after.argmax(1) == ys[test])[m]).mean():.1%}")

    acc = {}
    for line in open(args.accuracy):
        name, rest = line.split(":", 1)
        parts = rest.replace(",", "").split()
        acc[name.strip()] = {"images": int(parts[0]), "top1": float(parts[3]), "top3": float(parts[5])}
    info = {"temperature": round(t, 2), "classes": len(labels), "architecture": "MobileNetV3-Large, float16 TFLite",
            "accuracy": acc, "ece_before": round(ece(probs[test], ys[test]), 3), "ece_after": round(ece(after, ys[test]), 3)}
    json.dump(info, open(args.out, "w"), indent=2)
    print(f"wrote {args.out}: {info}")


if __name__ == "__main__":
    main()
