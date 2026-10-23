# Model card: `food_model.tflite`

Every number below was measured on 2026-10-21. The raw outputs are in `ml/export_v3/` (training log,
`tflite_eval.txt`, `calibrate.txt`, `new_classes_eval.txt`) and `ml/export_v2/` (OpenCV and
personalisation experiments). No number here comes from anywhere else.

## What it is

| | |
|---|---|
| Task | Single-label food classification, **122 classes** |
| Classes | 101 Food-101 dishes + 21 Indian dishes (`app/src/main/assets/labels.txt`, in output order) |
| Architecture | Keras `MobileNetV3Large` (ImageNet weights), global average pooling, dropout 0.25, dense softmax |
| Input | `[1, 224, 224, 3]` float32, raw RGB 0..255 (the model rescales internally) |
| Outputs | `[1, 122]` probabilities, and `[1, 960]` image embedding (used for personalisation). The app picks each output by its shape. |
| File | 6.2 MB, float16 weights |
| Calibration | Temperature T = 0.75, stored in `assets/model_info.json` |
| Training script | `ml/train_model.py` (TensorFlow 2.15, Apple M4 CPU) |

## Training data

| Dataset | Train | Test | Licence |
|---|---|---|---|
| Food-101 (`ethz/food101` on Hugging Face) | 75,750 | 25,250 | research dataset (ETH Zurich) |
| Indian food, set 1 (`rajistics/indian_food_images`, mirrored from Kaggle) | 5,328 | 941 | **none stated** |
| Indian food, set 2 (`bharat-raghunathan/indian-foods-dataset`) | 3,809 | 961 | CC0 |

Where the datasets use different names for the same dish they are merged (e.g. `burger` →
`hamburger`, `dosa` → `masala_dosa`). One table, `ALIASES` in `ml/verify_tflite.py`, is used for
both training and evaluation. The two smaller Indian sets are repeated twice per epoch.

## Training

1. **117 classes** (Food-101 + Indian set 1), from ImageNet weights: 2 epochs training only the head
   (Adam 1e-3), then 8 epochs fine-tuning the top 60 backbone layers with BatchNorm frozen
   (Adam 1e-4). Label smoothing 0.1. Augmentation: horizontal flip, rotation ±8%, zoom ±15%.
2. **122 classes** (+ Indian set 2): continued from the 117-class model. The 117 known classes keep
   their trained weights and 5 new ones are added (biryani, dabeli, dal, kofta, vada pav). Then 1 head
   epoch and 3 fine-tune epochs. Validation accuracy measured by the run: **77.6%**.

```bash
python train_model.py --parquet data/food101 --extra data/indian --extra data/indian2 \
    --init export_v2/food_model.keras --init-labels export_v2/labels.txt \
    --out export_v3 --cpu --head-epochs 1 --finetune-epochs 3
```

## Accuracy (shipped `.tflite`, app preprocessing)

| Test set | Photos | Top-1 | Top-3 |
|---|---|---|---|
| Food-101 validation | 25,250 | **76.7%** | **90.2%** |
| Indian set 1 test | 941 | **86.0%** | **94.5%** |
| Indian set 2 test | 961 | **91.1%** | **97.4%** |

Previous models, on the same Food-101 split: 64.7% (MobileNetV3-Small, int8), then 75.8% (117 classes).

The five newly added dishes (Indian set 2 test split):

| Dish | Photos | Top-1 | Top-3 | Most often confused with |
|---|---|---|---|---|
| Vada pav | 108 | 92% | 98% | dabeli |
| Kofta | 134 | 91% | 97% | pakode |
| Dabeli | 44 | 89% | 93% | – |
| Dal | 53 | 75% | 94% | kadai paneer, dal makhani |
| Biryani | 46 | 70% | 91% | fried rice |

Reproduce: `python ml/verify_tflite.py --parquet ml/data/food101 --extra ml/data/indian --extra ml/data/indian2`.

## Confidence calibration

The raw softmax was over-confident. A single temperature (T = 0.75) was fitted on half of a
4,902-photo held-out sample and checked on the other half (`ml/calibrate.py`):

| | Before | After |
|---|---|---|
| Expected calibration error | 0.108 | **0.018** |

| Shown confidence | Photos | Actually correct |
|---|---|---|
| under 40% | 183 | 26% |
| 40–70% | 423 | 57% |
| 70% and above | 1,845 | 93% |

Below 40% the app says "Not sure" and offers the next guesses as one-tap corrections.

## Android ↔ Python parity

The app resizes in Kotlin (`resizeBilinearRgb`) with the same maths as training: bilinear,
half-pixel centres, no antialiasing. With float16 weights there is no quantised-kernel
difference left. For the same photo the phone's log line (`adb logcat -s FoodClassifier`) and
`verify_tflite.py` give the same probabilities to 4 decimals. Example: `pad_thai=0.3203,
takoyaki=0.1218, biryani=0.1094`.

## OpenCV photo check (measured, so only helpful steps are used)

`ml/vision_eval.py`, 2,441 held-out photos, 117-class model:

| Step | Result | Used in the app? |
|---|---|---|
| Plate crop (Hough circles) | 77.3% vs 79.2% whole photo | No. Only a starting box when the user marks a food. |
| CLAHE on normal photos | 74.5% vs 79.2% | No |
| Auto-gamma on dark photos | 71.9% → 74.8% | Yes, only when mean brightness < 60 |
| Blur (Laplacian variance) | accuracy drops from 79% to 71% at σ=3 (variance ≈ 3) | Warning below 8 |

## Personalisation (learning from corrections)

When the user corrects or confirms a food, the photo's 960-number embedding is stored (not the
picture; at most 500). Later scans are re-ranked by their nearest stored neighbours (k = 5,
cosine ≥ 0.6, weight up to 0.6). `ml/personalize_eval.py`:

| Scenario | Without | With |
|---|---|---|
| Dish the model got wrong, corrected once, photographed again | 18.0% | **96.7%** |
| Dishes never logged (must not get worse) | 76.3% | 77.0% |
| Different photos of dishes logged before | 81.5% | 81.5% |

It fixes repeat mistakes on the user's own food without hurting anything else. It does not make
the model generally better at a dish.

## Speed

`ms=` in logcat on the Android 34 arm64 **emulator** (M4 Mac): about 20–40 ms per photo.
This is not a phone measurement. Measure on a real device before quoting latency.

## Limitations

- **122 dishes only.** Anything else is forced into the nearest known class. The top-3 list and
  "Change food" are always available.
- **One dish per photo.** For a thali, the user marks each food on the photo and each region is
  recognised separately. There is no automatic detector.
- **Confidently wrong happens.** About 7% of photos shown at 70% or more are wrong.
- **Biryani vs fried rice and dal vs other curries** are the weakest of the new dishes.
- **Portion size is not estimated from the photo.** Nutrition = the dataset's portion × the
  amount the user chooses.
- **Licensing.** Indian set 1 states no licence. Fine for a college project; check before any
  commercial use.
