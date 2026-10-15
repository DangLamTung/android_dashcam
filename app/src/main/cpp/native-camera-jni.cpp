#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <chrono>
#include <atomic>
#include "hud_log.hpp"
#include "ffmpeg_decoder.hpp"
#include "uvc_stream_reader.hpp"

#define LOG_TAG "NativeCameraJNI"
#define LOGI(...) hudLog(HUD_LOG_LEVEL_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) hudLog(HUD_LOG_LEVEL_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// File-logging bridge: forwards C++ logs to the Kotlin HudLogger file sink.
// ---------------------------------------------------------------------------
// Externally visible so libusb_capture.cpp can attach the JVM from its thread.
JavaVM* gJavaVM = nullptr;
static jobject gLogSink = nullptr;
static jmethodID gLogSinkMethod = nullptr;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJavaVM = vm;
    return JNI_VERSION_1_6;
}

void hudLogSetSink(JNIEnv* env, jobject sink) {
    if (gLogSink != nullptr) {
        env->DeleteGlobalRef(gLogSink);
        gLogSink = nullptr;
    }
    gLogSinkMethod = nullptr;

    if (sink != nullptr) {
        jclass cls = env->GetObjectClass(sink);
        jmethodID mid = env->GetMethodID(cls, "nativeLogSink", "(ILjava/lang/String;Ljava/lang/String;)V");
        if (mid != nullptr) {
            gLogSink = env->NewGlobalRef(sink);
            gLogSinkMethod = mid;
        }
    }
}

void hudLog(int level, const char* tag, const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    int androidLevel = ANDROID_LOG_INFO;
    switch (level) {
        case HUD_LOG_LEVEL_VERBOSE: androidLevel = ANDROID_LOG_VERBOSE; break;
        case HUD_LOG_LEVEL_DEBUG:   androidLevel = ANDROID_LOG_DEBUG;   break;
        case HUD_LOG_LEVEL_WARN:    androidLevel = ANDROID_LOG_WARN;    break;
        case HUD_LOG_LEVEL_ERROR:   androidLevel = ANDROID_LOG_ERROR;   break;
        default:                    androidLevel = ANDROID_LOG_INFO;    break;
    }
    __android_log_print(androidLevel, tag, "%s", buf);

    // Forward to the Kotlin file logger when a sink is registered. Safe on any
    // thread: attach to the JVM if this is a native thread.
    JavaVM* vm = gJavaVM;
    jobject sink = gLogSink;
    if (vm != nullptr && sink != nullptr) {
        JNIEnv* env = nullptr;
        bool attached = false;
        jint rc = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                env = nullptr;
            }
        }
        if (env != nullptr && gLogSinkMethod != nullptr) {
            jstring jTag = env->NewStringUTF(tag);
            jstring jMsg = env->NewStringUTF(buf);
            env->CallVoidMethod(sink, gLogSinkMethod, static_cast<jint>(level), jTag, jMsg);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(jMsg);
            env->DeleteLocalRef(jTag);
        }
        if (attached) {
            vm->DetachCurrentThread();
        }
    }
}

static FFmpegDecoder gDecoder;
static UvcStreamReader gStreamReader;
static ANativeWindow* gNativeWindow = nullptr;
static bool gMirrorHorizontal = false;
static std::mutex gWindowMutex;
static std::atomic<unsigned long long> gRenderedFrames{0};
static std::chrono::steady_clock::time_point gLastStatsTime = std::chrono::steady_clock::now();

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeInit(
        JNIEnv* env,
        jobject thiz,
        jint format,
        jint width,
        jint height) {
    LOGI("nativeInit called: format=%d, %dx%d", format, width, height);

    VideoFormat vFormat = VideoFormat::FORMAT_MJPEG;
    if (format == 1) vFormat = VideoFormat::FORMAT_YUYV;
    else if (format == 2) vFormat = VideoFormat::FORMAT_H264;

    gDecoder.init(vFormat, width, height);
    gStreamReader.reset();

    // Register callback from stream reader to decoder
    gStreamReader.setFrameCallback([](const uint8_t* frameData, size_t frameSize) {
        std::lock_guard<std::mutex> lock(gWindowMutex);
        if (gNativeWindow != nullptr) {
            gDecoder.decodeAndRender(frameData, frameSize, gNativeWindow, gMirrorHorizontal);
            gRenderedFrames.fetch_add(1);

            // Periodic render stats so failures are visible in the log file.
            auto now = std::chrono::steady_clock::now();
            long long elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - gLastStatsTime).count();
            if (elapsedMs >= 2000) {
                unsigned long long frames = gRenderedFrames.load();
                double fps = frames * 1000.0 / static_cast<double>(elapsedMs);
                hudLog(HUD_LOG_LEVEL_INFO, "NativeCameraJNI",
                       "render fps=%.1f frames=%llu dropped=%zu frame=%.1fKB",
                       fps,
                       static_cast<unsigned long long>(frames),
                       gStreamReader.getDroppedFramesCount(),
                       static_cast<double>(frameSize) / 1024.0);
                gRenderedFrames.store(0);
                gLastStatsTime = now;
            }
        }
    });

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeSetSurface(
        JNIEnv* env,
        jobject thiz,
        jobject surface) {
    std::lock_guard<std::mutex> lock(gWindowMutex);

    if (gNativeWindow != nullptr) {
        ANativeWindow_release(gNativeWindow);
        gNativeWindow = nullptr;
    }

    if (surface != nullptr) {
        gNativeWindow = ANativeWindow_fromSurface(env, surface);
        LOGI("NativeWindow acquired from Surface: %p", gNativeWindow);
    } else {
        LOGI("Surface cleared, NativeWindow released");
    }
}

JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeSetMirror(
        JNIEnv* env,
        jobject thiz,
        jboolean mirror) {
    gMirrorHorizontal = mirror;
    LOGI("HUD Windshield Mirror set to: %s", mirror ? "ENABLED" : "DISABLED");
}

JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeFeedChunk(
        JNIEnv* env,
        jobject thiz,
        jbyteArray chunk,
        jint length) {
    if (chunk == nullptr || length <= 0) return;

    jbyte* buffer = env->GetByteArrayElements(chunk, nullptr);
    if (buffer != nullptr) {
        gStreamReader.feedUsbChunk(reinterpret_cast<const uint8_t*>(buffer), static_cast<size_t>(length));
        env->ReleaseByteArrayElements(chunk, buffer, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeSetLogSink(
        JNIEnv* env,
        jobject thiz,
        jobject sink) {
    hudLogSetSink(env, sink);
}

JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeStop(
        JNIEnv* env,
        jobject thiz) {
    LOGI("nativeStop called");
    gStreamReader.reset();
}

JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeRelease(
        JNIEnv* env,
        jobject thiz) {
    LOGI("nativeRelease called");
    std::lock_guard<std::mutex> lock(gWindowMutex);

    if (gNativeWindow != nullptr) {
        ANativeWindow_release(gNativeWindow);
        gNativeWindow = nullptr;
    }

    gDecoder.release();
    gStreamReader.reset();
}

} // extern "C"
