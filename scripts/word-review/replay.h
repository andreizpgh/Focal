// Used only when replaying the released encoder's private CPU tensor fixture.
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "onnxruntime_cxx_api.h"

inline std::vector<Ort::Value> replay_encoding(OrtAllocator *allocator, const char *path) {
  static auto *file = std::fopen(path, "rb");
  uint32_t dims[2];
  if (!file || std::fread(dims, sizeof(uint32_t), 2, file) != 2 ||
      dims[0] == 0 || dims[0] > 512 || dims[1] != 1024) std::abort();
  std::vector<float> frames(size_t(dims[0]) * dims[1]);
  if (std::fread(frames.data(), sizeof(float), frames.size(), file) != frames.size()) std::abort();
  const int64_t shape[] = {1, dims[1], dims[0]}, one[] = {1};
  std::vector<Ort::Value> result;
  result.push_back(Ort::Value::CreateTensor<float>(allocator, shape, 3));
  result.push_back(Ort::Value::CreateTensor<int64_t>(allocator, one, 1));
  float *dest = result[0].GetTensorMutableData<float>();
  for (uint32_t t = 0; t < dims[0]; ++t)
    for (uint32_t c = 0; c < dims[1]; ++c)
      dest[c*dims[0]+t] = frames[t*dims[1]+c];
  *result[1].GetTensorMutableData<int64_t>() = dims[0];
  return result;
}
