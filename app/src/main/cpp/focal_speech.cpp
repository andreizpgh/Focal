// Focal's JNI bridge over the sherpa-onnx C API.
//
// Focal links only libsherpa-onnx-c-api.so (and the ONNX Runtime it needs) from the pinned
// sherpa-onnx 2.0.1 AAR. Unlike the AAR's Kotlin API, the C API returns per-token log
// probabilities, and it exposes the Silero voice activity detector without a Kotlin layer.
//
// Rules for every entry point:
// - No C++ exception reaches the JVM: failures become IllegalArgumentException,
//   IllegalStateException or OutOfMemoryError, and the function returns a neutral value.
// - Every native object is released on every path (RAII guards).
// - Inputs that make sherpa-onnx terminate the process instead of returning an error are rejected
//   here first: an unreadable provider config file, a sample rate other than 16 kHz and a voice
//   activity window of the wrong length.
// - Strings from the runtime cross as UTF-8 bytes, never through NewStringUTF, so a malformed
//   token cannot abort the VM; Kotlin decodes them with replacement characters.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <new>
#include <string>
#include <vector>

#include "sherpa-onnx/c-api/c-api.h"

namespace {

constexpr int32_t kSampleRate = 16000;
constexpr int32_t kFeatureDim = 80;
constexpr jint kMaxThreads = 16;
// Silero VAD v4 at 16 kHz, exactly as in Focal's segmentation experiments.
constexpr jint kVadWindow = 512;
// The detector keeps at most one window plus its pre-roll, because Focal flushes and clears its
// segment queue after every window. One second is ample and never reallocates.
constexpr float kVadBufferSeconds = 1.0f;

// Decode result layout shared with SherpaBridge.kt.
enum ResultField : jsize {
  kText = 0,         // byte[] UTF-8
  kTokenBytes = 1,   // byte[] UTF-8 of all tokens, concatenated
  kTokenEnds = 2,    // int[] end offset of token i in kTokenBytes
  kTimestamps = 3,   // float[] seconds, or null
  kDurations = 4,    // float[] seconds, or null
  kLogProbs = 5,     // float[] natural log, or null
  kResultFields = 6,
};

void Throw(JNIEnv *env, const char *type, const char *message) {
  if (env->ExceptionCheck()) return;  // Keep the first exception, for example an OutOfMemoryError.
  jclass cls = env->FindClass(type);
  if (cls != nullptr) env->ThrowNew(cls, message);
}

void ThrowArgument(JNIEnv *env, const char *message) {
  Throw(env, "java/lang/IllegalArgumentException", message);
}

void ThrowState(JNIEnv *env, const char *message) {
  Throw(env, "java/lang/IllegalStateException", message);
}

void ThrowOutOfMemory(JNIEnv *env) {
  Throw(env, "java/lang/OutOfMemoryError", "The speech runtime ran out of memory.");
}

// A Java string as modified UTF-8 for the lifetime of this object (null stays null). Once an
// earlier conversion has failed (OutOfMemoryError pending), no further JNI call is made.
class JavaUtf {
 public:
  JavaUtf(JNIEnv *env, jstring value)
      : env_(env), value_(value),
        chars_(value != nullptr && !env->ExceptionCheck() ? env->GetStringUTFChars(value, nullptr)
                                                          : nullptr) {}
  ~JavaUtf() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }
  JavaUtf(const JavaUtf &) = delete;
  JavaUtf &operator=(const JavaUtf &) = delete;

  const char *get() const { return chars_; }
  bool nonEmpty() const { return chars_ != nullptr && chars_[0] != '\0'; }

 private:
  JNIEnv *env_;
  jstring value_;
  const char *chars_;
};

bool Readable(const char *path) {
  std::FILE *file = std::fopen(path, "rb");
  if (file == nullptr) return false;
  std::fclose(file);
  return true;
}

template <typename T>
jlong ToHandle(const T *pointer) {
  return static_cast<jlong>(reinterpret_cast<std::intptr_t>(pointer));
}

template <typename T>
const T *FromHandle(jlong handle) {
  return reinterpret_cast<const T *>(static_cast<std::intptr_t>(handle));
}

class StreamGuard {
 public:
  explicit StreamGuard(const SherpaOnnxOfflineStream *stream) : stream_(stream) {}
  ~StreamGuard() {
    if (stream_ != nullptr) SherpaOnnxDestroyOfflineStream(stream_);
  }
  StreamGuard(const StreamGuard &) = delete;
  StreamGuard &operator=(const StreamGuard &) = delete;
  const SherpaOnnxOfflineStream *get() const { return stream_; }

 private:
  const SherpaOnnxOfflineStream *stream_;
};

class ResultGuard {
 public:
  explicit ResultGuard(const SherpaOnnxOfflineRecognizerResult *result) : result_(result) {}
  ~ResultGuard() {
    if (result_ != nullptr) SherpaOnnxDestroyOfflineRecognizerResult(result_);
  }
  ResultGuard(const ResultGuard &) = delete;
  ResultGuard &operator=(const ResultGuard &) = delete;
  const SherpaOnnxOfflineRecognizerResult *get() const { return result_; }

 private:
  const SherpaOnnxOfflineRecognizerResult *result_;
};

// Stores value at index and drops the local reference. False if the value is missing (an
// exception is pending then).
bool Store(JNIEnv *env, jobjectArray array, jsize index, jobject value) {
  if (value == nullptr) return false;
  env->SetObjectArrayElement(array, index, value);
  env->DeleteLocalRef(value);
  return !env->ExceptionCheck();
}

jbyteArray Bytes(JNIEnv *env, const char *data, size_t size) {
  if (size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    ThrowState(env, "The speech runtime returned an oversized result.");
    return nullptr;
  }
  jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
  if (array != nullptr && size > 0) {
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
  }
  return array;
}

jfloatArray Floats(JNIEnv *env, const float *data, jsize size) {
  jfloatArray array = env->NewFloatArray(size);
  if (array != nullptr && size > 0) env->SetFloatArrayRegion(array, 0, size, data);
  return array;
}

// A per-token array: null when the runtime did not provide it (not an error by itself).
bool StoreOptionalFloats(JNIEnv *env, jobjectArray array, jsize index, const float *data, jsize size) {
  if (data == nullptr) return true;
  return Store(env, array, index, Floats(env, data, size));
}

struct Vad {
  const SherpaOnnxVoiceActivityDetector *detector;
  std::vector<float> window;
};

}  // namespace

extern "C" {

JNIEXPORT jobjectArray JNICALL Java_app_focal_speech_SherpaBridge_runtimeInfo(JNIEnv *env, jclass) {
  try {
    const char *values[] = {SherpaOnnxGetVersionStr(), SherpaOnnxGetGitSha1(), SherpaOnnxGetGitDate(),
                            SherpaOnnxGetOnnxruntimeVersionStr()};
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(4, string_class, nullptr);
    if (out == nullptr) return nullptr;
    for (jsize i = 0; i < 4; ++i) {
      // Version strings are ASCII; anything else is replaced so NewStringUTF stays safe.
      std::string ascii = values[i] != nullptr ? values[i] : "";
      for (char &c : ascii) {
        if (static_cast<unsigned char>(c) < 0x20 || static_cast<unsigned char>(c) > 0x7e) c = '?';
      }
      if (!Store(env, out, i, env->NewStringUTF(ascii.c_str()))) return nullptr;
    }
    return out;
  } catch (const std::bad_alloc &) {
    ThrowOutOfMemory(env);
  } catch (...) {
    ThrowState(env, "The speech runtime could not report its version.");
  }
  return nullptr;
}

JNIEXPORT jlong JNICALL Java_app_focal_speech_SherpaBridge_createRecognizer(
    JNIEnv *env, jclass, jstring encoder, jstring decoder, jstring joiner, jstring tokens,
    jstring model_type, jstring decoding_method, jint num_threads, jstring provider,
    jstring provider_config) {
  try {
    JavaUtf encoder_path(env, encoder), decoder_path(env, decoder), joiner_path(env, joiner),
        tokens_path(env, tokens), type(env, model_type), method(env, decoding_method),
        provider_name(env, provider), config_path(env, provider_config);
    if (env->ExceptionCheck()) return 0;
    if (!encoder_path.nonEmpty() || !decoder_path.nonEmpty() || !joiner_path.nonEmpty() ||
        !tokens_path.nonEmpty() || !type.nonEmpty() || !method.nonEmpty() || !provider_name.nonEmpty()) {
      ThrowArgument(env, "Every recognizer path and option is required.");
      return 0;
    }
    if (num_threads < 1 || num_threads > kMaxThreads) {
      ThrowArgument(env, "The recognizer thread count is out of range.");
      return 0;
    }
    for (const JavaUtf *path : {&encoder_path, &decoder_path, &joiner_path, &tokens_path}) {
      if (!Readable(path->get())) {
        ThrowState(env, "A speech model file is missing or unreadable.");
        return 0;
      }
    }
    std::string provider_string = provider_name.get();
    if (provider_string.find(':') != std::string::npos) {
      ThrowArgument(env, "Pass the provider config file separately.");
      return 0;
    }
    if (provider_config != nullptr) {
      // sherpa-onnx exits the process when "provider:path" names a file it cannot open.
      if (!config_path.nonEmpty() || !Readable(config_path.get())) {
        ThrowState(env, "The speech runtime session config file is missing or unreadable.");
        return 0;
      }
      provider_string += ':';
      provider_string += config_path.get();
    }

    SherpaOnnxOfflineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = kSampleRate;
    config.feat_config.feature_dim = kFeatureDim;  // NeMo models replace it with their own front end.
    config.model_config.transducer.encoder = encoder_path.get();
    config.model_config.transducer.decoder = decoder_path.get();
    config.model_config.transducer.joiner = joiner_path.get();
    config.model_config.tokens = tokens_path.get();
    config.model_config.num_threads = num_threads;
    config.model_config.debug = 0;
    config.model_config.provider = provider_string.c_str();
    config.model_config.model_type = type.get();
    config.decoding_method = method.get();

    const SherpaOnnxOfflineRecognizer *recognizer = SherpaOnnxCreateOfflineRecognizer(&config);
    if (recognizer == nullptr) {
      ThrowState(env, "The speech recognizer could not be created.");
      return 0;
    }
    return ToHandle(recognizer);
  } catch (const std::bad_alloc &) {
    ThrowOutOfMemory(env);
  } catch (...) {
    ThrowState(env, "The speech runtime failed while creating the recognizer.");
  }
  return 0;
}

JNIEXPORT void JNICALL Java_app_focal_speech_SherpaBridge_destroyRecognizer(JNIEnv *env, jclass,
                                                                           jlong handle) {
  try {
    SherpaOnnxDestroyOfflineRecognizer(FromHandle<SherpaOnnxOfflineRecognizer>(handle));
  } catch (...) {
    ThrowState(env, "The speech runtime failed while releasing the recognizer.");
  }
}

JNIEXPORT jobjectArray JNICALL Java_app_focal_speech_SherpaBridge_decode(
    JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint offset, jint length) {
  try {
    const auto *recognizer = FromHandle<SherpaOnnxOfflineRecognizer>(handle);
    if (recognizer == nullptr || samples == nullptr) {
      ThrowArgument(env, "Decode needs an open recognizer and samples.");
      return nullptr;
    }
    const jsize size = env->GetArrayLength(samples);
    if (offset < 0 || length <= 0 || offset > size - length) {
      ThrowArgument(env, "The sample range is outside the array.");
      return nullptr;
    }
    std::vector<float> pcm(static_cast<size_t>(length));
    env->GetFloatArrayRegion(samples, offset, length, pcm.data());
    if (env->ExceptionCheck()) return nullptr;

    StreamGuard stream(SherpaOnnxCreateOfflineStream(recognizer));
    if (stream.get() == nullptr) {
      ThrowState(env, "The speech runtime could not create a stream.");
      return nullptr;
    }
    SherpaOnnxAcceptWaveformOffline(stream.get(), kSampleRate, pcm.data(), length);
    SherpaOnnxDecodeOfflineStream(recognizer, stream.get());
    ResultGuard result(SherpaOnnxGetOfflineStreamResult(stream.get()));
    const SherpaOnnxOfflineRecognizerResult *r = result.get();
    if (r == nullptr || r->count < 0 || (r->count > 0 && r->tokens_arr == nullptr)) {
      ThrowState(env, "The speech runtime returned a malformed result.");
      return nullptr;
    }

    // Per-token arrays from the C API hold exactly `count` entries when present.
    const jsize count = r->count;
    std::string token_bytes;
    std::vector<jint> token_ends(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
      const char *token = r->tokens_arr[i];
      if (token == nullptr) {
        ThrowState(env, "The speech runtime returned a malformed token.");
        return nullptr;
      }
      token_bytes.append(token);
      if (token_bytes.size() > static_cast<size_t>(std::numeric_limits<jint>::max())) {
        ThrowState(env, "The speech runtime returned an oversized result.");
        return nullptr;
      }
      token_ends[static_cast<size_t>(i)] = static_cast<jint>(token_bytes.size());
    }

    jclass object_class = env->FindClass("java/lang/Object");
    if (object_class == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(kResultFields, object_class, nullptr);
    if (out == nullptr) return nullptr;
    const char *text = r->text != nullptr ? r->text : "";
    if (!Store(env, out, kText, Bytes(env, text, std::strlen(text)))) return nullptr;
    if (!Store(env, out, kTokenBytes, Bytes(env, token_bytes.data(), token_bytes.size()))) return nullptr;
    jintArray ends = env->NewIntArray(count);
    if (ends != nullptr && count > 0) env->SetIntArrayRegion(ends, 0, count, token_ends.data());
    if (!Store(env, out, kTokenEnds, ends)) return nullptr;
    if (!StoreOptionalFloats(env, out, kTimestamps, r->timestamps, count) ||
        !StoreOptionalFloats(env, out, kDurations, r->durations, count) ||
        !StoreOptionalFloats(env, out, kLogProbs, r->ys_log_probs, count)) {
      return nullptr;
    }
    return out;
  } catch (const std::bad_alloc &) {
    ThrowOutOfMemory(env);
  } catch (...) {
    ThrowState(env, "The speech runtime failed while decoding.");
  }
  return nullptr;
}

JNIEXPORT jlong JNICALL Java_app_focal_speech_SherpaBridge_createVad(
    JNIEnv *env, jclass, jstring model, jfloat threshold, jfloat min_silence_seconds,
    jfloat min_speech_seconds, jint window_size, jfloat max_speech_seconds, jint num_threads) {
  try {
    JavaUtf model_path(env, model);
    if (env->ExceptionCheck()) return 0;
    if (!model_path.nonEmpty()) {
      ThrowArgument(env, "The voice activity model path is required.");
      return 0;
    }
    // The C API replaces zeros with its own defaults, so every value must be set and positive.
    if (!(threshold > 0.0f && threshold < 1.0f) || !(min_silence_seconds > 0.0f) ||
        !(min_speech_seconds > 0.0f) || !(max_speech_seconds > 0.0f) || window_size != kVadWindow ||
        num_threads < 1 || num_threads > kMaxThreads) {
      ThrowArgument(env, "Invalid voice activity detector settings.");
      return 0;
    }
    if (!Readable(model_path.get())) {
      ThrowState(env, "The voice activity model is missing or unreadable.");
      return 0;
    }

    SherpaOnnxVadModelConfig config;
    std::memset(&config, 0, sizeof(config));
    config.silero_vad.model = model_path.get();
    config.silero_vad.threshold = threshold;
    config.silero_vad.min_silence_duration = min_silence_seconds;
    config.silero_vad.min_speech_duration = min_speech_seconds;
    config.silero_vad.window_size = window_size;
    config.silero_vad.max_speech_duration = max_speech_seconds;
    config.sample_rate = kSampleRate;
    config.num_threads = num_threads;
    config.provider = "cpu";
    config.debug = 0;

    auto *vad = new Vad{nullptr, std::vector<float>(static_cast<size_t>(window_size))};
    vad->detector = SherpaOnnxCreateVoiceActivityDetector(&config, kVadBufferSeconds);
    if (vad->detector == nullptr) {
      delete vad;
      ThrowState(env, "The voice activity detector could not be created.");
      return 0;
    }
    return ToHandle(vad);
  } catch (const std::bad_alloc &) {
    ThrowOutOfMemory(env);
  } catch (...) {
    ThrowState(env, "The speech runtime failed while creating the voice activity detector.");
  }
  return 0;
}

// Feeds exactly one window and returns the detector's speech decision for it: the Silero
// probability with sherpa-onnx's hysteresis (minimum speech and silence durations).
//
// The C API's detector also collects speech segments, copying the whole current segment on every
// window. Flushing while speech continues and clearing the queue after every window keep that
// memory and work bounded to one window. Neither touches the model or its hysteresis state, so
// the decisions equal SileroVadModel::IsSpeech, as in the experiments. Because the segment buffer
// never grows, max_speech_duration's higher threshold for long segments never applies.
JNIEXPORT jboolean JNICALL Java_app_focal_speech_SherpaBridge_vadAcceptWindow(JNIEnv *env, jclass,
                                                                             jlong handle,
                                                                             jfloatArray window) {
  try {
    auto *vad = const_cast<Vad *>(FromHandle<Vad>(handle));
    if (vad == nullptr || window == nullptr) {
      ThrowArgument(env, "The voice activity detector is closed or the window is missing.");
      return JNI_FALSE;
    }
    const jsize size = static_cast<jsize>(vad->window.size());
    if (env->GetArrayLength(window) != size) {
      ThrowArgument(env, "A voice activity window must hold exactly 512 samples.");
      return JNI_FALSE;
    }
    env->GetFloatArrayRegion(window, 0, size, vad->window.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad->detector, vad->window.data(), size);
    const bool speech = SherpaOnnxVoiceActivityDetectorDetected(vad->detector) != 0;
    if (speech) SherpaOnnxVoiceActivityDetectorFlush(vad->detector);
    SherpaOnnxVoiceActivityDetectorClear(vad->detector);
    return speech ? JNI_TRUE : JNI_FALSE;
  } catch (const std::bad_alloc &) {
    ThrowOutOfMemory(env);
  } catch (...) {
    ThrowState(env, "The speech runtime failed in the voice activity detector.");
  }
  return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_app_focal_speech_SherpaBridge_vadReset(JNIEnv *env, jclass, jlong handle) {
  try {
    const Vad *vad = FromHandle<Vad>(handle);
    if (vad == nullptr) {
      ThrowArgument(env, "The voice activity detector is closed.");
      return;
    }
    SherpaOnnxVoiceActivityDetectorReset(vad->detector);
  } catch (...) {
    ThrowState(env, "The speech runtime failed while resetting the voice activity detector.");
  }
}

JNIEXPORT void JNICALL Java_app_focal_speech_SherpaBridge_destroyVad(JNIEnv *env, jclass, jlong handle) {
  try {
    const Vad *vad = FromHandle<Vad>(handle);
    if (vad == nullptr) return;
    SherpaOnnxDestroyVoiceActivityDetector(vad->detector);
    delete vad;
  } catch (...) {
    ThrowState(env, "The speech runtime failed while releasing the voice activity detector.");
  }
}

}  // extern "C"
