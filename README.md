# Android Dashcam & Driving HUD

A high-performance, fault-tolerant Android application and Heads-Up Display (HUD) dashcam supporting **USB (UVC/OTG) webcams**, **built-in system cameras (rear/front)**, and **WiFi IP cameras (RTSP / HTTP MJPEG)** with on-device **YOLOv10n AI object detection** accelerated by **NCNN** and **ONNX Runtime**.

---

## 🚗 Key Features

### 1. 🧠 On-Device AI Detection & Distance Estimation
- **YOLOv10n Neural Network**: Real-time object detection for vehicles (`car`, `truck`, `bus`, `motorcycle`) and pedestrians.
- **NCNN Acceleration Engine**: Highly optimized native ARM64 NEON and Vulkan compute pipeline (`libncnn.so`) yielding ultra-low inference latency (~15–25ms on modern Android chipsets).
- **Dual Inference Backends**:
  - **NCNN** (Default / High performance): `yolov10n_224.ncnn` (224×224) and `yolov10n_320.ncnn` (320×320).
  - **ONNX Runtime**: `yolov10n-224.onnx`, `yolov10n-320.onnx`, and pruned variants.
- **Fused Ground-Plane & Pinhole Distance Estimation**: Fuses ground-plane camera pitch geometry ($Z_g = \frac{h_c}{\tan(\theta_{pitch} + \alpha_y)}$) with real-world 3D object size priors ($Z_p = \frac{f_y \cdot H_{real}}{h_{px}}$) to calculate accurate distances and Time-To-Collision (TTC).
- **Interactive Calibration Wizard**: On-screen horizon line calibration, camera mounting height ($h_c$), vertical FOV adjustment, and automatic vanishing-point pitch solver.

### 2. 📹 Multi-Source Camera Support
- **USB / UVC OTG Cameras**: Direct low-latency native streaming from USB video class cameras with packet-drop recovery and cable vibration tolerance.
- **Built-in System Cameras**: High-resolution Camera2 pipeline utilizing Android's built-in rear camera with instant one-tap flip to the front camera.
- **WiFi / IP Network Cameras**:
  - **RTSP Streams** (`rtsp://...`) via hardware-accelerated MediaPlayer.
  - **HTTP MJPEG Streams** (`http://...`) with pooled memory buffers and live canvas rendering.
- **Quick Camera Switching**: Cycle between USB, System, and WiFi cameras directly from the HUD bottom toolbar or customize via Settings.

### 3. 💾 Smart Dashcam Recording & Overlay Burn-In
- **Hardware Matroska (MKV) Recording**: Hardware H.264 MediaCodec encoding directly into `.mkv` files.
- **Automatic Storage Resolution**:
  - Prefers physical, removable **MicroSD cards** (`[SD_CARD]/Android/data/com.driving.hud.usbcamera/files/Movies`) so phone storage is never filled.
  - If no SD card is detected, saves internally and automatically exports to the public **MediaStore (`Movies/HUD`)** in the background so recordings appear immediately in Gallery and Google Photos.
- **Zero-Copy NV21 Overlay Burn-In**: Renders tactical HUD bounding boxes, vehicle classifications, distances (meters), and TTC warning badges directly onto the recorded video frames.
- **Synchronized Subtitles (.SRT)**: Generates a matching `.srt` subtitle file alongside every recording, providing frame-by-frame telemetry and detection logs in standard media players (VLC, MX Player, etc.).

### 4. 🚘 Driving HUD Display
- **Windshield Mirror Mode**: 1-tap horizontal flip so the screen reflects legibly when placed on a dashboard reflecting onto the windshield.
- **Picture-in-Picture (PiP) & Split Screen**: Run navigation apps (Google Maps, Waze) in split-screen or float the HUD as a PiP window while keeping detection and collision alerts active.
- **Live Battery & Thermal Telemetry**: Real-time battery drain rate (%/hr), temperature, and FPS monitoring with 5-second EMA low-pass filtering.

---

## 🔋 Real-World Battery & Power Benchmarks

Field tested on a device with a **7,000 mAh** battery running **YOLOv10n AI object detection**, active camera streaming, and a **navigation app running concurrently in Picture-in-Picture (PiP)**:

| Metric | Measured Real-World Value |
| :--- | :--- |
| **Average Power Draw** | **2.7 W – 3.0 W** (~700–780 mA @ 3.85V) |
| **Peak Surge Power** | **~4.0 W** (during complex multi-object inference passes) |
| **Measured Drain Rate** | **~1% every 6 minutes** (approx. **10.0% / hour**) |
| **Total Continuous Runtime** | **~10 hours** of driving on a full 7,000 mAh charge |
| **Multitasking Stability** | Full background tracking & alerts sustained while Google Maps/Waze navigates in PiP |

> **Note on Telemetry Smoothing**: The HUD applies a 5-second Exponential Moving Average (EMA, $\alpha = 0.2$) filter to instantaneous current and wattage, ensuring that transient 4W inference spikes do not cause fluctuating battery runtime estimates.

## 📁 Repository Structure

```
android-dashcam/
├── .github/workflows/
│   └── build-and-release.yml              # Automated GitHub Actions CI/CD & release pipeline
├── app/
│   ├── build.gradle.kts                   # NDK r28c, CMake, NCNN & ONNX build script
│   └── src/main/
│       ├── AndroidManifest.xml            # Permissions (USB, Camera, Network, Storage)
│       ├── assets/                        # YOLOv10n NCNN (.param/.bin) & ONNX models
│       │   ├── yolov10n_224.ncnn.param
│       │   ├── yolov10n_224.ncnn.bin
│       │   ├── yolov10n_320.ncnn.param
│       │   ├── yolov10n_320.ncnn.bin
│       │   └── yolov10n-224.onnx
│       ├── cpp/                           # Native NCNN detector & UVC engine
│       │   ├── CMakeLists.txt
│       │   ├── ncnn_detector.cpp
│       │   └── native-camera-jni.cpp
│       ├── java/com/driving/hud/usbcamera/
│       │   ├── MainActivity.kt            # HUD UI, telemetry, dialogs & lifecycle
│       │   ├── SettingsActivity.kt        # Camera, model, confidence & resolution preferences
│       │   ├── camera/                    # Unified multi-camera coordinator
│       │   │   ├── CameraCoordinator.kt   # USB / System / WiFi switching
│       │   │   ├── AusbcCameraController.kt
│       │   │   ├── SystemCameraController.kt
│       │   │   └── WifiCameraController.kt
│       │   ├── detect/                    # YOLOv10n inference engines
│       │   │   ├── NcnnDetector.kt        # JNI NCNN high-speed inference
│       │   │   ├── OnnxDetector.kt        # ONNX Runtime fallback
│       │   │   └── DetectorFactory.kt
│       │   ├── record/                    # Video recording & overlay
│       │   │   ├── MkvRecorder.kt         # H.264 MediaCodec + Matroska muxer
│       │   │   ├── Nv21OverlayRenderer.kt # Zero-copy bounding box burn-in
│       │   │   ├── SrtWriter.kt           # Subtitle generator
│       │   │   └── RecordingStorageManager.kt # SD card & MediaStore export
│       │   ├── alert/                     # Collision warning & TTC tracker
│       │   └── ui/                        # HUD overlay, distance ladder & widgets
│       └── jniLibs/arm64-v8a/             # Prebuilt libncnn.so & libusb1.0.so
└── tools/
    └── export_yolo_224.py                 # PyTorch YOLO export script
```

---

## 🛠️ Building & Developing

### Local Build (macOS / Linux / Windows)
1. Open the project in **Android Studio** (Koala / Ladybug or newer).
2. Ensure Android NDK `28.2.13676358` (r28c) and CMake are installed via SDK Manager.
3. Build the debug or release APK:
   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew assembleRelease
   ```
   The resulting signed release APK is generated at:
   `app/build/outputs/apk/release/app-release.apk`

4. Install directly to a connected device:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

### 🚀 CI/CD & Automated GitHub Releases
This repository includes a fully automated GitHub Actions workflow (`.github/workflows/build-and-release.yml`):
- **On every push / PR to `main`**: Runs unit tests and builds both Debug and Release APKs as workflow artifacts.
- **On git tag creation (`v*`)**: Automatically compiles the project, runs tests, and publishes a new **GitHub Release** with `app-release.apk` and `app-debug.apk` attached.
- **Manual Trigger**: Can be run on demand from the **Actions** tab with an optional release publish toggle.

To create a release:
```bash
git tag v1.0.0
git push origin v1.0.0
```
GitHub Actions will automatically build, package, and publish the release.

---

## 🎯 Usage & Calibration

1. **Connect Camera**:
   - **USB Camera**: Plug in via OTG adapter and tap **Connect / Request Permission**.
   - **System Camera**: Tap the camera source icon on the bottom toolbar or open Settings to select the phone's built-in camera (rear or front).
   - **WiFi Camera**: Open Settings, select **WiFi / IP Camera**, and enter your RTSP (`rtsp://192.168.1.x:554/...`) or HTTP MJPEG URL.
2. **Horizon & Distance Calibration**:
   - Tap **Calibration** on the bottom toolbar.
   - Adjust the **Pitch Angle** slider until the cyan horizon line aligns with the physical road horizon.
   - Enter your phone's mounting height from the road surface (e.g., 1.25m).
   - Tap **Save & Apply**.
3. **Dashcam Recording**:
   - Tap **REC** to start recording with detection overlays burned into the video.
   - When finished, tap **STOP** to view file details, play immediately in your video player, or share.
