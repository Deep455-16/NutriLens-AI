"""
Builds app/src/main/assets/nutrition.csv from USDA FoodData Central (FNDDS survey foods).

    python build_nutrition.py --fndds data/fndds/FoodData_Central_survey_food_csv_2024-10-31

Download: https://fdc.nal.usda.gov/download-datasets (Food and Nutrient Database for Dietary
Studies, CSV). USDA data is public domain. Which FNDDS food(s) and portion each label uses is
chosen by hand in nutrition_sources.csv; this script only does the arithmetic.
"""
import argparse
import csv
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
ASSETS = HERE.parent / "app/src/main/assets"

# FNDDS food_nutrient.csv uses nutrient *numbers* (nutrient.csv "nutrient_nbr"), e.g. 208 = kcal.
NUTRIENTS = {"208": "calories", "203": "protein", "205": "carbs", "204": "fat",
             "291": "fiber", "269": "sugar", "307": "sodium", "606": "satFat",
             "301": "calcium", "303": "iron", "306": "potassium", "401": "vitaminC", "418": "vitaminB12"}
MICROS = ["calcium", "iron", "potassium", "vitaminC", "vitaminB12"]  # mg, mg, mg, mg, ug

# Portion descriptions that are measuring units, not something you'd eat.
SKIP_PORTIONS = ("Quantity not specified", "1 surface inch", "1 cubic inch", "1 oz", "1 serving", "1 tablespoon",
                 "1 teaspoon", "1 fl oz", "1 gram")

# No FNDDS food fits; typical home-recipe values per serving, clearly labelled as estimates.
ESTIMATES = {
    "dhokla": dict(grams=120, calories=190, protein=8.4, carbs=27.6, fat=5.4, fiber=3.0,
                   sugar=6.0, sodium=600, satFat=0.8, calcium=60, iron=2.4, potassium=250,
                   vitaminC=3, vitaminB12=0),
}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--fndds", required=True)
    p.add_argument("--labels", default=ASSETS / "labels.txt")
    p.add_argument("--out", default=ASSETS / "nutrition.csv")
    p.add_argument("--portions-out", default=ASSETS / "portions.csv")
    args = p.parse_args()
    d = pathlib.Path(args.fndds)

    names = {r["fdc_id"]: r["description"] for r in csv.DictReader(open(d / "food.csv"))}
    per100 = {}
    for r in csv.DictReader(open(d / "food_nutrient.csv")):
        if r["nutrient_id"] in NUTRIENTS:
            per100.setdefault(r["fdc_id"], {})[NUTRIENTS[r["nutrient_id"]]] = float(r["amount"] or 0)
    portions = {}
    for r in csv.DictReader(open(d / "food_portion.csv")):
        portions.setdefault(r["fdc_id"], []).append((r["portion_description"], float(r["gram_weight"] or 0)))

    def grams_for(fdc, amount):
        if amount.endswith("g") and amount[:-1].replace(".", "").isdigit():
            return float(amount[:-1])
        want = "Quantity not specified" if amount == "qns" else amount.removeprefix("p=")
        for desc, g in portions.get(fdc, []):
            if desc.startswith(want) and g > 0:
                return g
        raise SystemExit(f"no portion '{amount}' for {fdc} {names.get(fdc)}: {portions.get(fdc)}")

    rows = [r for r in csv.DictReader(l for l in open(HERE / "nutrition_sources.csv") if not l.startswith("#"))]
    labels = [l.strip() for l in open(args.labels) if l.strip()]
    missing = set(labels) - {r["label"] for r in rows}
    assert not missing, f"labels without a nutrition source: {sorted(missing)}"

    out = []
    portion_rows = []
    for r in rows:
        if r["label"] not in labels:
            continue
        if r["components"] == "estimate":
            e = ESTIMATES[r["label"]]
            total = {k: e[k] for k in NUTRIENTS.values()}
            grams = e["grams"]
            source = "Estimate from typical recipes (no USDA match)"
        else:
            total = dict.fromkeys(NUTRIENTS.values(), 0.0)
            grams = 0.0
            parts = []
            for comp in r["components"].split(" + "):
                fdc, amount = comp.split(":", 1)
                g = grams_for(fdc, amount)
                grams += g
                for k, v in per100[fdc].items():
                    total[k] += v * g / 100
                parts.append(f"{names[fdc]} ({g:.0f} g)")
            source = "USDA FNDDS: " + " + ".join(parts)
        out.append([r["label"], round(grams), round(total["calories"]),
                    *(round(total[k], 1) for k in ("protein", "carbs", "fat", "fiber", "sugar")),
                    round(total["sodium"]), round(total["satFat"], 1),
                    *(round(total[k], 1) for k in MICROS), r["serving"], source])

        # Portion choices: the standard serving first, then USDA's own portions for
        # single-food rows (composites only have their standard serving).
        seen, seen_g = {r["serving"].lower()}, {round(grams)}
        portion_rows.append([r["label"], r["serving"], round(grams)])
        comps = r["components"].split(" + ")
        if r["components"] != "estimate" and len(comps) == 1:
            fdc = comps[0].split(":")[0]
            for desc, g in sorted(portions.get(fdc, []), key=lambda x: x[1]):
                if g <= 0 or desc.startswith(SKIP_PORTIONS) or desc.lower() in seen or round(g) in seen_g:
                    continue
                seen.add(desc.lower())
                seen_g.add(round(g))
                portion_rows.append([r["label"], desc, round(g)])

    with open(args.out, "w", newline="") as f:
        f.write("# Per serving. Built by ml/build_nutrition.py from USDA FoodData Central FNDDS 2021-2023\n")
        f.write("# (public domain) unless the source column says otherwise. Values are typical, not measured.\n")
        w = csv.writer(f)
        w.writerow(["label", "servingGrams", "calories", "protein", "carbs", "fat", "fiber",
                    "sugar", "sodiumMg", "satFat", "calciumMg", "ironMg", "potassiumMg",
                    "vitaminCMg", "vitaminB12Ug", "serving", "source"])
        w.writerows(out)
    with open(args.portions_out, "w", newline="") as f:
        f.write("# Portion choices per food (first = standard serving). From USDA FNDDS food_portion.csv.\n")
        w = csv.writer(f)
        w.writerow(["label", "description", "grams"])
        w.writerows(portion_rows)
    print(f"wrote {len(out)} foods to {args.out} and {len(portion_rows)} portions to {args.portions_out}")


if __name__ == "__main__":
    main()
