"""
Train a MobileNetV3 food classifier on Food-101 and export it for the NutriLens AI app.

Outputs (in --out, default ./export):
    food_model.tflite   -> copy to app/src/main/assets/
    labels.txt          -> copy to app/src/main/assets/ (replaces the bundled one)

The model is exported with float32 input/output. MobileNetV3 from Keras rescales pixels
internally, so it expects RAW 0..255 values - this matches FoodClassifier.kt
(FLOAT_MEAN = 0, FLOAT_STD = 1). Do not add your own normalization.

Written for TensorFlow 2.15 (pip install -r requirements.txt). Use a GPU (e.g. a free Colab
runtime, or an Apple Silicon Mac with tensorflow-metal): Food-101 has ~100k images, ~5 GB.

Data sources:
    default            tensorflow-datasets downloads Food-101 from data.vision.ee.ethz.ch
                       (can be very slow)
    --parquet DIR      local copy of the Hugging Face mirror (ethz/food101, data/*.parquet),
                       same images and labels, but the label ORDER differs slightly
                       (cheesecake/cheese_plate are swapped). Always copy the labels.txt
                       written next to the model; never mix a model and labels.txt from
                       different runs.
    --indian DIR       optional extra classes from rajistics/indian_food_images (Hugging Face;
                       images scraped from the web, no license stated). Its 20 classes are
                       merged in: pizza / samosa / fried_rice already exist, burger becomes
                       hamburger, the other 16 are added (117 classes in total).

Defaults (MobileNetV3-Large, label smoothing, float16 export) are what the bundled model
used; --arch small reproduces the first, smaller model.

NOTE: this script has not been run by the person who wrote it. Report the validation
accuracy that YOUR run prints - do not quote numbers you did not measure.
"""
import argparse
import json
import pathlib
import random

import tensorflow as tf

IMG = 224
AUTOTUNE = tf.data.AUTOTUNE


def load_tfds():
    import tensorflow_datasets as tfds

    (train, val), info = tfds.load(
        "food101", split=["train", "validation"], as_supervised=True, with_info=True
    )
    return train, val, info.features["label"].names


# Extra-dataset class name -> the app's label (same dish, different spelling). Names not listed
# here become new classes.
from verify_tflite import ALIASES  # noqa: E402  (one alias table for training and evaluation)


def small_row_groups(path):
    """Rewrites a parquet file with ~64-row groups once (cached next to it) so it can be shuffled."""
    import pyarrow.parquet as pq

    out = path.with_name(path.stem + ".rg64.parquet")
    if not out.exists():
        pq.write_table(pq.read_table(path, columns=["image", "label"]), out, row_group_size=64)
    return out


def load_parquet(directory, extras=None):
    """Reads Hugging Face parquet shards (image bytes + int label) as (image, label)."""
    import pyarrow.parquet as pq

    directory = pathlib.Path(directory)
    meta = pq.read_schema(next(directory.glob("train-*.parquet"))).metadata
    names = json.loads(meta[b"huggingface"])["info"]["features"]["label"]["names"]

    # (files, label remap, repeat) per source; the Indian set is small, so it is shown twice.
    sources = {"train": [(sorted(directory.glob("train-*.parquet")), None, 1)],
               "validation": [(sorted(directory.glob("validation-*.parquet")), None, 1)]}
    for extra in extras or []:
        extra = pathlib.Path(extra)
        files = lambda prefix: [f for f in sorted(extra.glob(f"{prefix}-*.parquet")) if ".rg64" not in f.name]
        meta = pq.read_schema(files("train")[0]).metadata or {}
        if b"huggingface" in meta:
            extra_names = json.loads(meta[b"huggingface"])["info"]["features"]["label"]["names"]
        else:
            info = json.loads((extra / "dataset_infos.json").read_text())
            extra_names = list(info.values())[0]["features"]["label"]["names"]
        remap = []
        for name in extra_names:
            name = ALIASES.get(name, name)
            if name not in names:
                names.append(name)
            remap.append(names.index(name))
        # Small datasets are shown twice per epoch so their dishes are not drowned out.
        sources["train"].append(([small_row_groups(f) for f in files("train")], remap, 2))
        sources["validation"].append(([small_row_groups(f) for f in files("test") + files("validation")], remap, 1))

    def split(prefix, shuffle):
        groups = []
        for paths, remap, repeat in sources[prefix]:
            assert paths, f"no {prefix} parquet files"
            for p in paths:
                f = pq.ParquetFile(p)
                groups += [(f, g, remap) for g in range(f.num_row_groups)] * repeat

        # The shards are grouped by label (~13 classes each), so streaming them in order
        # would feed the model a few classes at a time. Visiting the ~100-row groups in a new
        # random order every epoch (plus the shuffle buffer) mixes classes without
        # loading 4 GB into memory.
        def rows():
            order = random.sample(groups, len(groups)) if shuffle else groups
            for f, g, remap in order:
                t = f.read_row_group(g, columns=["image", "label"])
                for img, label in zip(t.column("image").to_pylist(), t.column("label").to_pylist()):
                    yield img["bytes"], remap[label] if remap else label

        ds = tf.data.Dataset.from_generator(
            rows,
            output_signature=(tf.TensorSpec((), tf.string), tf.TensorSpec((), tf.int64)),
        )
        return ds.map(
            lambda b, y: (tf.io.decode_image(b, channels=3, expand_animations=False), y),
            num_parallel_calls=AUTOTUNE,
        )

    return split("train", shuffle=True), split("validation", shuffle=False), names


def build_datasets(batch, parquet=None, extras=None):
    train, val, names = load_parquet(parquet, extras) if parquet else load_tfds()
    num_classes = len(names)

    def prep(image, label):
        image = tf.image.resize(image, (IMG, IMG))
        return tf.cast(image, tf.float32), tf.one_hot(label, num_classes)  # keep 0..255

    augment = tf.keras.Sequential(
        [
            tf.keras.layers.RandomFlip("horizontal"),
            tf.keras.layers.RandomRotation(0.08),
            tf.keras.layers.RandomZoom(0.15),
        ]
    )

    train_ds = (
        train.shuffle(10_000)
        .map(prep, num_parallel_calls=AUTOTUNE)
        .batch(batch)
        .map(lambda x, y: (augment(x, training=True), y), num_parallel_calls=AUTOTUNE)
        .prefetch(AUTOTUNE)
    )
    val_ds = val.map(prep, num_parallel_calls=AUTOTUNE).batch(batch).prefetch(AUTOTUNE)
    return train_ds, val_ds, names


# Legacy Adam: tensorflow-metal (Apple GPU) crashes when the new Keras optimizer is
# re-compiled for fine-tuning ("Mutation::Apply error"). Same algorithm.
Adam = getattr(tf.keras.optimizers, "legacy", tf.keras.optimizers).Adam


def build_model(num_classes, arch="large"):
    backbone = tf.keras.applications.MobileNetV3Large if arch == "large" else tf.keras.applications.MobileNetV3Small
    base = backbone(
        input_shape=(IMG, IMG, 3),
        include_top=False,
        weights="imagenet",
        include_preprocessing=True,  # expects raw 0..255 input
    )
    base.trainable = False
    inputs = tf.keras.Input(shape=(IMG, IMG, 3))
    x = base(inputs, training=False)
    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    x = tf.keras.layers.Dropout(0.25)(x)
    outputs = tf.keras.layers.Dense(num_classes, activation="softmax")(x)
    return tf.keras.Model(inputs, outputs), base


def init_from(model, path, labels_path, names):
    """
    Copies every weight from an earlier trained model; for the final classifier, the columns of
    classes it already knew are copied too (new classes start from scratch). Returns how many
    classes were carried over.
    """
    old = tf.keras.models.load_model(path)
    old_labels = [l.strip() for l in open(labels_path) if l.strip()]
    dense_new = model.layers[-1]
    dense_old = old.layers[-1]
    for new_layer, old_layer in zip(model.layers[:-1], old.layers[:-1]):
        new_layer.set_weights(old_layer.get_weights())
    kernel, bias = [w.copy() for w in dense_new.get_weights()]
    old_kernel, old_bias = dense_old.get_weights()
    carried = 0
    for i, name in enumerate(names):
        if name in old_labels:
            j = old_labels.index(name)
            kernel[:, i], bias[i] = old_kernel[:, j], old_bias[j]
            carried += 1
    dense_new.set_weights([kernel, bias])
    return carried


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="export")
    parser.add_argument("--parquet", help="folder with Food-101 parquet shards (see docstring)")
    parser.add_argument("--extra", action="append", default=[],
                        help="extra parquet dataset folder (repeatable), e.g. data/indian data/indian2")
    parser.add_argument("--indian", help="same as --extra (kept for older commands)")
    parser.add_argument("--init", help="continue from this trained .keras model instead of ImageNet")
    parser.add_argument("--init-labels", help="labels.txt of the --init model (to carry over its classes)")
    parser.add_argument("--arch", choices=["large", "small"], default="large")
    parser.add_argument("--unfreeze", type=int, default=60, help="backbone layers to fine-tune")
    parser.add_argument("--quant", choices=["float16", "dynamic"], default="float16",
                        help="float16 keeps float maths on the phone; dynamic is smaller but less exact")
    parser.add_argument("--smoke", action="store_true", help="a few batches only, to test the pipeline")
    parser.add_argument("--cpu", action="store_true",
                        help="ignore the GPU (tensorflow-metal 1.1 crashes during fine-tuning)")
    parser.add_argument("--batch", type=int, default=64)
    parser.add_argument("--head-epochs", type=int, default=2)
    parser.add_argument("--finetune-epochs", type=int, default=8)
    args = parser.parse_args()
    if args.cpu:
        tf.config.set_visible_devices([], "GPU")

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    extras = args.extra + ([args.indian] if args.indian else [])
    train_ds, val_ds, names = build_datasets(args.batch, args.parquet, extras)
    if args.smoke:
        train_ds, val_ds = train_ds.take(8), val_ds.take(4)
    (out / "labels.txt").write_text("\n".join(names) + "\n")
    print(f"{len(names)} classes written to {out / 'labels.txt'}")

    model, base = build_model(len(names), args.arch)
    if args.init:
        carried = init_from(model, args.init, args.init_labels, names)
        print(f"continued from {args.init}: {carried} of {len(names)} classes keep their trained weights")
    loss = tf.keras.losses.CategoricalCrossentropy(label_smoothing=0.1)

    # 1) train only the new classification head
    model.compile(Adam(1e-3), loss=loss, metrics=["accuracy"])
    model.fit(train_ds, validation_data=val_ds, epochs=args.head_epochs)

    # 2) fine-tune the top of the backbone with a small learning rate
    base.trainable = True
    for layer in base.layers[:-args.unfreeze]:
        layer.trainable = False
    for layer in base.layers:
        if isinstance(layer, tf.keras.layers.BatchNormalization):
            layer.trainable = False
    model.compile(Adam(1e-4), loss=loss, metrics=["accuracy"])
    model.fit(train_ds, validation_data=val_ds, epochs=args.finetune_epochs)

    loss_value, acc = model.evaluate(val_ds)
    print(f"Validation accuracy measured by this run: {acc:.4f}")

    model.save(out / "food_model.keras")  # keep it: re-exporting later needs no retraining

    # 3) export to TensorFlow Lite (float32 input/output either way) with two outputs: class
    #    probabilities and the pooled image embedding (the app's personal correction memory).
    pool = next(l for l in model.layers if isinstance(l, tf.keras.layers.GlobalAveragePooling2D))
    converter = tf.lite.TFLiteConverter.from_keras_model(tf.keras.Model(model.input, [model.output, pool.output]))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    if args.quant == "float16":
        converter.target_spec.supported_types = [tf.float16]
    tflite = converter.convert()
    (out / "food_model.tflite").write_bytes(tflite)

    interpreter = tf.lite.Interpreter(model_content=tflite)
    interpreter.allocate_tensors()
    print("input :", interpreter.get_input_details()[0]["shape"], interpreter.get_input_details()[0]["dtype"])
    for o in interpreter.get_output_details():
        print("output:", o["shape"], o["dtype"])
    print(f"Done. Copy {out / 'food_model.tflite'} and {out / 'labels.txt'} to app/src/main/assets/")


if __name__ == "__main__":
    main()
