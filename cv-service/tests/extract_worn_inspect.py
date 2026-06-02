#!/usr/bin/env python3
"""
Extract images from the JSON response of POST /scan/diagnose/worn.

Usage:
    curl -s -X POST http://localhost:8001/scan/diagnose/worn \\
         -F "file=@outfit.jpg" > worn_result.json

    python3 tests/extract_worn_inspect.py worn_result.json ./worn_output/

Saves:
    worn_output/annotated.jpg
    worn_output/crop_0_t-shirt_keep.jpg
    worn_output/crop_1_jeans_keep.jpg
    ... etc
"""
import sys
import json
import base64
from pathlib import Path

if len(sys.argv) < 3:
    print("Usage: extract_worn_inspect.py <worn_result.json> <output_dir>")
    sys.exit(2)

data = json.load(open(sys.argv[1]))
out = Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)

# Annotated full image
ann_path = out / "annotated.jpg"
ann_path.write_bytes(base64.b64decode(data["annotated_jpeg_b64"]))
print(f"Saved {ann_path}")

# Print summary table
print(f"\nDiagnosis summary: {data['summary']}")
print(f"\n{'#':<3}  {'DINO label':<28}  {'FC subtype':<14}  {'FC conf':>6}  {'area':>5}  {'verdict':<20}  overlaps")
print(f"{'-'*3}  {'-'*28}  {'-'*14}  {'-'*6}  {'-'*5}  {'-'*20}  {'-'*10}")
for c in data["crops"]:
    overlap_str = str(c["overlaps_with"]) if c["overlaps_with"] else "—"
    print(
        f"#{c['index']:<2}  {c['dino_label']:<28}  {c['fashionclip_subtype']:<14}  "
        f"{c['fashionclip_conf']:>6.3f}  {c['area_fraction']:>5.3f}  "
        f"{c['verdict']:<20}  {overlap_str}"
    )

print(f"\nSuggested keep indices: {data['suggested_keep']}")

# Individual crops
for c in data["crops"]:
    name = f"crop_{c['index']}_{c['fashionclip_subtype'].replace(' ', '_')}_{c['verdict']}.jpg"
    p = out / name
    p.write_bytes(base64.b64decode(c["crop_jpeg_b64"]))
    print(f"Saved {p}")
