"""
Re-exports a trained Keras model to TFLite with TWO outputs, without retraining:

    probs      [1, N]   class probabilities (as before)
    embedding  [1, D]   the pooled image features ("fingerprint") before the classifier

The app uses the embedding to remember the user's corrections (nearest-neighbour re-ranking).

    python export_tflite.py --keras export_v2/food_model.keras --out export_v2/food_model.tflite
"""
import argparse

import tensorflow as tf


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--keras", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    model = tf.keras.models.load_model(args.keras)
    pool = next(l for l in model.layers if isinstance(l, tf.keras.layers.GlobalAveragePooling2D))
    two = tf.keras.Model(model.input, [model.output, pool.output])

    converter = tf.lite.TFLiteConverter.from_keras_model(two)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite = converter.convert()
    open(args.out, "wb").write(tflite)

    it = tf.lite.Interpreter(model_content=tflite)
    it.allocate_tensors()
    for d in it.get_output_details():
        print("output", d["index"], d["name"], d["shape"], d["dtype"])
    print(f"wrote {args.out} ({len(tflite) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
