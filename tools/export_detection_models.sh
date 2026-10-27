#!/usr/bin/env bash
# Rebuilds the bundled detection models (ONNX + ncnn graphs) from upstream weights.
#
# The APK ships these files in app/src/main/assets/:
#   yolo26n_256.ncnn.param/.bin        (ncnn only — the .onnx stays in build/model-export)
#   nanodet_320.ncnn.param/.bin        (ncnn only, same reason)
#
# Shipping the matching .onnx as well cost 14.6 MB and never won the backend shoot-out on this
# hardware, so it is not copied. Add it back — and set hasOnnx = true in DetectorFactory — if you
# need the ONNX Runtime fallback for these two models.
#
# Run from the repository root. Needs network access (~1 GB of wheels the first time) and
# writes into build/model-export/ so nothing lands in the working tree.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/model-export"
VENV="$ROOT/.venv-model"
cd "$WORK" 2>/dev/null || { mkdir -p "$WORK"; cd "$WORK"; }

# 1. Toolchain: torch for the exports, ultralytics for YOLO, pnnx for ONNX -> ncnn.
[ -d "$VENV" ] || uv venv --python 3.12 "$VENV"
uv pip install --python "$VENV/bin/python" torch torchvision ultralytics onnx onnxslim pnnx ncnn onnxruntime numpy

# 2. YOLO26n @256. The export prints the output shape; it must be (1, 84, 1344), i.e. the
#    4+80 channel layout that YoloHead decodes. An end-to-end export would be (1, 300, 6)
#    instead and needs a different decoder, so do not pass nms=True.
"$VENV/bin/python" - <<'PY'
from ultralytics import YOLO
m = YOLO("yolo26n.pt")
m.export(format="onnx", imgsz=256, opset=12, simplify=True)
m.export(format="ncnn", imgsz=256)
PY
cp yolo26n_ncnn_model/model.ncnn.param "$ROOT/app/src/main/assets/yolo26n_256.ncnn.param"
cp yolo26n_ncnn_model/model.ncnn.bin   "$ROOT/app/src/main/assets/yolo26n_256.ncnn.bin"

# 3. NanoDet-Plus-m @320: official ONNX (Apache-2.0), then pnnx to ncnn.
#    Output must be (1, 2125, 112): 80 sigmoid class scores, then 4 sides x 8 distribution
#    bins. NanoDetHead decodes that layout; a YOLO decoder cannot.
curl -fsSL -o nanodet-plus-m_320.onnx \
  https://github.com/RangiLyu/nanodet/releases/download/v1.0.0-alpha-1/nanodet-plus-m_320.onnx
"$VENV/bin/pnnx" nanodet-plus-m_320.onnx inputshape=[1,3,320,320]
cp nanodet_plus_m_320.ncnn.param "$ROOT/app/src/main/assets/nanodet_320.ncnn.param"
cp nanodet_plus_m_320.ncnn.bin   "$ROOT/app/src/main/assets/nanodet_320.ncnn.bin"

echo "done: assets updated. Rebuild the APK to pick them up."
