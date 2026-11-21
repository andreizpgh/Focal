// Diagnostic executable only. Observe public ORT Run results from the bundled
// recognizer in this process, without altering any tensor or decoder choice.
#include "onnxruntime_c_api.h"
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#ifdef FOCAL_CAPTURE_ENCODINGS
void save_encoding(const OrtApi *, OrtValue *const *);
#endif

namespace {
const OrtApiBase *original_base() {
  static const OrtApiBase *base = [] {
    auto *handle = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) std::abort();
    auto get = reinterpret_cast<decltype(&OrtGetApiBase)>(dlsym(handle, "OrtGetApiBase"));
    if (!get || get == &OrtGetApiBase) std::abort();
    return get();
  }();
  return base;
}

const OrtApi *original() {
  return original_base()->GetApi(ORT_API_VERSION);
}

std::FILE *output() {
  static std::FILE *file = [] {
    const char *path = std::getenv("FOCAL_REVIEW_LOGITS");
    if (!path) std::abort();
    std::FILE *out = std::fopen(path, "w");
    if (!out) std::abort();
    return out;
  }();
  return file;
}

OrtStatus *ORT_API_CALL observe(OrtSession *session, const OrtRunOptions *opts,
    const char *const *names, const OrtValue *const *values, size_t count,
    const char *const *outputs, size_t output_count, OrtValue **results) noexcept {
  auto *api = original();
  auto *status = api->Run(session, opts, names, values, count, outputs, output_count, results);
  if (status) return status;
  // The pinned transducer has exactly one encoder run per single-stream window.
  if (count == 2 && output_count == 2) {
#ifdef FOCAL_CAPTURE_ENCODINGS
    save_encoding(api, results);
#else
    if (std::fputs("W\n", output()) < 0) std::abort();
#endif
    return nullptr;
  }
#ifdef FOCAL_CAPTURE_ENCODINGS
  return nullptr;
#endif
  if (count != 2 || output_count != 1 || std::strcmp(names[0], "encoder_outputs") ||
      std::strcmp(names[1], "decoder_outputs")) return nullptr;

  // Ultra's joiner has 8,193 vocabulary scores followed by the five TDT durations.
  constexpr int vocab = 8193;
  OrtTensorTypeAndShapeInfo *info = nullptr;
  if ((status = api->GetTensorTypeAndShape(results[0], &info))) return status;
  size_t size = 0;
  ONNXTensorElementDataType type;
  status = api->GetTensorShapeElementCount(info, &size);
  if (!status) status = api->GetTensorElementType(info, &type);
  api->ReleaseTensorTypeAndShapeInfo(info);
  if (status) return status;
  if (size != vocab + 5 || type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) std::abort();
  const void *data = nullptr;
  if ((status = api->GetTensorData(results[0], &data))) return status;
  const float *scores = static_cast<const float *>(data);
  const int winner = std::max_element(scores, scores + vocab) - scores;
  if (winner == vocab - 1) return nullptr;
  std::array<int, 2> runners{-1, -1};
  for (int i = 0; i < vocab; ++i) {
    if (i == winner) continue;
    if (runners[0] < 0 || scores[i] > scores[runners[0]]) {
      runners[1] = runners[0];
      runners[0] = i;
    } else if (runners[1] < 0 || scores[i] > scores[runners[1]]) {
      runners[1] = i;
    }
  }
  if (std::fprintf(output(), "%d\t%d\t%.9g\t%d\t%.9g\n", winner, runners[0],
      scores[runners[0]] - scores[winner], runners[1], scores[runners[1]] - scores[winner]) < 0) std::abort();
  return nullptr;
}

const OrtApi *ORT_API_CALL get_api(uint32_t version) noexcept {
  const OrtApi *requested = original_base()->GetApi(version);
  // ORT can return the same backward-compatible table to older C++ clients.
  if (!requested || requested != original()) return requested;
  static const OrtApi observed = [] {
    OrtApi copy = *original();
    copy.Run = observe;
    return copy;
  }();
  return &observed;
}
}

extern "C" const OrtApiBase *ORT_API_CALL OrtGetApiBase() noexcept {
  static const OrtApiBase base{get_api, original_base()->GetVersionString};
  return &base;
}
