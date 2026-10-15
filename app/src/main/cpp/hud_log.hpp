#ifndef HUD_LOG_HPP
#define HUD_LOG_HPP

#include <jni.h>
#include <android/log.h>
#include <cstdarg>

// Log levels (match android.util.Log constants used by the Kotlin side).
#define HUD_LOG_LEVEL_VERBOSE 2
#define HUD_LOG_LEVEL_DEBUG   3
#define HUD_LOG_LEVEL_INFO    4
#define HUD_LOG_LEVEL_WARN    5
#define HUD_LOG_LEVEL_ERROR   6

// Writes a log line to logcat (always) and forwards it to the Kotlin
// HudLogger file sink when one has been registered via hudLogSetSink().
// Safe to call from any thread (native thread will be attached to the JVM).
void hudLog(int level, const char* tag, const char* fmt, ...)
#if defined(__GNUC__)
    __attribute__((format(printf, 3, 4)))
#endif
    ;

// Registers (or clears, when sink == nullptr) the Kotlin object whose
// nativeLogSink(int, String, String) method receives forwarded log lines.
void hudLogSetSink(JNIEnv* env, jobject sink);

#endif // HUD_LOG_HPP
