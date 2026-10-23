"""
Does "learning from the user's corrections" help, and with which settings?

    python personalize_eval.py --parquet data/food101 --extra data/indian

Simulates a user who has saved/corrected MEMORY photos of each of CLASSES dishes, then measures
on *different* photos: accuracy on those dishes with and without nearest-neighbour re-ranking,
and on dishes the user never logged (re-ranking must not hurt those). The re-ranking maths is
the same as PersonalReranker.kt in the app.
"""
import argparse
import io
import random

import numpy as np
from PIL import Image, ImageOps

import verify_tflite as vt
import vision_eval as ve

K = 5
ALPHA_MAX = 0.6


def embedder(model, labels):
    it = vt.Interpreter(model_path=str(model), num_threads=4)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    outs = it.get_output_details()
    probs_o = next(o for o in outs if o["shape"][-1] == len(labels))
    emb_o = next(o for o in outs if o is not probs_o)

    def run(data):
        img = vt.resize_bilinear(ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB"), inp["shape"][1])
        it.set_tensor(inp["index"], img.astype(np.float32)[None])
        it.invoke()
        e = it.get_tensor(emb_o["index"])[0].astype(np.float32)
        return it.get_tensor(probs_o["index"])[0].astype(np.float32), e / max(np.linalg.norm(e), 1e-12)

    return run


def rerank(p, e, mem_e, mem_y, n_labels, threshold):
    """Same as PersonalReranker.rerank in the app."""
    if len(mem_y) == 0:
        return p
    sims = mem_e @ e
    idx = [i for i in np.argsort(-sims)[:K] if sims[i] >= threshold]
    if not idx:
        return p
    vote = np.zeros(n_labels, np.float32)
    for i in idx:
        vote[mem_y[i]] += sims[i]
    vote /= vote.sum()
    alpha = ALPHA_MAX * min(1.0, (sims[idx[0]] - threshold) / (1 - threshold))
    return (1 - alpha) * p + alpha * vote


def augment(data, rng):
    """A second photo of the same plate: different framing, mirror, light and slight rotation."""
    img = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")
    w, h = img.size
    f = rng.uniform(0.8, 0.95)
    cw, ch = int(w * f), int(h * f)
    x, y = rng.randint(0, w - cw), rng.randint(0, h - ch)
    img = img.crop((x, y, x + cw, y + ch)).rotate(rng.uniform(-8, 8), resample=Image.BILINEAR)
    if rng.random() < 0.5:
        img = ImageOps.mirror(img)
    arr = np.clip(np.asarray(img, np.float32) * rng.uniform(0.85, 1.15), 0, 255).astype(np.uint8)
    buf = io.BytesIO()
    Image.fromarray(arr).save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--parquet", required=True)
    a.add_argument("--extra", action="append")
    a.add_argument("--classes", type=int, default=40)
    a.add_argument("--memory", type=int, default=3)
    a.add_argument("--test", type=int, default=10)
    args = a.parse_args()

    labels, _ = vt.load(vt.ASSETS / "food_model.tflite", vt.ASSETS / "labels.txt")
    run = embedder(vt.ASSETS / "food_model.tflite", labels)
    data = ve.sample(args.parquet, args.extra, labels, 20000, seed=2)
    by_class = {}
    for b, y in data:
        by_class.setdefault(y, []).append(b)
    rng = random.Random(3)
    chosen = rng.sample(sorted(c for c, v in by_class.items() if len(v) >= args.memory + args.test), args.classes)
    others = [c for c in by_class if c not in chosen]

    def embed_all(items):
        out = [(run(b), y) for b, y in items]
        return np.array([o[0][0] for o in out]), np.array([o[0][1] for o in out]), np.array([o[1] for o in out])

    mem, test, control = [], [], []
    for c in chosen:
        photos = rng.sample(by_class[c], args.memory + args.test)
        mem += [(b, c) for b in photos[:args.memory]]
        test += [(b, c) for b in photos[args.memory:]]
    for c in rng.sample(others, min(60, len(others))):
        control += [(b, c) for b in rng.sample(by_class[c], min(5, len(by_class[c])))]

    _, mem_e, mem_y = embed_all(mem)
    test_p, test_e, test_y = embed_all(test)
    ctl_p, ctl_e, ctl_y = embed_all(control)
    print(f"memory {len(mem_y)} photos of {args.classes} dishes; test {len(test_y)}; control (dishes never logged) {len(ctl_y)}")

    def acc(P, E, Y, t):
        if t is None:
            return float(np.mean(P.argmax(1) == Y))
        return float(np.mean([rerank(p, e, mem_e, mem_y, len(labels), t).argmax() == y for p, e, y in zip(P, E, Y)]))

    same = [float(mem_e[i] @ test_e[j]) for i in range(len(mem_y)) for j in range(len(test_y)) if mem_y[i] == test_y[j]]
    diff = [float(mem_e[i] @ ctl_e[j]) for i in range(len(mem_y)) for j in range(len(ctl_y))]
    print(f"cosine similarity: same dish median {np.median(same):.2f} (p90 {np.percentile(same, 90):.2f}); "
          f"different dish median {np.median(diff):.2f} (p99 {np.percentile(diff, 99):.2f})")
    print("Scenario 1: other photos of dishes the user logged before (different places/plates)")
    print(f"  {'threshold':>9}  logged dishes top-1   never-logged dishes top-1")
    print(f"  {'none':>9}  {acc(test_p, test_e, test_y, None):.3f}                {acc(ctl_p, ctl_e, ctl_y, None):.3f}")
    for t in (0.5, 0.6, 0.7, 0.8, 0.9):
        print(f"  {t:>9}  {acc(test_p, test_e, test_y, t):.3f}                {acc(ctl_p, ctl_e, ctl_y, t):.3f}", flush=True)

    # Scenario 2: the model got a photo wrong, the user corrected it, and later photographs the
    # same dish again (same kitchen/plate). Memory = one re-framed copy, test = another.
    wrong = []
    for b, y in rng.sample(data, len(data)):
        if len(wrong) >= 150:
            break
        p, _ = run(b)
        if p.argmax() != y:
            wrong.append((b, y))
    mem_e, mem_y = np.array([run(augment(b, rng))[1] for b, _ in wrong]), np.array([y for _, y in wrong])
    again = [(augment(b, rng), y) for b, y in wrong]
    ag_p, ag_e, ag_y = embed_all(again)
    print(f"Scenario 2: {len(wrong)} dishes the model got wrong, corrected once, photographed again")
    print(f"  {'threshold':>9}  repeat photos top-1  never-logged dishes top-1")
    print(f"  {'none':>9}  {acc(ag_p, ag_e, ag_y, None):.3f}                {acc(ctl_p, ctl_e, ctl_y, None):.3f}")
    for t in (0.5, 0.6, 0.7, 0.8):
        print(f"  {t:>9}  {acc(ag_p, ag_e, ag_y, t):.3f}                {acc(ctl_p, ctl_e, ctl_y, t):.3f}", flush=True)


if __name__ == "__main__":
    main()
