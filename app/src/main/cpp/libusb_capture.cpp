#include <jni.h>
#include <pthread.h>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <atomic>
#include "libusb/libusb.h"

// Set in native-camera-jni.cpp JNI_OnLoad.
extern JavaVM* gJavaVM;

namespace {

constexpr size_t MAX_FRAME = 4 * 1024 * 1024; // 4MB per frame cap
constexpr int NUM_ISO_PACKETS = 8;

libusb_context* gCtx = nullptr;
libusb_device_handle* gDev = nullptr;
struct libusb_transfer* gIso = nullptr;
pthread_t gThread = 0;
std::atomic<bool> gRunning{false};
jobject gSink = nullptr; // global ref to the CameraNativeBridge object
jmethodID gOnFrame = nullptr;
int gIfaceId = 0;

// Frame assembler (SOI/EOI resync) state.
std::vector<uint8_t> gFrameBuf;
bool gInFrame = false;
unsigned int gDropped = 0;
unsigned int gDecoded = 0;

void notifyFrame(JNIEnv* env, const uint8_t* data, size_t size) {
    if (!env || !gSink || !gOnFrame) return;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (!arr) return;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
    env->CallVoidMethod(gSink, gOnFrame, arr);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(arr);
}

void feedChunk(JNIEnv* env, const uint8_t* chunk, size_t len) {
    if (!chunk || len == 0) return;

    // UVC payload header: byte0 = header len, byte1 bit1 = EOF.
    size_t offset = 0;
    bool isEof = false;
    uint8_t hle = chunk[0];
    if (hle >= 2 && hle <= 12 && hle <= len) {
        isEof = (chunk[1] & 0x02) != 0;
        offset = hle;
    }

    // Resync on JPEG SOI (0xFF 0xD8).
    int soi = -1;
    for (size_t i = offset; i + 1 < len; ++i) {
        if (chunk[i] == 0xFF && chunk[i + 1] == 0xD8) {
            if (gInFrame && !gFrameBuf.empty()) gDropped++;
            gFrameBuf.clear();
            gInFrame = true;
            soi = static_cast<int>(i);
            break;
        }
    }
    if (!gInFrame && offset == 0) return;

    size_t start = soi >= 0 ? static_cast<size_t>(soi) : offset;
    if (start < len) gFrameBuf.insert(gFrameBuf.end(), chunk + start, chunk + len);

    if (gFrameBuf.size() > MAX_FRAME) {
        gFrameBuf.clear();
        gInFrame = false;
        gDropped++;
        return;
    }

    bool hasEoi = gFrameBuf.size() >= 2 &&
                  gFrameBuf[gFrameBuf.size() - 2] == 0xFF &&
                  gFrameBuf[gFrameBuf.size() - 1] == 0xD9;
    if ((isEof || hasEoi) && gInFrame && gFrameBuf.size() > 4) {
        notifyFrame(env, gFrameBuf.data(), gFrameBuf.size());
        gFrameBuf.clear();
        gInFrame = false;
        gDecoded++;
    }
}

void LIBUSB_CALL isoCallback(struct libusb_transfer* transfer) {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (gJavaVM) {
        if (gJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (gJavaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
    }
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && env) {
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            int len = transfer->iso_packet_desc[i].actual_length;
            if (len <= 0) continue;
            const uint8_t* pkt = libusb_get_iso_packet_buffer_simple(transfer, i);
            feedChunk(env, pkt, static_cast<size_t>(len));
        }
    }
    if (gRunning) libusb_submit_transfer(transfer);
    if (attached) gJavaVM->DetachCurrentThread();
}

void* eventLoop(void*) {
    while (gRunning) {
        libusb_handle_events_completed(gCtx, nullptr);
    }
    return nullptr;
}

// Best-effort UVC probe/commit (non-fatal; many cams stream on alt activation).
void bestEffortUvcStart(libusb_device_handle* dev, int ifaceId) {
    uint8_t probe[26];
    memset(probe, 0, sizeof(probe));
    probe[0] = 26; // bLength
    probe[2] = 1;  // bFormatIndex
    probe[3] = 1;  // bFrameIndex
    libusb_control_transfer(dev, 0x21, 0x01, 0x0100, ifaceId, probe, sizeof(probe), 300);
    libusb_control_transfer(dev, 0xA1, 0x81, 0x0100, ifaceId, probe, sizeof(probe), 300);
    libusb_control_transfer(dev, 0x21, 0x01, 0x0300, ifaceId, probe, sizeof(probe), 300);
}

} // namespace

extern "C" {

// Startup diagnostic: reports libusb_init's return code so we can see why it
// fails on a given device without needing a camera attached.
JNIEXPORT jint JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeLibusbTestInit(
        JNIEnv* env, jobject thiz) {
    // Required on Android: skip device discovery so libusb_init doesn't try the
    // netlink hotplug socket (blocked by SELinux) and wrap_sys_device can be used.
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, 1);
    libusb_context* c = nullptr;
    int r = libusb_init(&c);
    if (r == 0 && c) libusb_exit(c);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeLibusbStart(
        JNIEnv* env, jobject thiz,
        jint fd, jint ifaceId, jint altSetting, jint endpointAddress, jint maxPacketSize) {
    if (gRunning) return -1;
    // Required on Android: skip device discovery so libusb_init doesn't try the
    // netlink hotplug socket (blocked by SELinux) and wrap_sys_device can be used.
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, 1);
    int initR = libusb_init(&gCtx);
    if (initR != 0) {
        // Return the exact libusb error code so the log shows the cause
        // (LIBUSB_ERROR_NO_MEM=-12, LIBUSB_ERROR_ACCESS=-3,
        //  LIBUSB_ERROR_NOT_SUPPORTED=-4, LIBUSB_ERROR_OTHER=-99, ...).
        return initR;
    }
    libusb_set_option(gCtx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

    int r = libusb_wrap_sys_device(gCtx, fd, &gDev);
    if (r < 0) {
        libusb_exit(gCtx);
        gCtx = nullptr;
        return -3;
    }

    gSink = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    gOnFrame = env->GetMethodID(cls, "onNativeFrame", "([B)V");
    gIfaceId = ifaceId;

    libusb_claim_interface(gDev, ifaceId);
    bestEffortUvcStart(gDev, ifaceId);
    libusb_set_interface_alt_setting(gDev, ifaceId, altSetting);

    int isoSize = maxPacketSize * NUM_ISO_PACKETS;
    gIso = libusb_alloc_transfer(NUM_ISO_PACKETS);
    unsigned char* isoBuf = static_cast<unsigned char*>(malloc(isoSize));
    libusb_fill_iso_transfer(gIso, gDev, static_cast<unsigned char>(endpointAddress),
                             isoBuf, isoSize, NUM_ISO_PACKETS, isoCallback, nullptr, 1000);
    libusb_set_iso_packet_lengths(gIso, static_cast<unsigned int>(maxPacketSize));

    gRunning = true;
    r = libusb_submit_transfer(gIso);
    if (r < 0) {
        gRunning = false;
        libusb_free_transfer(gIso);
        free(isoBuf);
        gIso = nullptr;
        return -4;
    }
    pthread_create(&gThread, nullptr, eventLoop, nullptr);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_camera_CameraNativeBridge_nativeLibusbStop(
        JNIEnv* env, jobject thiz) {
    if (!gRunning) return;
    gRunning = false;
    if (gIso) libusb_cancel_transfer(gIso);
    if (gThread) pthread_join(gThread, nullptr);
    if (gIso) {
        free(gIso->buffer);
        libusb_free_transfer(gIso);
        gIso = nullptr;
    }
    if (gDev) {
        libusb_release_interface(gDev, gIfaceId);
        libusb_close(gDev);
        gDev = nullptr;
    }
    if (gCtx) {
        libusb_exit(gCtx);
        gCtx = nullptr;
    }
    if (gSink) {
        env->DeleteGlobalRef(gSink);
        gSink = nullptr;
        gOnFrame = nullptr;
    }
    gFrameBuf.clear();
    gInFrame = false;
}

} // extern "C"
