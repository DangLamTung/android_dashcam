#ifndef FFMPEG_DECODER_HPP
#define FFMPEG_DECODER_HPP

#include <android/native_window.h>
#include <cstdint>
#include <cstddef>
#include <vector>
#include <mutex>
#include "hud_log.hpp"

#define DEC_LOG_TAG "NativeCameraHUD"
#define DEC_LOGI(...) hudLog(HUD_LOG_LEVEL_INFO, DEC_LOG_TAG, __VA_ARGS__)
#define DEC_LOGW(...) hudLog(HUD_LOG_LEVEL_WARN, DEC_LOG_TAG, __VA_ARGS__)
#define DEC_LOGE(...) hudLog(HUD_LOG_LEVEL_ERROR, DEC_LOG_TAG, __VA_ARGS__)

enum class VideoFormat {
    FORMAT_UNKNOWN = 0,
    FORMAT_MJPEG,
    FORMAT_YUYV,
    FORMAT_H264
};

class FFmpegDecoder {
public:
    FFmpegDecoder();
    ~FFmpegDecoder();

    bool init(VideoFormat format, int width, int height);
    void release();

    // Decodes a single video frame and renders it to the given ANativeWindow
    bool decodeAndRender(const uint8_t* data, size_t size, ANativeWindow* window, bool mirrorHorizontal);

    int getFrameWidth() const { return mWidth; }
    int getFrameHeight() const { return mHeight; }

private:
    VideoFormat mFormat;
    int mWidth;
    int mHeight;
    std::mutex mMutex;

    // Internal decoders and renderers
    bool renderYuyvToWindow(const uint8_t* yuyvData, size_t size, ANativeWindow* window, bool mirrorHorizontal);
    bool renderRgbToWindow(const uint8_t* rgbData, int width, int height, ANativeWindow* window, bool mirrorHorizontal);
    bool decodeMjpegSoftware(const uint8_t* mjpegData, size_t size, ANativeWindow* window, bool mirrorHorizontal);
};

#endif // FFMPEG_DECODER_HPP
