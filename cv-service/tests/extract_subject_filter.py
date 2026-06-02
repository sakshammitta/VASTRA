#!/usr/bin/env python3
"""
Extract images + summary from POST /scan/diagnose/subject response.

Usage:
    curl -s -X POST http://localhost:8001/scan/diagnose/subject \\
         -F "file=@outfit.jpg" > subject_result.json
    python3 tests/extract_subject_filter.py subject_result.json ./subject_out/

Saves:
    subject_out/before.jpg  — all raw garment boxes + primary-subject box
    subject_out/after.jpg   — only kept garment boxes (subject-filtered)
And prints the per-detection table + summary.
"""
import sys
import json
import base64
from pathlib import Path

if len(sys.argv) < 3:
    print("Usage: extract_subject_filter.py <subject_result.json> <output_dir>")
    sys.exit(2)

data = json.load(open(sys.argv[1]))
out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)

(out / "before.jpg").write_bytes(base64.b64decode(data["annotated_before_b64"]))
(out / "after.jpg").write_bytes(base64.b64decode(data["annotated_after_b64"]))

print(f"Image: {data['image_width']}x{data['image_height']}")
print(f"Primary subject: {data['primary_subject_bbox']}  area={data['primary_subject_area']}")
print(f"\n{'#':<3}  {'DINO label':<26}  {'conf':>5}  {'area':>5}  {'contain':>7}  "
      f"{'FC cat/subtype':<22}  {'FC conf':>6}  {'result':<16}")
print(f"{'-'*3}  {'-'*26}  {'-'*5}  {'-'*5}  {'-'*7}  {'-'*22}  {'-'*6}  {'-'*16}")
for d in data["detections"]:
    fc = f"{d['fashionclip_category']}/{d['fashionclip_subtype']}" if d["kept"] else "—"
    result = "KEEP" if d["kept"] else f"reject:{d['reject_reason']}"
    print(f"#{d['index']:<2}  {d['dino_label']:<26}  {d['dino_conf']:>5.3f}  "
          f"{d['area_fraction']:>5.3f}  {d['containment_in_subject']:>7.3f}  "
          f"{fc:<22}  {d['fashionclip_conf']:>6.3f}  {result:<16}")

print(f"\nKept indices: {data['kept_indices']}")
print(f"\nSummary: {data['summary']}")
print(f"\nSaved before.jpg and after.jpg to {out}/")
