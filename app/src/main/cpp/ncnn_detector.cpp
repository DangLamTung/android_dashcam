// ncnn inference bridge.
//
// Deliberately thin: ncnn runs the graph and nothing else. Letterboxing, head decoding, NMS
// and coordinate mapping all stay in Kotlin (Letterbox / YoloHead) where they are shared with
// the ONNX backend, so a decoder fix cannot end up applied to only one of the two backends.
//
// The model is loaded from the APK's assets, so no file is ever written to disk. ncnn's param
// loader keeps a pointer into the param text for the life of the net (layer names are parsed
// lazily) and load_model keeps a pointer into the weight bytes, so each Session owns private
// copies of both. They must NOT be process-wide: the backend shoot-out builds the next
// candidate while the current winner is still open, and a shared buffer would be overwritten
// underneath the live net.

#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <vector>

#include "gpu.h"
#include "net.h"

#define LOG_TAG "NcnnDetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Session creation is serialised: ncnn's layer registry and GPU device init are process-wide.
// The asset bytes deliberately are NOT shared — see Session::param.
std::mutex g_init_mu;

const char* const kInputBlob = "in0";
const char* const kOutputBlob = "out0";

struct Session {
    ncnn::Net* net = nullptr;
    bool vulkan = false;
    int in_w = 0;
    int in_h = 0;
    int in_c = 3;

    // Private copies of the graph text and the weights it reads from. ncnn holds raw pointers
    // into these for the whole life of the net, so they must stay put and must not be shared
    // with any other session.
    std::vector<char> param;
    std::vector<unsigned char> model;

    // Shape of the last output actually extracted, so Kotlin reads the real thing rather
    // than assuming a layout.
    int out_dims = 0;
    int out_w = 0;
    int out_h = 0;
    int out_c = 0;

    // One inference at a time: the underlying net holds shared weight memory.
    std::mutex mu;

    ~Session() {
        delete net;
    }
};

/**
 * ncnn can return a packed and/or fp16 Mat. Decoding expects plain fp32, so normalise rather
 * than let the caller guess: unpack first (which also drops fp16 packing metadata), then
 * widen fp16 if it is still 16-bit.
 */
bool normaliseToFp32(const ncnn::Mat& src, ncnn::Mat& dst, ncnn::Mat& scratch) {
    const ncnn::Mat* cur = &src;
    if (cur->elempack != 1) {
        ncnn::convert_packing(*cur, scratch, 1);
        if (scratch.empty()) return false;
        cur = &scratch;
    }
    if (cur->elembits() == 16) {
        ncnn::cast_float16_to_float32(*cur, dst);
        return !dst.empty();
    }
    dst = *cur;
    return !dst.empty();
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_driving_hud_usbcamera_detect_NcnnDetector_nativeGpuCount(JNIEnv*, jclass) {
    return ncnn::get_gpu_count();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_driving_hud_usbcamera_detect_NcnnDetector_nativeInit(
        JNIEnv* env, jclass, jbyteArray param, jbyteArray model,
        jint in_w, jint in_h, jint in_c, jint threads,
        jboolean vulkan, jboolean fp16) {
    if (param == nullptr || model == nullptr || in_w <= 0 || in_h <= 0) return 0;

    std::lock_guard<std::mutex> lock(g_init_mu);

    auto* s = new Session();

    const jsize paramLen = env->GetArrayLength(param);
    s->param.resize(static_cast<size_t>(paramLen) + 1);
    env->GetByteArrayRegion(param, 0, paramLen, reinterpret_cast<jbyte*>(s->param.data()));
    // load_param_mem requires the text to be NUL terminated.
    s->param[static_cast<size_t>(paramLen)] = '\0';

    const jsize modelLen = env->GetArrayLength(model);
    s->model.resize(static_cast<size_t>(modelLen));
    env->GetByteArrayRegion(model, 0, modelLen, reinterpret_cast<jbyte*>(s->model.data()));

    const bool wantVulkan = vulkan == JNI_TRUE;
    const int gpuCount = ncnn::get_gpu_count();
    if (wantVulkan && gpuCount <= 0) {
        // Fail loudly instead of quietly running on CPU, so a caller timing this sees the
        // truth rather than a "vulkan" number that was never actually on the GPU.
        LOGE("vulkan requested but ncnn sees no GPU (get_gpu_count=%d)", gpuCount);
        delete s;
        return 0;
    }

    s->vulkan = wantVulkan;
    s->in_w = in_w;
    s->in_h = in_h;
    s->in_c = in_c > 0 ? in_c : 3;

    auto* net = new ncnn::Net();
    net->opt.num_threads = threads > 0 ? threads : 2;
    net->opt.use_vulkan_compute = wantVulkan;
    if (fp16) {
        // fp16 *storage* only: Cortex-A73/A53 are ARMv8.0 and have no fp16 SIMD, so enabling
        // fp16 arithmetic would be slower, not faster.
        net->opt.use_fp16_packed = true;
        net->opt.use_fp16_storage = true;
        net->opt.use_fp16_arithmetic = false;
    }
    if (wantVulkan) {
        net->set_vulkan_device(0);
    }

    const int paramRet = net->load_param_mem(s->param.data());
    if (paramRet != 0) {
        LOGE("load_param_mem failed: %d", paramRet);
        delete net;
        delete s;
        return 0;
    }
    // The memory loader reports bytes consumed, and 0 means it could not read the weights.
    const size_t consumed = net->load_model(s->model.data());
    if (consumed == 0) {
        LOGE("load_model from memory failed (0 bytes consumed)");
        delete net;
        delete s;
        return 0;
    }

    s->net = net;
    LOGI(
        "session ready: vulkan=%d fp16storage=%d threads=%d gpu_count=%d weights=%zuB in=%dx%dx%d",
        wantVulkan ? 1 : 0, fp16 ? 1 : 0, net->opt.num_threads, gpuCount, consumed,
        s->in_w, s->in_h, s->in_c);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_driving_hud_usbcamera_detect_NcnnDetector_nativeRun(
        JNIEnv* env, jclass, jlong handle, jfloatArray input, jfloatArray output) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr || s->net == nullptr) return -1;

    const jsize inLen = env->GetArrayLength(input);
    const jsize outLen = env->GetArrayLength(output);
    const jsize expected = static_cast<jsize>(s->in_w) * s->in_h * s->in_c;
    if (inLen < expected) {
        LOGE("input too small: %d < %d", inLen, expected);
        return -2;
    }

    // Pin (or copy) the Java array; ncnn reads it during extract, so it must stay valid until
    // then. CHW float layout is byte-identical to ncnn's (w,h,c) ordering.
    jfloat* inPtr = env->GetFloatArrayElements(input, nullptr);
    if (inPtr == nullptr) return -3;

    ncnn::Mat in(s->in_w, s->in_h, s->in_c, inPtr, 4);

    std::lock_guard<std::mutex> lock(s->mu);

    // A fresh extractor per inference, which is ncnn's documented usage. Reusing one looks
    // "much faster" and is a trap: the extractor keeps its blob results, so a second run can
    // return a blob computed from the *previous* input without redoing the maths.
    ncnn::Extractor ex = s->net->create_extractor();
    ex.set_light_mode(true);

    int ret = ex.input(kInputBlob, in);
    if (ret != 0) {
        env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);
        LOGE("ex.input(%s) failed: %d", kInputBlob, ret);
        return -4;
    }

    ncnn::Mat out;
    ret = ex.extract(kOutputBlob, out);
    env->ReleaseFloatArrayElements(input, inPtr, JNI_ABORT);
    if (ret != 0 || out.empty()) {
        LOGE("ex.extract(%s) failed: %d", kOutputBlob, ret);
        return -5;
    }

    ncnn::Mat unpacked;
    ncnn::Mat result;
    if (!normaliseToFp32(out, result, unpacked)) {
        LOGE("could not normalise output to fp32");
        return -6;
    }

    const int total = static_cast<int>(result.total());
    if (total > outLen) {
        LOGE("output array too small: %d > %d", total, outLen);
        return -7;
    }

    s->out_dims = result.dims;
    s->out_w = result.w;
    s->out_h = result.h;
    s->out_c = result.c;

    env->SetFloatArrayRegion(output, 0, total, reinterpret_cast<const jfloat*>(result.data));
    return total;
}

/** {dims, w, h, c} of the last extracted output, so Kotlin reads the real shape. */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_driving_hud_usbcamera_detect_NcnnDetector_nativeOutputShape(
        JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    jint vals[4] = {0, 0, 0, 0};
    if (s != nullptr) {
        vals[0] = s->out_dims;
        vals[1] = s->out_w;
        vals[2] = s->out_h;
        vals[3] = s->out_c;
    }
    jintArray arr = env->NewIntArray(4);
    if (arr != nullptr) env->SetIntArrayRegion(arr, 0, 4, vals);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_driving_hud_usbcamera_detect_NcnnDetector_nativeRelease(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
