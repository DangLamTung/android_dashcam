# Android USB Camera Connect & Driving HUD (with FFmpeg & Native UVC Engine)

A high-performance, fault-tolerant Android application designed to connect any USB/UVC webcam or dashcam to an Android phone over OTG, displaying a real-time, low-latency video feed optimized for **Driving HUD (Heads-Up Display)**.

---

## 🚗 Key Features for Driving & Janky OTG Cables

1. **Janky OTG Cable & USB Drop Resilience**:
   - Built-in packet re-synchronization with JPEG SOI (`0xFFD8`) and UVC payload header bounds checking.
   - Automatically drops corrupted or truncated USB packets caused by cable vibration or loose OTG adapters without freezing or crashing.
   - Background Watchdog service automatically re-polls and recovers the camera stream within 1–2 seconds of a reconnect.

2. **Real-Time AI ADAS & Pinhole Distance Estimation**:
   - **On-Device YOLOv8 Detection**: Real-time bounding box detection for cars, trucks, buses, motorcycles, and pedestrians.
   - **Fused True Distance Calculation**: Combines ground-plane projection geometry ($Z_g = \frac{h_c}{\tan(\theta_{pitch} + \alpha_y)}$) with pinhole 3D real-world dimensional priors ($Z_p = \frac{f_y \cdot H_{real}}{h_{px}}$) with outlier rejection.
   - **Interactive Calibration Wizard**: Horizon line slider, camera mounting height ($h_c$), vertical FOV adjustment, and an Auto-Pitch Vanishing Point Solver.
   - **Visual Distance Ladder**: AR distance marks at 5m, 10m, 20m, 50m and color-coded safety badges (Green $\ge 20$m, Amber $10-20$m, Red $< 10$m).

3. **Driving HUD & Hardware Dashcam Recording**:
   - **Hardware H.264 Dashcam Recording**: Zero-copy MediaCodec hardware encoder for simultaneous background recording without dropping stream frames.
   - **Windshield Mirror Mode**: 1-tap horizontal flip to mirror the video display so it reflects correctly when projected onto a car windshield.
   - **Immersive Fullscreen & Screen Keep-Awake**: Locks screen brightness on and hides system navigation bars.
   - **Real-Time Diagnostics**: Live FPS, transfer bitrate, and real-time battery drain rate (%/hr) monitoring.

4. **Ultra-Low Latency Native Pipeline**:
   - Direct streaming from Android `UsbDeviceConnection` bulk endpoints to C++ JNI and `ANativeWindow` (`SurfaceView`).
   - Software color space conversion (YUYV422/MJPEG to 32-bit RGBA8888) with minimal frame buffering (< 30ms latency).

---

## 📁 Project Architecture

```
android-usb-camera-hud/
├── app/
│   ├── build.gradle.kts                   # NDK, CMake & ONNX / MediaCodec build definitions
│   └── src/main/
│       ├── AndroidManifest.xml            # USB host permissions & intent filters
│       ├── cpp/
│       │   ├── CMakeLists.txt             # NDK CMake build configuration
│       │   ├── ffmpeg_decoder.hpp/.cpp    # Native frame decoder & ANativeWindow renderer
│       │   ├── uvc_stream_reader.hpp/.cpp # Resilient UVC stream parser & packet accumulator
│       │   └── native-camera-jni.cpp      # JNI bridge
│       ├── java/com/driving/hud/usbcamera/
│       │   ├── MainActivity.kt            # HUD UI, Calibration UI & lifecycle
│       │   ├── calibration/
│       │   │   ├── GroundPlaneCalibration.kt # Persistent calibration storage & horizon math
│       │   │   └── PinholeDistanceEstimator.kt # Fused pinhole + ground-plane distance solver
│       │   ├── camera/
│       │   │   ├── UsbCameraManager.kt    # USB device discovery, permissions & watchdog
│       │   │   └── CameraNativeBridge.kt  # JNI bindings
│       │   ├── recording/
│       │   │   └── DashcamVideoRecorder.kt # Hardware H.264 MediaCodec video recorder
│       │   ├── tracking/
│       │   │   └── VehicleTracker.kt      # Multi-frame object smoothing & distance fusion
│       │   └── ui/
│       │       ├── HudSurfaceView.kt      # SurfaceView holder
│       │       └── HudOverlayView.kt      # AR HUD distance ladder, bounding boxes & horizon
│       └── res/
│           ├── layout/activity_main.xml   # HUD & Calibration layout
│           └── xml/device_filter.xml      # UVC Class 14 (0x0E) auto-detect
└── tools/
    ├── export_yolo_224.py                 # YOLOv8 ONNX model export & optimization script
    ├── openpilot_onnx_inspect.py          # Supercombo model inspection tool
    └── openpilot_output_slices.py         # Openpilot output tensor slicing utility
```

---

## 🛠️ How to Open and Run in Android Studio

1. Open **Android Studio** (Koala, Ladybug, Iguana, or newer).
2. Click **File -> Open...** and select `/Users/tungdl/Documents/android-usb-camera-hud`.
3. Allow Gradle to sync dependencies and the NDK / CMake C++ toolchain.
4. Connect your Android phone via USB debugging.
5. Click **Run (`Shift + F10`)** to install and run on your device.

---

## 🔌 Connecting your USB Camera / Webcam

1. Plug your USB Webcam / UVC camera into your phone using an **OTG adapter** (or USB-C to USB-A adapter).
2. Android will prompt: *"Allow HUD USB Camera to access [Camera Model]?"* -> Tap **OK**.
3. The app will immediately lock onto the video stream and render to the HUD screen.

### 🎯 Calibration for Vehicle Distance Accuracy
1. Tap the **"Calibration"** button in the HUD controls.
2. Park the car on a flat, level road.
3. Adjust the **Pitch Angle** slider until the blue dashed Horizon Line aligns with the real-world horizon (or tap **"Auto Pitch"** while pointing at a straight, level vanishing point).
4. Enter the camera mounting height ($h_c$) from the ground (e.g. 1.25m for sedan/suv windshield).
5. Tap **"Save & Apply"** to persist settings across reboots.
