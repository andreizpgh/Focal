// Private replay fixture. Stream one window in the TDT decoder's (time, feature) layout.
#include "onnxruntime_c_api.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

void save_encoding(const OrtApi *api, OrtValue *const *outputs) {
  static auto *file = [] {
    const char *path = std::getenv("FOCAL_WORD_ENCODINGS");
    return path ? std::fopen(path, "wb") : nullptr;
  }();
  if (!file) std::abort();
  auto check = [](OrtStatus *s) { if (s) std::abort(); };
  OrtTensorTypeAndShapeInfo *shape = nullptr;
  check(api->GetTensorTypeAndShape(outputs[0], &shape));
  size_t rank = 0;
  check(api->GetDimensionsCount(shape, &rank));
  if (rank != 3) std::abort();
  int64_t dims[3];
  check(api->GetDimensions(shape, dims, 3));
  ONNXTensorElementDataType type;
  check(api->GetTensorElementType(shape, &type));
  api->ReleaseTensorTypeAndShapeInfo(shape);
  if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || dims[0] != 1 || dims[1] != 1024 ||
      dims[2] <= 0 || dims[2] > 512) std::abort();
  check(api->GetTensorTypeAndShape(outputs[1], &shape));
  check(api->GetTensorElementType(shape, &type));
  api->ReleaseTensorTypeAndShapeInfo(shape);
  const void *length = nullptr, *data = nullptr;
  check(api->GetTensorData(outputs[1], &length));
  check(api->GetTensorData(outputs[0], &data));
  if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32 && type != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) std::abort();
  const int64_t rows = type == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32 ?
      *static_cast<const int32_t *>(length) : *static_cast<const int64_t *>(length);
  if (rows <= 0 || rows > dims[2]) std::abort();
  const uint32_t header[] = {static_cast<uint32_t>(rows), static_cast<uint32_t>(dims[1])};
  std::vector<float> transposed(rows * dims[1]);
  for (int t = 0; t < rows; ++t)
    for (int c = 0; c < dims[1]; ++c)
      transposed[t*dims[1]+c] = static_cast<const float *>(data)[c*dims[2]+t];
  if (std::fwrite(header, sizeof(uint32_t), 2, file) != 2 ||
      std::fwrite(transposed.data(), sizeof(float), transposed.size(), file) != transposed.size())
    std::abort();
}
