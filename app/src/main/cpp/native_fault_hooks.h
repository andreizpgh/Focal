#pragma once

#include <cstdint>

struct ggml_context;
struct ggml_backend;
struct ggml_backend_buffer;

// Linked only into the explicitly enabled native-fault test artifact.
namespace focal_native_fault {
class DecodeScope {
public:
    DecodeScope(std::int64_t handle, const void *context, std::uint64_t &engine_token);
    ~DecodeScope();
    void returned(int result);
    DecodeScope(const DecodeScope &) = delete;
    DecodeScope &operator=(const DecodeScope &) = delete;
private:
    std::uint64_t token_ = 0;
};

void encoded(const void *context, const void *state);
class GrowthScope {
public:
    GrowthScope(const void *context, const void *state, int old_decoders, int new_decoders);
    ~GrowthScope();
    GrowthScope(const GrowthScope &) = delete;
    GrowthScope &operator=(const GrowthScope &) = delete;
};
ggml_backend_buffer *allocate_kv(ggml_context *context, ggml_backend *backend);
void destroyed(std::uint64_t token);
}
