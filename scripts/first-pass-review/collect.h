// Benchmark-only instrumentation for the single-stream TDT greedy decoder.
// The original predictor state, emitted token and duration are never modified.
#pragma once

#include <cstdio>
#include <cstdlib>

namespace focal_review {

inline std::FILE *begin_window() {
  static std::FILE *out = []() -> std::FILE * {
    const char *path = std::getenv("FOCAL_REVIEW_TRACE");
    if (!path) return nullptr;
    auto *file = std::fopen(path, "w");
    if (!file) std::abort();
    return file;
  }();
  if (out && std::fputs("W\n", out) < 0) std::abort();
  return out;
}

inline void record(std::FILE *out, size_t index, int winner, const float *logp,
                   int vocab_size) {
  if (!out || logp[winner] >= -0.69314718056f) return;
  int second = -1;
  int third = -1;
  for (int id = 0; id < vocab_size; ++id) {
    if (id == winner) continue;
    if (second < 0 || logp[id] > logp[second]) {
      third = second;
      second = id;
    } else if (third < 0 || logp[id] > logp[third]) {
      third = id;
    }
  }
  if (third < 0 || std::fprintf(out, "%zu\t%d\t%.9g\t%d\t%.9g\t%d\t%.9g\n",
        index, winner, logp[winner], second, logp[second], third, logp[third]) < 0)
    std::abort();
}

}  // namespace focal_review
