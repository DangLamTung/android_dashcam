#ifndef UVC_STREAM_READER_HPP
#define UVC_STREAM_READER_HPP

#include <cstdint>
#include <cstddef>
#include <vector>
#include <functional>
#include <mutex>

class UvcStreamReader {
public:
    using FrameCallback = std::function<void(const uint8_t* frameData, size_t frameSize)>;

    UvcStreamReader();
    ~UvcStreamReader();

    void reset();
    void setFrameCallback(FrameCallback callback);

    // Feeds incoming USB chunks from UsbDeviceConnection or native USB endpoint
    void feedUsbChunk(const uint8_t* chunk, size_t chunkSize);

    size_t getDroppedFramesCount() const { return mDroppedFrames; }
    size_t getDecodedFramesCount() const { return mDecodedFrames; }

private:
    std::vector<uint8_t> mFrameBuffer;
    FrameCallback mCallback;
    std::mutex mMutex;

    size_t mDroppedFrames;
    size_t mDecodedFrames;
    bool mInFrame;

    static constexpr size_t MAX_FRAME_BUFFER_SIZE = 4 * 1024 * 1024; // 4MB max for a single frame
};

#endif // UVC_STREAM_READER_HPP
