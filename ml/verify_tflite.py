"""
Check the exported .tflite exactly the way the app runs it.

1) Parity with Android (per image):
    python verify_tflite.py --images samples
        -> "pizza.jpg   top3=pizza=0.9127, ..."
    adb logcat -s FoodClassifier
        -> "top3=pizza=0.9132, ... ms=41"     (scan the same photos in the app)
   Pass: same top-1 for every image, probabilities within +-0.02.

2) Accuracy of the shipped file on the Food-101 validation split:
    python verify_tflite.py --parquet data/food101
        -> top-1 / top-3 accuracy over 25,250 images

Preprocessing mirrors FoodClassifier.kt (resizeBilinearRgb) and training (tf.image.resize):
EXIF rotation, bilinear resize to 224x224 with half-pixel centers, no crop and NO
antialiasing, kept as raw 0..255 RGB floats (FLOAT_MEAN = 0, FLOAT_STD = 1). PIL's own
BILINEAR antialiases when shrinking and changed the top-1 on 3 of 12 sample photos, so it is
deliberately not used here.
"""
import argparse
import io
import pathlib

import numpy as np
from PIL import Image, ImageOps

try:
    from tflite_runtime.interpreter import Interpreter
except ImportError:
    import tensorflow as tf

    Interpreter = tf.lite.Interpreter

HERE = pathlib.Path(__file__).resolve().parent
ASSETS = HERE.parent / "app/src/main/assets"


def softmax(v):
    e = np.exp(v - v.max())
    return e / e.sum()


def resize_bilinear(img, size):
    """Bilinear resize with half-pixel centers and no antialiasing (= resizeBilinearRgb)."""
    a = np.asarray(img, dtype=np.float32)
    h, w = a.shape[:2]

    def axis(n_in):
        pos = np.clip((np.arange(size) + 0.5) * n_in / size - 0.5, 0, n_in - 1)
        lo = np.floor(pos).astype(int)
        return lo, np.minimum(lo + 1, n_in - 1), (pos - lo).astype(np.float32)

    y0, y1, wy = axis(h)
    x0, x1, wx = axis(w)
    wx = wx[None, :, None]
    top = a[y0][:, x0] * (1 - wx) + a[y0][:, x1] * wx
    bottom = a[y1][:, x0] * (1 - wx) + a[y1][:, x1] * wx
    out = top * (1 - wy[:, None, None]) + bottom * wy[:, None, None]
    return out


def center_square(img):
    """Central square of the image (where the camera's scan frame is)."""
    w, h = img.size
    side = min(w, h)
    left, top = (w - side) // 2, (h - side) // 2
    return img.crop((left, top, left + side, top + side))


def load(model, labels, crop="squash"):
    labels = [l.strip() for l in open(labels) if l.strip()]
    interp = Interpreter(model_path=str(model), num_threads=4)
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    # The class scores are the output with one value per label (newer models also output an
    # image embedding, which may come first).
    outs = interp.get_output_details()
    # labels.txt may list extra foods after the model's classes (log-by-hand dishes); like the
    # app, use only as many labels as the model has outputs.
    classes = max(o["shape"][-1] for o in outs if o["shape"][-1] != 960) if len(outs) > 1 else outs[0]["shape"][-1]
    if len(labels) > classes:
        labels = labels[:classes]
    out = next(o for o in outs if o["shape"][-1] - len(labels) in (0, 1))
    size = inp["shape"][1]
    offset = out["shape"][-1] - len(labels)  # 1 if the model has a background class

    def predict(image):
        """PIL image -> list of (label index, probability), best first, for all classes."""
        image = ImageOps.exif_transpose(image).convert("RGB")
        if crop == "center":
            image = center_square(image)
        img = resize_bilinear(image, size)
        interp.set_tensor(inp["index"], img.astype(inp["dtype"])[None, ...])
        interp.invoke()
        y = interp.get_tensor(out["index"])[0].astype(np.float32)
        if out["dtype"] == np.uint8:
            y = y / 255.0
        elif y.min() < 0 or y.max() > 1 or abs(y.sum() - 1) > 0.05:
            y = softmax(y)
        order = np.argsort(-y[offset:])
        return [(int(i), float(y[i + offset])) for i in order]

    return labels, predict


def per_image(folder, labels, predict):
    images = sorted(f for f in pathlib.Path(folder).iterdir()
                    if f.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"))
    assert images, f"no images in {folder}"
    for f in images:
        top = predict(Image.open(f))[:3]
        print(f"{f.name:20s} top3=" + ", ".join(f"{labels[i]}={p:.4f}" for i, p in top))


# Dataset class names -> our labels (the same dish under different names). train_model.py uses this too.
ALIASES = {
    "burger": "hamburger", "cholebhature": "chole_bhature", "dosa": "masala_dosa",
    "kathiroll": "kaathi_rolls", "naan": "butter_naan", "pakora": "pakode", "paneer": "kadai_paneer",
    "panipuri": "paani_puri", "pavbhaji": "pav_bhaji", "vadapav": "vada_pav",
}


def names_of(path, fallback_dir):
    """Class names of a Hugging Face parquet dataset (from its metadata or dataset_infos.json)."""
    import json
    import pyarrow.parquet as pq
    meta = pq.read_schema(path).metadata or {}
    if b"huggingface" in meta:
        return json.loads(meta[b"huggingface"])["info"]["features"]["label"]["names"]
    info = json.loads((pathlib.Path(fallback_dir) / "dataset_infos.json").read_text())
    return list(info.values())[0]["features"]["label"]["names"]


def test_files(folder):
    return [f for f in sorted(pathlib.Path(folder).glob("test-*.parquet")) if ".rg64" not in f.name]


def accuracy(folder, labels, predict, limit=0, extras=()):
    import pyarrow.parquet as pq

    import random

    alias = ALIASES
    sources = [(sorted(pathlib.Path(folder).glob("validation-*.parquet")), pathlib.Path(folder))]
    sources += [(test_files(e), pathlib.Path(e)) for e in extras or ()]

    for files, base in sources:
        assert files, f"no validation files in {base}"
        # Map the dataset's label ids to OUR labels.txt order (they can differ).
        to_ours = [labels.index(alias.get(n, n)) for n in names_of(files[0], base)]
        rows = [(img["bytes"], label) for f in files
                for b in pq.ParquetFile(f).iter_batches(batch_size=256, columns=["image", "label"])
                for img, label in zip(b.column("image").to_pylist(), b.column("label").to_pylist())]
        if limit:
            rows = random.Random(0).sample(rows, min(limit, len(rows)))  # same sample every run
        n = top1 = top3 = 0
        for data, label in rows:
            ranked = [i for i, _ in predict(Image.open(io.BytesIO(data)))[:3]]
            truth = to_ours[label]
            n += 1
            top1 += ranked[0] == truth
            top3 += truth in ranked
        print(f"{base.name}: {n} images, top-1 {top1 / n:.4f}, top-3 {top3 / n:.4f}", flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--model", default=ASSETS / "food_model.tflite")
    p.add_argument("--labels", default=ASSETS / "labels.txt")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--images", default=HERE / "samples", help="folder of photos (parity check)")
    g.add_argument("--parquet", help="folder with Food-101 validation parquet (accuracy)")
    p.add_argument("--crop", choices=["squash", "center"], default="squash",
                   help="squash = whole photo resized (as trained); center = central square first")
    p.add_argument("--limit", type=int, default=0, help="accuracy on the first N images only")
    p.add_argument("--extra", action="append", help="extra test parquet folder (e.g. data/indian); repeatable")
    args = p.parse_args()

    labels, predict = load(args.model, args.labels, args.crop)
    if args.parquet:
        accuracy(args.parquet, labels, predict, args.limit, args.extra)
    else:
        per_image(args.images, labels, predict)


if __name__ == "__main__":
    main()
