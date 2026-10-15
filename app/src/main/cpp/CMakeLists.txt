cmake_minimum_required(VERSION 3.22.1)

project("native-camera-hud")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Define include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Check if prebuilt FFmpeg is placed in ffmpeg/include
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/include")
    include_directories("${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/include")
    set(FFMPEG_FOUND TRUE)
endif()

# Source files
set(SOURCE_FILES
    native-camera-jni.cpp
    ffmpeg_decoder.cpp
    uvc_stream_reader.cpp
    libusb_capture.cpp
    ncnn_detector.cpp
)

add_library(
    native-camera-hud
    SHARED
    ${SOURCE_FILES}
)

# Prebuilt libusb for isochronous UVC capture (built for arm64-v8a).
add_library(libusb SHARED IMPORTED)
set_target_properties(libusb PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libusb1.0.so")

# Prebuilt ncnn, Vulkan-enabled (from the official android-vulkan-shared release). It runs
# the YOLO graph behind a thin JNI shim; the model itself is loaded from memory rather than
# written to disk. The released headers already define NCNN_VULKAN/NCNN_STRING/NCNN_STDIO to
# match this build, so nothing extra has to be defined here.
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libncnn.so")
# ncnn's headers include each other by bare name ("net.h", "gpu.h"), so ncnn's own header
# directory goes on the include path rather than its parent — this is ncnn's convention.
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/ncnn/include/ncnn")

# Search for Android NDK system libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# If external prebuilt FFmpeg libraries exist for the current ABI, link them
set(FFMPEG_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
if(EXISTS "${FFMPEG_LIB_DIR}/libavcodec.so")
    add_library(avcodec SHARED IMPORTED)
    set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavcodec.so")
    add_library(avutil SHARED IMPORTED)
    set_target_properties(avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libavutil.so")
    add_library(swscale SHARED IMPORTED)
    set_target_properties(swscale PROPERTIES IMPORTED_LOCATION "${FFMPEG_LIB_DIR}/libswscale.so")
    set(FFMPEG_LIBS avcodec avutil swscale)
else()
    set(FFMPEG_LIBS "")
endif()

target_link_libraries(
    native-camera-hud
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    libusb
    ncnn
    ${FFMPEG_LIBS}
)
