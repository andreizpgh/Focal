#include "native_fault_hooks.h"
#include <ggml-alloc.h>
#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <condition_variable>
#include <limits>
#include <mutex>
#include <stdexcept>

#ifndef FOCAL_NATIVE_FAULT_SOURCE_SHA
#error Native fault hooks require the explicit test-only CMake overlay.
#endif

namespace {
constexpr int FAIL_ALLOCATION = 1;
constexpr int PAUSE_ALLOCATION = 2;
constexpr int MAX_BARRIER_MS = 30000;
constexpr int MAX_ENTER_WAIT_MS = 120000;
struct Event {
    std::uint64_t token = 0;
    int mode = 0;
    std::int64_t handle = 0;
    const void *context = nullptr;
    const void *state = nullptr;
    int encoder_completions = 0, old_decoders = 0, new_decoders = 0;
    int allocation_entries = 0, refusals = 0, destructions = 0;
    int result = 0;
    bool entered = false, released = false, finished = false, has_result = false;
    bool timed_out = false, invalid_target = false;
};
std::mutex mutex;
std::condition_variable changed;
Event event;
std::uint64_t next_token = 1;
thread_local std::uint64_t armed_token = 0;
thread_local std::uint64_t decode_token = 0;
thread_local bool targeted_growth = false;

void require(bool condition, const char *message) {
    if (!condition) throw std::logic_error(message);
}
void valid_token(jlong token) {
    require(token > 0 && event.token == static_cast<std::uint64_t>(token), "Unknown native fault token.");
}
void fail(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) return;
    const auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
}

namespace focal_native_fault {
DecodeScope::DecodeScope(std::int64_t handle, const void *context, std::uint64_t &engine_token) {
    if (armed_token == 0) return;
    std::lock_guard<std::mutex> lock(mutex);
    const auto token = armed_token;
    armed_token = 0;
    require(event.token == token && event.handle == 0 && decode_token == 0 && engine_token == 0,
            "A native fault must target one fresh engine decode.");
    event.handle = handle;
    event.context = context;
    token_ = decode_token = engine_token = token;
}
DecodeScope::~DecodeScope() {
    if (token_ == 0) return;
    std::lock_guard<std::mutex> lock(mutex);
    if (event.token == token_) { event.finished = true; changed.notify_all(); }
    decode_token = 0;
    targeted_growth = false;
}
void DecodeScope::returned(int result) {
    if (token_ == 0) return;
    std::lock_guard<std::mutex> lock(mutex);
    if (event.token == token_) { event.result = result; event.has_result = true; }
}
void encoded(const void *context, const void *state) {
    if (decode_token == 0) return;
    std::lock_guard<std::mutex> lock(mutex);
    if (event.token != decode_token || event.context != context) {
        event.invalid_target = true;
        return;
    }
    ++event.encoder_completions;
    event.state = state;
}
GrowthScope::GrowthScope(const void *context, const void *state, int old_decoders, int new_decoders) {
    if (decode_token == 0) return;
    std::lock_guard<std::mutex> lock(mutex);
    event.old_decoders = old_decoders;
    event.new_decoders = new_decoders;
    targeted_growth = event.token == decode_token && event.context == context && event.state == state &&
        event.encoder_completions == 1 && old_decoders == 1 && new_decoders == 3 && event.allocation_entries == 0;
    if (!targeted_growth) event.invalid_target = true;
}
GrowthScope::~GrowthScope() { targeted_growth = false; }
ggml_backend_buffer *allocate_kv(ggml_context *context, ggml_backend *backend) {
    if (targeted_growth) {
        std::unique_lock<std::mutex> lock(mutex);
        require(event.token == decode_token && !event.entered, "Repeated native fault allocation.");
        ++event.allocation_entries;
        event.entered = true;
        changed.notify_all();
        if (event.mode == FAIL_ALLOCATION) {
            ++event.refusals;
            __android_log_print(ANDROID_LOG_ERROR, "FocalNativeFault", "KV_REFUSAL token=%llu handle=%lld old=1 new=3 encoder=1",
                static_cast<unsigned long long>(event.token), static_cast<long long>(event.handle));
            return nullptr;
        }
        if (!changed.wait_for(lock, std::chrono::milliseconds(MAX_BARRIER_MS), [] { return event.released; }))
            event.timed_out = true;
        // A barrier timeout is observable test failure, never a synthetic allocation failure.
    }
    return ggml_backend_alloc_ctx_tensors(context, backend);
}
void destroyed(std::uint64_t token) {
    if (token == 0) return;
    std::lock_guard<std::mutex> lock(mutex);
    if (event.token == token) { ++event.destructions; changed.notify_all(); }
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_focal_speech_NativeFaultHooks_identity(JNIEnv *env, jobject) {
    return env->NewStringUTF("focal-native-fault-v1:" FOCAL_NATIVE_FAULT_SOURCE_SHA);
}
extern "C" JNIEXPORT jlong JNICALL
Java_app_focal_speech_NativeFaultHooks_arm(JNIEnv *env, jobject, jint mode) {
    try {
        std::lock_guard<std::mutex> lock(mutex);
        require(mode == FAIL_ALLOCATION || mode == PAUSE_ALLOCATION, "Unknown native fault mode.");
        require(event.token == 0 && armed_token == 0 && decode_token == 0, "A native fault is already armed.");
        require(next_token < static_cast<std::uint64_t>(std::numeric_limits<jlong>::max()), "Native fault tokens exhausted.");
        event = Event{};
        event.mode = mode;
        event.token = armed_token = next_token++;
        return static_cast<jlong>(event.token);
    } catch (const std::exception &error) { fail(env, error.what()); }
    catch (...) { fail(env, "Could not arm native fault."); }
    return 0;
}
extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NativeFaultHooks_disarm(JNIEnv *, jobject) { armed_token = 0; }
extern "C" JNIEXPORT jboolean JNICALL
Java_app_focal_speech_NativeFaultHooks_awaitEntered(JNIEnv *env, jobject, jlong token, jlong timeout_ms) {
    try {
        require(timeout_ms > 0 && timeout_ms <= MAX_ENTER_WAIT_MS, "Invalid native fault wait timeout.");
        std::unique_lock<std::mutex> lock(mutex);
        valid_token(token);
        changed.wait_for(lock, std::chrono::milliseconds(timeout_ms), [] { return event.entered || event.finished; });
        return event.entered;
    } catch (const std::exception &error) { fail(env, error.what()); }
    catch (...) { fail(env, "Could not wait for native fault."); }
    return JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NativeFaultHooks_release(JNIEnv *env, jobject, jlong token) {
    try {
        std::lock_guard<std::mutex> lock(mutex);
        valid_token(token);
        event.released = true;
        changed.notify_all();
    } catch (const std::exception &error) { fail(env, error.what()); }
    catch (...) { fail(env, "Could not release native fault."); }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_app_focal_speech_NativeFaultHooks_snapshotRaw(JNIEnv *env, jobject, jlong token) {
    try {
        std::lock_guard<std::mutex> lock(mutex);
        valid_token(token);
        const jlong values[] = {event.handle, event.encoder_completions, event.old_decoders, event.new_decoders,
            event.allocation_entries, event.refusals, event.entered, event.released, event.finished,
            event.destructions, event.has_result, event.result, event.timed_out, event.invalid_target};
        const auto array = env->NewLongArray(14);
        if (array) env->SetLongArrayRegion(array, 0, 14, values);
        return array;
    } catch (const std::exception &error) { fail(env, error.what()); }
    catch (...) { fail(env, "Could not inspect native fault."); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL
Java_app_focal_speech_NativeFaultHooks_clear(JNIEnv *env, jobject, jlong token) {
    try {
        std::lock_guard<std::mutex> lock(mutex);
        valid_token(token);
        require(event.handle == 0 || (event.finished && event.destructions == 1), "Close the fault engine and join decode before clearing.");
        event = Event{};
    } catch (const std::exception &error) { fail(env, error.what()); }
    catch (...) { fail(env, "Could not clear native fault."); }
}
