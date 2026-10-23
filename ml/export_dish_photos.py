"""
Picks one photo per recognised dish for the app's lists and detail screens.

    python export_dish_photos.py --food101 data/food101 --indian data/indian --indian2 data/indian2

For each label it takes up to --candidates training photos, crops each to the square that will be
shown, runs the shipped model on that crop and ranks them (very wide images, usually collages, are
skipped). The best-ranked photo is used unless dish_photo_overrides.csv picks another rank after a
visual review (watermarks, people, collages); --review writes contact sheets of the top 4 for that.
The photo is centre-cropped to a square, resized to --size px and saved as WebP in
app/src/main/assets/dish_photos/<label>.webp. SOURCES.csv records the dataset of each photo.
Datasets are tried in licence order: Indian set 2 (CC0) first, then Indian set 1, then Food-101.
"""
import argparse
import csv
import io
import pathlib

import pyarrow.parquet as pq
from PIL import Image, ImageOps

import verify_tflite as vt

LICENCES = {
    "indian2": "CC0 (bharat-raghunathan/indian-foods-dataset)",
    "indian": "no licence stated (rajistics/indian_food_images)",
    "food101": "Food-101, ETH Zurich (research use)",
}


def train_files(folder):
    return [f for f in sorted(pathlib.Path(folder).glob("train-*.parquet")) if ".rg64" not in f.name]


def candidates(folder, wanted, per_label):
    """{our label: [image bytes, ...]} for the labels in [wanted], reading only the rows needed."""
    files = train_files(folder)
    names = vt.names_of(files[0], folder)
    found = {}
    for f in files:
        pf = pq.ParquetFile(f)
        labels = pf.read(columns=["label"]).column("label").to_pylist()
        rows = []
        for i, l in enumerate(labels):
            ours = vt.ALIASES.get(names[l], names[l])
            if ours in wanted and len(found.setdefault(ours, [])) + rows.count(ours) < per_label:
                rows.append((i, ours))
        if not rows:
            continue
        images = pf.read(columns=["image"]).column("image").take([i for i, _ in rows]).to_pylist()
        for (_, ours), img in zip(rows, images):
            found[ours].append(img["bytes"])
    return {k: v for k, v in found.items() if v}


def square(img, size):
    img = ImageOps.exif_transpose(img).convert("RGB")
    s = min(img.size)
    left, top = (img.width - s) // 2, (img.height - s) // 2
    return img.crop((left, top, left + s, top + s)).resize((size, size), Image.LANCZOS)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--food101", required=True)
    a.add_argument("--indian")
    a.add_argument("--indian2")
    a.add_argument("--candidates", type=int, default=24)
    a.add_argument("--size", type=int, default=320)
    a.add_argument("--quality", type=int, default=72)
    a.add_argument("--out", default=vt.ASSETS / "dish_photos")
    a.add_argument("--overrides", default=pathlib.Path(__file__).parent / "dish_photo_overrides.csv")
    a.add_argument("--review", help="folder for contact sheets of each dish's top 4 candidates")
    args = a.parse_args()

    labels, predict = vt.load(vt.ASSETS / "food_model.tflite", vt.ASSETS / "labels.txt")
    recognised = labels[:122] if len(labels) > 122 else labels
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    overrides = {}
    if pathlib.Path(args.overrides).exists():
        overrides = {r["label"]: int(r["rank"]) for r in csv.DictReader(open(args.overrides))}
    todo = set(recognised)
    rows = []
    ranked_all = {}
    for key, folder in (("indian2", args.indian2), ("indian", args.indian), ("food101", args.food101)):
        if not folder or not todo:
            continue
        pool = candidates(folder, todo, args.candidates)
        for label, blobs in sorted(pool.items()):
            target = labels.index(label)
            scored = []
            for b in blobs:
                img = Image.open(io.BytesIO(b))
                if min(img.size) < 200 or max(img.size) / min(img.size) > 1.6:
                    continue
                crop = square(img, args.size)
                scored.append((dict(predict(crop)).get(target, 0.0), crop))
            if not scored:
                continue
            scored.sort(key=lambda t: -t[0])
            ranked_all[label] = scored[:4]
            rank = min(overrides.get(label, 0), len(scored) - 1)
            best_p, best = scored[rank]
            best.save(out / f"{label}.webp", "WEBP", quality=args.quality, method=6)
            rows.append((label, key, LICENCES[key], round(best_p, 3)))
            todo.discard(label)
            print(f"{label:24s} {key:8s} p={best_p:.2f}", flush=True)

    if args.review:
        rv = pathlib.Path(args.review)
        rv.mkdir(parents=True, exist_ok=True)
        items = sorted(ranked_all.items())
        for k in range(0, len(items), 16):
            sheet = Image.new("RGB", (4 * 132 + 150, len(items[k:k + 16]) * 132), "white")
            from PIL import ImageDraw
            d = ImageDraw.Draw(sheet)
            for r, (label, cands) in enumerate(items[k:k + 16]):
                d.text((4, r * 132 + 56), label[:22], fill="black")
                for c, (_, img) in enumerate(cands):
                    sheet.paste(img.resize((128, 128)), (150 + c * 132, r * 132))
            sheet.save(rv / f"review_{k // 16:02d}.png")

    with open(out / "SOURCES.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["label", "dataset", "licence", "model_confidence"])
        w.writerows(sorted(rows))
    print(f"{len(rows)} photos written to {out}; no photo for: {sorted(todo) or 'none'}")


if __name__ == "__main__":
    main()
