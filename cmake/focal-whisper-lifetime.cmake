include_guard(GLOBAL)

# Optional ownership-only fix against the pinned engine. Generate a build copy;
# never mutate the submodule or combine it with speech-policy research overlays.
function(focal_whisper_lifetime_overlay)
    if(NOT TARGET whisper)
        message(FATAL_ERROR "The Focal lifetime overlay requires the whisper target.")
    endif()
    get_target_property(whisper_imported whisper IMPORTED)
    if(whisper_imported)
        message(FATAL_ERROR "The Focal lifetime overlay requires a source-built whisper target.")
    endif()
    get_target_property(whisper_source_dir whisper SOURCE_DIR)
    get_target_property(whisper_binary_dir whisper BINARY_DIR)
    get_target_property(whisper_sources whisper SOURCES)
    set(original "${whisper_source_dir}/whisper.cpp")
    get_filename_component(header "${whisper_source_dir}/../include/whisper.h" ABSOLUTE)
    set(overlay "${whisper_binary_dir}/focal-lifetime/whisper.cpp")
    set(original_count 0)
    set(header_count 0)
    set(overlay_sources)
    foreach(source IN LISTS whisper_sources)
        get_filename_component(source_absolute "${source}" ABSOLUTE BASE_DIR "${whisper_source_dir}")
        if(source_absolute STREQUAL original)
            math(EXPR original_count "${original_count} + 1")
            list(APPEND overlay_sources "${overlay}")
        else()
            if(source_absolute STREQUAL header)
                math(EXPR header_count "${header_count} + 1")
            endif()
            list(APPEND overlay_sources "${source}")
        endif()
    endforeach()
    if(NOT original_count EQUAL 1 OR NOT header_count EQUAL 1)
        message(FATAL_ERROR "The Focal lifetime overlay requires exactly one original whisper.cpp and whisper.h; repeated or composed overlays are rejected.")
    endif()
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${original}" "${header}")
    file(READ "${original}" content)
    file(READ "${header}" header_content)
    string(REPLACE "\r\n" "\n" content "${content}")
    string(REPLACE "\r\n" "\n" header_content "${header_content}")
    string(SHA256 original_sha256 "${content}")
    string(SHA256 header_sha256 "${header_content}")
    if(NOT original_sha256 STREQUAL "02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e"
            OR NOT header_sha256 STREQUAL "0525b758571ab71c00ddd8ea2845f74af7c62dfa5100c5586655f75bdc5ef4be")
        message(FATAL_ERROR "The Focal lifetime overlay requires the pinned preimages (4979e04f5dcaccb36057e059bbaed8a2f5288315).")
    endif()

    string(REPLACE [=[
#include <fstream>
#include <functional>
#include <map>
#include <random>
#include <regex>
#include <set>
]=] [=[
#include <fstream>
#include <functional>
#include <map>
#include <memory>
#include <random>
#include <regex>
#include <set>
]=] content "${content}")
    string(REPLACE [=[

    std::vector<whisper_kv_cell> cells;

    struct ggml_tensor * k;
    struct ggml_tensor * v;

    ggml_backend_buffer_t buffer = nullptr;

]=] [=[

    std::vector<whisper_kv_cell> cells;

    struct ggml_tensor * k = nullptr;
    struct ggml_tensor * v = nullptr;

    ggml_backend_buffer_t buffer = nullptr;

]=] content "${content}")
    string(REPLACE [=[

    whisper_mel mel;

    whisper_batch batch;

    whisper_decoder decoders[WHISPER_MAX_DECODERS];

]=] [=[

    whisper_mel mel;

    whisper_batch batch = {};

    whisper_decoder decoders[WHISPER_MAX_DECODERS];

]=] content "${content}")
    string(REPLACE [=[
    BYTESWAP_VALUE(dest);
}

static bool whisper_kv_cache_init(
             struct whisper_kv_cache & cache,
                      ggml_backend_t   backend,
]=] [=[
    BYTESWAP_VALUE(dest);
}

static void whisper_kv_cache_free(struct whisper_kv_cache & cache) {
    ggml_backend_buffer_free(cache.buffer);
    cache.buffer = nullptr;
    cache.k = nullptr;
    cache.v = nullptr;
    cache.head = 0;
    cache.size = 0;
    cache.n = 0;
    cache.cells.clear();
}

static bool whisper_kv_cache_init(
             struct whisper_kv_cache & cache,
                      ggml_backend_t   backend,
]=] content "${content}")
    string(REPLACE [=[
    const int64_t n_mem      = n_text_layer*n_ctx;
    const int64_t n_elements = n_text_state*n_mem;

    cache.ctx_buf.resize(2*ggml_tensor_overhead());

    struct ggml_init_params params = {
        /*.mem_size   =*/ cache.ctx_buf.size(),
        /*.mem_buffer =*/ cache.ctx_buf.data(),
        /*.no_alloc   =*/ true,
    };

    cache.head = 0;
    cache.size = n_ctx;

    cache.cells.clear();
    cache.cells.resize(n_ctx);

    struct ggml_context * ctx = ggml_init(params);

    if (!ctx) {
        WHISPER_LOG_ERROR("%s: failed to allocate memory for the kv cache context\n", __func__);
        return false;
    }

    cache.k = ggml_new_tensor_1d(ctx, wtype, n_elements);
    cache.v = ggml_new_tensor_1d(ctx, wtype, n_elements);

    cache.buffer = ggml_backend_alloc_ctx_tensors(ctx, backend);
    if (!cache.buffer) {
        WHISPER_LOG_ERROR("%s: failed to allocate memory for the kv cache\n", __func__);
        return false;
    }

    ggml_backend_buffer_clear(cache.buffer, 0);

    ggml_free(ctx);

    return true;
}

static void whisper_kv_cache_free(struct whisper_kv_cache & cache) {
    ggml_backend_buffer_free(cache.buffer);
}

static bool whisper_kv_cache_find_slot(
]=] [=[
    const int64_t n_mem      = n_text_layer*n_ctx;
    const int64_t n_elements = n_text_state*n_mem;

    // Keep the previous cache usable until the replacement is complete.
    whisper_kv_cache next;
    next.ctx_buf.resize(2*ggml_tensor_overhead());
    next.size = n_ctx;
    next.cells.resize(n_ctx);

    struct ggml_init_params params = {
        /*.mem_size   =*/ next.ctx_buf.size(),
        /*.mem_buffer =*/ next.ctx_buf.data(),
        /*.no_alloc   =*/ true,
    };
    std::unique_ptr<ggml_context, decltype(&ggml_free)> ctx(ggml_init(params), ggml_free);
    if (!ctx) {
        WHISPER_LOG_ERROR("%s: failed to allocate memory for the kv cache context\n", __func__);
        return false;
    }

    next.k = ggml_new_tensor_1d(ctx.get(), wtype, n_elements);
    next.v = ggml_new_tensor_1d(ctx.get(), wtype, n_elements);
    std::unique_ptr<ggml_backend_buffer, decltype(&ggml_backend_buffer_free)> buffer(
            ggml_backend_alloc_ctx_tensors(ctx.get(), backend), ggml_backend_buffer_free);
    if (!buffer) {
        WHISPER_LOG_ERROR("%s: failed to allocate memory for the kv cache\n", __func__);
        return false;
    }
    ggml_backend_buffer_clear(buffer.get(), 0);

    whisper_kv_cache_free(cache);
    next.buffer = buffer.release();
    cache = std::move(next);
    return true;
}

static bool whisper_kv_cache_find_slot(
]=] content "${content}")
    string(REPLACE [=[
#endif

struct whisper_state * whisper_init_state(whisper_context * ctx) {
    whisper_state * state = new whisper_state;

    state->backends = whisper_backend_init(ctx->params);
    if (state->backends.empty()) {
        WHISPER_LOG_ERROR("%s: whisper_backend_init() failed\n", __func__);
        whisper_free_state(state);
        return nullptr;
    }

]=] [=[
#endif

struct whisper_state * whisper_init_state(whisper_context * ctx) {
    std::unique_ptr<whisper_state, decltype(&whisper_free_state)> state_owner(new whisper_state, whisper_free_state);
    whisper_state * state = state_owner.get();

    state->backends = whisper_backend_init(ctx->params);
    if (state->backends.empty()) {
        WHISPER_LOG_ERROR("%s: whisper_backend_init() failed\n", __func__);
        return nullptr;
    }

]=] content "${content}")
    string(REPLACE [=[
                ctx->model.hparams.n_text_layer,
                GGML_PAD(ctx->model.hparams.n_text_ctx, 256))) {
        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for self-attention cache\n", __func__);
        whisper_free_state(state);
        return nullptr;
    }

]=] [=[
                ctx->model.hparams.n_text_layer,
                GGML_PAD(ctx->model.hparams.n_text_ctx, 256))) {
        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for self-attention cache\n", __func__);
        return nullptr;
    }

]=] content "${content}")
    string(REPLACE [=[
                ctx->model.hparams.n_text_layer,
                GGML_PAD(ctx->model.hparams.n_audio_ctx, 256))) {
        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for cross-attention cache\n", __func__);
        whisper_free_state(state);
        return nullptr;
    }

]=] [=[
                ctx->model.hparams.n_text_layer,
                GGML_PAD(ctx->model.hparams.n_audio_ctx, 256))) {
        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for cross-attention cache\n", __func__);
        return nullptr;
    }

]=] content "${content}")
    string(REPLACE [=[
                1,
                GGML_PAD(ctx->model.hparams.n_audio_ctx, 256))) {
        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for self-attention cache\n", __func__);
        whisper_free_state(state);
        return nullptr;
    }

]=] [=[
                1,
                GGML_PAD(ctx->model.hparams.n_audio_ctx, 256))) {
        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for self-attention cache\n", __func__);
        return nullptr;
    }

]=] content "${content}")
    string(REPLACE [=[
    if (ctx->params.dtw_token_timestamps) {
        if (!aheads_masks_init(ctx->params, ctx->model.hparams, state->aheads_masks, state->backends[0])) {
            WHISPER_LOG_ERROR("%s: aheads_masks_init() failed for alignment heads masks\n", __func__);
            whisper_free_state(state);
            return nullptr;
        }
        const size_t memory_size = aheads_masks_nbytes(state->aheads_masks);
]=] [=[
    if (ctx->params.dtw_token_timestamps) {
        if (!aheads_masks_init(ctx->params, ctx->model.hparams, state->aheads_masks, state->backends[0])) {
            WHISPER_LOG_ERROR("%s: aheads_masks_init() failed for alignment heads masks\n", __func__);
            return nullptr;
        }
        const size_t memory_size = aheads_masks_nbytes(state->aheads_masks);
]=] content "${content}")
    string(REPLACE [=[
    if (!state->ctx_coreml) {
        WHISPER_LOG_ERROR("%s: failed to load Core ML model from '%s'\n", __func__, path_coreml.c_str());
#ifndef WHISPER_COREML_ALLOW_FALLBACK
        whisper_free_state(state);
        return nullptr;
#endif
    } else {
]=] [=[
    if (!state->ctx_coreml) {
        WHISPER_LOG_ERROR("%s: failed to load Core ML model from '%s'\n", __func__, path_coreml.c_str());
#ifndef WHISPER_COREML_ALLOW_FALLBACK
        return nullptr;
#endif
    } else {
]=] content "${content}")
    string(REPLACE [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init conv allocator\n", __func__);
            whisper_free_state(state);
            return nullptr;
        }

]=] [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init conv allocator\n", __func__);
            return nullptr;
        }

]=] content "${content}")
    string(REPLACE [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init encoder allocator\n", __func__);
            whisper_free_state(state);
            return nullptr;
        }

]=] [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init encoder allocator\n", __func__);
            return nullptr;
        }

]=] content "${content}")
    string(REPLACE [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init cross allocator\n", __func__);
            whisper_free_state(state);
            return nullptr;
        }

]=] [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init cross allocator\n", __func__);
            return nullptr;
        }

]=] content "${content}")
    string(REPLACE [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init decoder allocator\n", __func__);
            whisper_free_state(state);
            return nullptr;
        }

        WHISPER_LOG_INFO("%s: compute buffer (decode) = %7.2f MB\n", __func__, whisper_sched_size(state->sched_decode) / 1e6);
    }

    return state;
}

int whisper_ctx_init_openvino_encoder_with_state(
]=] [=[

        if (!ok) {
            WHISPER_LOG_ERROR("%s: failed to init decoder allocator\n", __func__);
            return nullptr;
        }

        WHISPER_LOG_INFO("%s: compute buffer (decode) = %7.2f MB\n", __func__, whisper_sched_size(state->sched_decode) / 1e6);
    }

    return state_owner.release();
}

int whisper_ctx_init_openvino_encoder_with_state(
]=] content "${content}")
    string(REPLACE [=[
}

struct whisper_context * whisper_init_from_file_with_params(const char * path_model, struct whisper_context_params params) {
    whisper_context * ctx = whisper_init_from_file_with_params_no_state(path_model, params);
    if (!ctx) {
        return nullptr;
    }

    ctx->state = whisper_init_state(ctx);
    if (!ctx->state) {
        whisper_free(ctx);
        return nullptr;
    }

    return ctx;
}

struct whisper_context * whisper_init_from_buffer_with_params(void * buffer, size_t buffer_size, struct whisper_context_params params) {
    whisper_context * ctx = whisper_init_from_buffer_with_params_no_state(buffer, buffer_size, params);
    if (!ctx) {
        return nullptr;
    }

    ctx->state = whisper_init_state(ctx);
    if (!ctx->state) {
        whisper_free(ctx);
        return nullptr;
    }

    return ctx;
}

struct whisper_context * whisper_init_with_params(struct whisper_model_loader * loader, struct whisper_context_params params) {
    whisper_context * ctx = whisper_init_with_params_no_state(loader, params);
    if (!ctx) {
        return nullptr;
    }

    ctx->state = whisper_init_state(ctx);
    if (!ctx->state) {
        whisper_free(ctx);
        return nullptr;
    }

    return ctx;
}

struct whisper_context * whisper_init_from_file(const char * path_model) {
]=] [=[
}

struct whisper_context * whisper_init_from_file_with_params(const char * path_model, struct whisper_context_params params) {
    std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_file_with_params_no_state(path_model, params), whisper_free);
    if (!ctx) {
        return nullptr;
    }

    ctx->state = whisper_init_state(ctx.get());
    if (!ctx->state) {
        return nullptr;
    }

    return ctx.release();
}

struct whisper_context * whisper_init_from_buffer_with_params(void * buffer, size_t buffer_size, struct whisper_context_params params) {
    std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_buffer_with_params_no_state(buffer, buffer_size, params), whisper_free);
    if (!ctx) {
        return nullptr;
    }

    ctx->state = whisper_init_state(ctx.get());
    if (!ctx->state) {
        return nullptr;
    }

    return ctx.release();
}

struct whisper_context * whisper_init_with_params(struct whisper_model_loader * loader, struct whisper_context_params params) {
    std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_with_params_no_state(loader, params), whisper_free);
    if (!ctx) {
        return nullptr;
    }

    ctx->state = whisper_init_state(ctx.get());
    if (!ctx->state) {
        return nullptr;
    }

    return ctx.release();
}

struct whisper_context * whisper_init_from_file(const char * path_model) {
]=] content "${content}")
    string(REPLACE [=[
                if (state->kv_self_n_dec < n_decoders_cur) {
                    WHISPER_LOG_DEBUG("%s: recreating KV cache: n_decoders_cur = %d\n", __func__, n_decoders_cur);

                    whisper_kv_cache_free(state->kv_self);

                    // overallocate to workaround KV cache fragmentation issues
                    const int factor = n_decoders_cur > 1 ? n_decoders_cur + 2 : 1;

]=] [=[
                if (state->kv_self_n_dec < n_decoders_cur) {
                    WHISPER_LOG_DEBUG("%s: recreating KV cache: n_decoders_cur = %d\n", __func__, n_decoders_cur);

                    // overallocate to workaround KV cache fragmentation issues
                    const int factor = n_decoders_cur > 1 ? n_decoders_cur + 2 : 1;

]=] content "${content}")
    string(REPLACE [=[
                                ctx->model.hparams.n_text_layer,
                                GGML_PAD(ctx->model.hparams.n_text_ctx, 256)*factor)) {
                        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for self-attention cache\n", __func__);
                        whisper_free_state(state);
                        return -7;
                    }

]=] [=[
                                ctx->model.hparams.n_text_layer,
                                GGML_PAD(ctx->model.hparams.n_text_ctx, 256)*factor)) {
                        WHISPER_LOG_ERROR("%s: whisper_kv_cache_init() failed for self-attention cache\n", __func__);
                        return -7;
                    }

]=] content "${content}")

    string(SHA256 overlay_sha256 "${content}")
    if(NOT overlay_sha256 STREQUAL "8cc010e36be180b2843287562cd69104a2b1f11e4d7a95827fffce7db70ed64b")
        message(FATAL_ERROR "The Focal lifetime overlay does not match the reviewed postimage.")
    endif()
    file(MAKE_DIRECTORY "${whisper_binary_dir}/focal-lifetime")
    file(WRITE "${overlay}" "${content}")
    set_property(TARGET whisper PROPERTY SOURCES "${overlay_sources}")
    message(STATUS "Focal lifetime overlay: ${overlay_sha256}")
endfunction()
