#include "uvc_stream_reader.hpp"
#include <cstring>
#include "hud_log.hpp"

#define READ_LOG_TAG "UvcStreamReader"
#define READ_LOGI(...) hudLog(HUD_LOG_LEVEL_INFO, READ_LOG_TAG, __VA_ARGS__)
#define READ_LOGW(...) hudLog(HUD_LOG_LEVEL_WARN, READ_LOG_TAG, __VA_ARGS__)

UvcStreamReader::UvcStreamReader()
    : mDroppedFrames(0),
      mDecodedFrames(0),
      mInFrame(false) {
    mFrameBuffer.reserve(256 * 1024);
}

UvcStreamReader::~UvcStreamReader() {
    reset();
}

void UvcStreamReader::reset() {
    std::lock_guard<std::mutex> lock(mMutex);
    mFrameBuffer.clear();
    mInFrame = false;
}

void UvcStreamReader::setFrameCallback(FrameCallback callback) {
    std::lock_guard<std::mutex> lock(mMutex);
    mCallback = callback;
}

void UvcStreamReader::feedUsbChunk(const uint8_t* chunk, size_t chunkSize) {
    if (!chunk || chunkSize == 0) return;

    std::lock_guard<std::mutex> lock(mMutex);

    size_t offset = 0;

    // Check for standard UVC payload header:
    // If first byte is header length (typically 2 <= HLE <= 12)
    uint8_t hle = chunk[0];
    bool hasUvcHeader = (hle >= 2 && hle <= 12 && hle <= chunkSize);
    bool isEof = false;

    if (hasUvcHeader) {
        uint8_t headerInfo = chunk[1];
        isEof = (headerInfo & 0x02) != 0; // Bit 1 = EOF (End of Frame)
        offset = hle;                     // Skip header bytes
    }

    const uint8_t* payload = chunk + offset;
    size_t payloadSize = chunkSize - offset;

    // Search for JPEG SOI (0xFF, 0xD8) to resynchronize stream on bad OTG cable drops
    for (size_t i = 0; i + 1 < payloadSize; ++i) {
        if (payload[i] == 0xFF && payload[i + 1] == 0xD8) {
            // New frame started
            if (mInFrame && !mFrameBuffer.empty()) {
                // Previous frame was incomplete/dropped due to packet loss
                mDroppedFrames++;
            }
            mFrameBuffer.clear();
            mInFrame = true;
            payload += i;
            payloadSize -= i;
            break;
        }
    }

    if (!mInFrame && !hasUvcHeader) {
        // Not in a frame and no marker found, wait for next SOI
        return;
    }

    // Protect against buffer overflow from missing EOI / EOF
    if (mFrameBuffer.size() + payloadSize > MAX_FRAME_BUFFER_SIZE) {
        READ_LOGW("Frame buffer exceeded max size (%zu), dropping corrupted frame", mFrameBuffer.size());
        mFrameBuffer.clear();
        mInFrame = false;
        mDroppedFrames++;
        return;
    }

    // Append payload to frame buffer
    mFrameBuffer.insert(mFrameBuffer.end(), payload, payload + payloadSize);

    // Check for JPEG EOI (0xFF, 0xD9) marker at the end of the payload
    bool hasEoi = false;
    if (mFrameBuffer.size() >= 2) {
        size_t last = mFrameBuffer.size() - 1;
        if (mFrameBuffer[last - 1] == 0xFF && mFrameBuffer[last] == 0xD9) {
            hasEoi = true;
        }
    }

    // If EOF or EOI is reached, dispatch complete frame
    if (isEof || hasEoi) {
        if (mCallback && mFrameBuffer.size() > 4) {
            mCallback(mFrameBuffer.data(), mFrameBuffer.size());
            mDecodedFrames++;
        }
        mFrameBuffer.clear();
        mInFrame = false;
    }
}
