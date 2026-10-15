# ONNX models

## What is bundled

| File | Input | Output | Notes |
| --- | --- | --- | --- |
| `yolov10n-320.onnx` | `[1,3,320,320]` | `[1,84,2100]` | **default.** YOLOv10n exported at 320x320. Raw head (4 box + 80 COCO classes, channels-first); NMS is applied in `OnnxDetector`. |
| `yolov10n_320.ncnn.*` | `[1,3,320,320]` | `[1,84,2100]` | Converted ncnn format for Adreno GPU Vulkan / ARM CPU execution. |
| `yolov10n-224.onnx` | `[1,3,224,224]` | `[1,84,1029]` | YOLOv10n exported at 224x224. ~51% fewer FLOPs/pixels and 1029 anchors; ~10ms CPU inference. |
| `yolov10n_224.ncnn.*` | `[1,3,224,224]` | `[1,84,1029]` | Converted ncnn format at 224x224 for Adreno GPU Vulkan / ARM CPU execution. |
| `yolov10n-224-pruned.onnx` | `[1,3,224,224]` | `[1,10,1029]` | YOLOv10n at 224x224 pruned to 6 automotive classes (person, bicycle, car, motorcycle, bus, truck). |
| `test_pattern.onnx` | `[1,3,640,640]` | `[1,6,4]` | Synthetic 392-byte model that always reports one "person" and one "bicycle". Plumbing self-test only. |

`OnnxDetector` reads the input size from the model, so **no code changes are needed for a
different input resolution** — the letterbox and the box coordinates both scale from it.

## Execution providers

The app links **`onnxruntime-android-qnn`**, which pulls in Qualcomm's
`com.qualcomm.qti:qnn-runtime` and bundles `libQnnHtp.so`, `libQnnGpu.so`, the HTP
skeletons and `libQnnSystem.so`. Attempts are made best-first and each one is
**verified**, because ONNX Runtime will happily build a QNN session that silently runs
every node on CPU:

| Order | Backend | Verified by |
| --- | --- | --- |
| 1 | QNN / HTP (Hexagon NPU) | `libQnnHtpV*Skel.so` present in `/proc/self/maps` |
| 2 | QNN / GPU (Adreno) | `libQnnGpu.so` present in `/proc/self/maps` |
| 3 | CPU | always succeeds |

### QNN does not work on this tablet — measured, not guessed

Tested on the Lenovo TB331FC (SM6225, Snapdragon 685) with two QNN vintages:

```
ORT 1.22.0 + QNN 2.33.0   HTP: QNN_LOG_ERROR_INITIALIZATION (QnnLog_create fails)
ORT 1.20.0 + QNN 2.27.0   HTP: QNN_BACKEND_ERROR_CANNOT_INITIALIZE
                          GPU: QNN_COMMON_ERROR_PLATFORM_NOT_SUPPORTED
```

`QnnLog_create` fails before any backend is set up, so both backends fall over at the
same place, and the GPU backend additionally reports the platform as unsupported
(Adreno 610 is not a QNN-capable target). Qualcomm's fastRPC libraries *are* present and
app-linkable (`/vendor/etc/public.libraries.txt` lists `libcdsprpc.so`), so this is not a
packaging or namespace problem on the app side.

The app therefore reports `CPU` on the HUD rather than pretending. If you ever run this
on a modern Snapdragon, the HTP attempt should engage with no code change.

> **APK size:** the QNN runtime is ~110 MB of the ~141 MB debug APK. Since it cannot run
> here, swapping the dependency for `com.microsoft.onnxruntime:onnxruntime-android`
> (CPU only) shrinks the APK to roughly 30 MB with no functional loss on this device.
> Only the `implementation(...)` line in `app/build.gradle.kts` changes.

## Measured cost (1280x720 preview, Lenovo TB331FC)

| Configuration | CPU | Temp |
| --- | --- | --- |
| 640x640 model, inference unthrottled | ~250-264% of one core | 88 °C |
| 320x320 model, 2 passes/s | **~123-165%** | **63 °C** |

Levers, in order of impact: **model input size** (4x less compute at 320 vs 640),
**detection rate** (Settings → Detection rate), then preview resolution.

## Using your own model

```bash
pip install ultralytics
yolo export model=yolov10n.pt format=onnx imgsz=320 opset=13   # or 480, 640
cp yolov10n.onnx app/src/main/assets/my-model.onnx
```

Then put `my-model.onnx` in Settings → Object detection → *model asset name*.

Supported heads, detected from the tensor dimensions:

| Export | Shape | Handling |
| --- | --- | --- |
| YOLOv8 / YOLOv10 / YOLOv11 (raw) | `[1, 4+nc, N]` | channels-first, NMS in app |
| YOLOv5 | `[1, N, 5+nc]` | objectness multiplied in, NMS in app |
| YOLOv10 end-to-end (`nms=True`) | `[1, N, 6]` | `x1,y1,x2,y2,score,class`, already NMS'd |

Leading unit dims (`[1,1,N,C]`) are squeezed. Input must be `[1,3,H,W]` float32, `0..1`
RGB, letterboxed with gray 114 — the standard Ultralytics preprocessing. Non-COCO models
work but need `COCO_LABELS` in `OnnxDetector.kt` replaced to get correct names.

If the configured asset is missing, the detector falls back to whatever `.onnx` is
bundled and says so in the log instead of silently disabling detection.
