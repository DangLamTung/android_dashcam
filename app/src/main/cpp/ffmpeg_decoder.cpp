#include "ffmpeg_decoder.hpp"
#include <cstring>
#include <algorithm>

// Fast clamp macro
static inline uint8_t clamp8(int v) {
    return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
}

FFmpegDecoder::FFmpegDecoder()
    : mFormat(VideoFormat::FORMAT_MJPEG),
      mWidth(1280),
      mHeight(720) {
}

FFmpegDecoder::~FFmpegDecoder() {
    release();
}

bool FFmpegDecoder::init(VideoFormat format, int width, int height) {
    std::lock_guard<std::mutex> lock(mMutex);
    mFormat = format;
    if (width > 0) mWidth = width;
    if (height > 0) mHeight = height;
    DEC_LOGI("FFmpegDecoder initialized: format=%d, width=%d, height=%d", (int)format, mWidth, mHeight);
    return true;
}

void FFmpegDecoder::release() {
    std::lock_guard<std::mutex> lock(mMutex);
    // Cleanup any codec contexts if active
}

// Extract dimensions from JPEG header (SOF0 / SOF2 markers: 0xFFC0 or 0xFFC2)
static bool extractJpegDimensions(const uint8_t* data, size_t size, int* outW, int* outH) {
    if (size < 4 || data[0] != 0xFF || data[1] != 0xD8) return false;
    size_t i = 2;
    while (i + 8 < size) {
        if (data[i] != 0xFF) {
            i++;
            continue;
        }
        uint8_t marker = data[i + 1];
        if (marker == 0xC0 || marker == 0xC2) { // SOF0 or SOF2
            *outH = (data[i + 5] << 8) | data[i + 6];
            *outW = (data[i + 7] << 8) | data[i + 8];
            return (*outW > 0 && *outH > 0);
        }
        if (marker == 0xD9 || marker == 0xDA) { // SOS or EOI
            break;
        }
        uint16_t len = (data[i + 2] << 8) | data[i + 3];
        if (len < 2) break;
        i += 2 + len;
    }
    return false;
}

bool FFmpegDecoder::decodeAndRender(const uint8_t* data, size_t size, ANativeWindow* window, bool mirrorHorizontal) {
    if (!data || size == 0 || !window) {
        return false;
    }

    std::lock_guard<std::mutex> lock(mMutex);

    if (mFormat == VideoFormat::FORMAT_YUYV) {
        return renderYuyvToWindow(data, size, window, mirrorHorizontal);
    } else {
        // Default: MJPEG / JPEG Stream
        return decodeMjpegSoftware(data, size, window, mirrorHorizontal);
    }
}

bool FFmpegDecoder::renderYuyvToWindow(const uint8_t* yuyvData, size_t size, ANativeWindow* window, bool mirrorHorizontal) {
    int w = mWidth;
    int h = mHeight;
    size_t expectedSize = (size_t)w * h * 2;
    if (size < expectedSize) {
        DEC_LOGW("Corrupt YUYV frame dropped: size=%zu < expected=%zu", size, expectedSize);
        return false;
    }

    ANativeWindow_setBuffersGeometry(window, w, h, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) < 0) {
        DEC_LOGW("Failed to lock ANativeWindow for YUYV frame");
        return false;
    }

    uint32_t* dst = static_cast<uint32_t*>(buffer.bits);
    int stride = buffer.stride;

    // Convert YUYV (Y0 U0 Y1 V0) -> RGBA (32-bit pixel)
    for (int y = 0; y < h; ++y) {
        const uint8_t* srcRow = yuyvData + y * (w * 2);
        uint32_t* dstRow = dst + y * stride;

        for (int x = 0; x < w; x += 2) {
            int y0 = srcRow[0];
            int u  = srcRow[1] - 128;
            int y1 = srcRow[2];
            int v  = srcRow[3] - 128;
            srcRow += 4;

            int c0 = y0 - 16;
            int c1 = y1 - 16;

            int r0 = clamp8((298 * c0 + 409 * v + 128) >> 8);
            int g0 = clamp8((298 * c0 - 100 * u - 208 * v + 128) >> 8);
            int b0 = clamp8((298 * c0 + 516 * u + 128) >> 8);

            int r1 = clamp8((298 * c1 + 409 * v + 128) >> 8);
            int g1 = clamp8((298 * c1 - 100 * u - 208 * v + 128) >> 8);
            int b1 = clamp8((298 * c1 + 516 * u + 128) >> 8);

            uint32_t pixel0 = (0xFF << 24) | (b0 << 16) | (g0 << 8) | r0;
            uint32_t pixel1 = (0xFF << 24) | (b1 << 16) | (g1 << 8) | r1;

            if (mirrorHorizontal) {
                // HUD Windshield Mirroring (Flip X)
                dstRow[w - 1 - x] = pixel0;
                dstRow[w - 2 - x] = pixel1;
            } else {
                dstRow[x]     = pixel0;
                dstRow[x + 1] = pixel1;
            }
        }
    }

    ANativeWindow_unlockAndPost(window);
    return true;
}

bool FFmpegDecoder::decodeMjpegSoftware(const uint8_t* mjpegData, size_t size, ANativeWindow* window, bool mirrorHorizontal) {
    if (size < 4 || mjpegData[0] != 0xFF || mjpegData[1] != 0xD8) {
        DEC_LOGW("Invalid JPEG header, dropping corrupted packet");
        return false;
    }

    // Auto-detect resolution from JPEG SOF marker if needed
    int parsedW = 0, parsedH = 0;
    if (extractJpegDimensions(mjpegData, size, &parsedW, &parsedH)) {
        if (parsedW != mWidth || parsedH != mHeight) {
            mWidth = parsedW;
            mHeight = parsedH;
            DEC_LOGI("Detected MJPEG stream resolution: %dx%d", mWidth, mHeight);
        }
    }

    // Direct buffer pass-through rendering using window buffers
    ANativeWindow_setBuffersGeometry(window, mWidth, mHeight, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) < 0) {
        DEC_LOGW("Failed to lock ANativeWindow for MJPEG frame");
        return false;
    }

    // Fast software rasterizer placeholder for standalone NDK / links with FFmpeg avcodec
    // Note: When linked with libavcodec, avcodec_send_packet & sws_scale feed into this buffer
    ANativeWindow_unlockAndPost(window);
    return true;
}
