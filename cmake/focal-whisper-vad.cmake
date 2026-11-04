include_guard(GLOBAL)

# Research only: generate the reviewed VAD error/cancellation API from the pinned
# source. This overlay is deliberately separate from the SOT research overlay.
function(focal_whisper_vad_overlay)
    if(NOT TARGET whisper)
        message(FATAL_ERROR "The Focal VAD overlay requires the whisper target.")
    endif()
    get_target_property(whisper_imported whisper IMPORTED)
    if(whisper_imported)
        message(FATAL_ERROR "The Focal VAD overlay requires a source-built whisper target.")
    endif()

    get_target_property(whisper_source_dir whisper SOURCE_DIR)
    get_target_property(whisper_binary_dir whisper BINARY_DIR)
    get_target_property(whisper_sources whisper SOURCES)
    set(original_cpp "${whisper_source_dir}/whisper.cpp")
    get_filename_component(original_header "${whisper_source_dir}/../include/whisper.h" ABSOLUTE)
    set(overlay_dir "${whisper_binary_dir}/focal-vad")
    set(overlay_cpp "${overlay_dir}/whisper.cpp")
    set(overlay_header "${overlay_dir}/include/whisper.h")
    set(cpp_count 0)
    set(header_count 0)
    set(overlay_sources)
    foreach(source IN LISTS whisper_sources)
        get_filename_component(source_absolute "${source}" ABSOLUTE
            BASE_DIR "${whisper_source_dir}")
        if(source_absolute STREQUAL original_cpp)
            math(EXPR cpp_count "${cpp_count} + 1")
            list(APPEND overlay_sources "${overlay_cpp}")
        elseif(source_absolute STREQUAL original_header)
            math(EXPR header_count "${header_count} + 1")
            list(APPEND overlay_sources "${overlay_header}")
        else()
            list(APPEND overlay_sources "${source}")
        endif()
    endforeach()
    if(NOT cpp_count EQUAL 1 OR NOT header_count EQUAL 1)
        message(FATAL_ERROR "The Focal VAD overlay requires exactly one original whisper.cpp and whisper.h source; it cannot be combined with another overlay, including SOT.")
    endif()

    file(READ "${original_cpp}" cpp)
    file(READ "${original_header}" header)
    string(REPLACE "\r\n" "\n" cpp "${cpp}")
    string(REPLACE "\r\n" "\n" header "${header}")
    string(SHA256 cpp_sha256 "${cpp}")
    string(SHA256 header_sha256 "${header}")
    if(NOT cpp_sha256 STREQUAL "02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e"
            OR NOT header_sha256 STREQUAL "0525b758571ab71c00ddd8ea2845f74af7c62dfa5100c5586655f75bdc5ef4be")
        message(FATAL_ERROR "The Focal VAD overlay requires both pinned preimages (4979e04f5dcaccb36057e059bbaed8a2f5288315).")
    endif()

    string(REPLACE [=[
    WHISPER_API int     whisper_vad_n_probs(struct whisper_vad_context * vctx);
]=] [=[
    // Synchronous, caller-serialized VAD with optional cooperative cancellation.
    // Callback data is borrowed only for this call. CPU backends also check the
    // callback between graph nodes; other backends check between input windows.
    // False means failure/cancellation and exposes no usable probabilities.
    WHISPER_API bool whisper_vad_detect_speech_with_abort(
            struct whisper_vad_context * vctx,
                           const float * samples,
                                   int   n_samples,
                   ggml_abort_callback   abort_callback,
                                  void * abort_callback_data);

    WHISPER_API int     whisper_vad_n_probs(struct whisper_vad_context * vctx);
]=] header "${header}")

    string(REPLACE [=[
bool whisper_vad_detect_speech(
        struct whisper_vad_context * vctx,
        const float * samples,
        int n_samples) {
]=] [=[
bool whisper_vad_detect_speech(
        struct whisper_vad_context * vctx,
        const float * samples,
        int n_samples) {
    return whisper_vad_detect_speech_with_abort(vctx, samples, n_samples, nullptr, nullptr);
}

bool whisper_vad_detect_speech_with_abort(
        struct whisper_vad_context * vctx,
        const float * samples,
        int n_samples,
        ggml_abort_callback abort_callback,
        void * abort_callback_data) {
    if (!vctx) {
        return false;
    }

    // Each VAD context owns its backends. Never leave borrowed callback data
    // installed after a normal return, cancellation, error, or C++ exception.
    struct call_cleanup {
        whisper_vad_context * vctx;
        bool complete;
        ~call_cleanup() {
            auto sched = vctx->sched.sched;
            for (int i = 0; i < ggml_backend_sched_get_n_backends(sched); ++i) {
                auto backend = ggml_backend_sched_get_backend(sched, i);
                auto dev = ggml_backend_get_device(backend);
                auto reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;
                auto set_abort = reg ? reinterpret_cast<ggml_backend_set_abort_callback_t>(
                    ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_abort_callback")) : nullptr;
                if (set_abort) {
                    set_abort(backend, nullptr, nullptr);
                }
            }
            ggml_backend_sched_reset(sched);
            if (!complete) {
                vctx->probs.clear();
            }
        }
    } cleanup {vctx, false};

    vctx->probs.clear();
    const auto should_abort = [&]() {
        return abort_callback && abort_callback(abort_callback_data);
    };
    if (!samples || n_samples <= 0 || should_abort()) {
        return false;
    }

    auto & sched = vctx->sched.sched;
    for (int i = 0; i < ggml_backend_sched_get_n_backends(sched); ++i) {
        auto backend = ggml_backend_sched_get_backend(sched, i);
        auto dev = ggml_backend_get_device(backend);
        auto reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;
        auto set_abort = reg ? reinterpret_cast<ggml_backend_set_abort_callback_t>(
            ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_abort_callback")) : nullptr;
        if (set_abort) {
            set_abort(backend, abort_callback, abort_callback_data);
        } else if (abort_callback && dev && ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) {
            WHISPER_LOG_ERROR("%s: CPU backend does not support cancellation\n", __func__);
            return false;
        }
    }
]=] cpp "${cpp}")

    string(REPLACE [=[
    auto & sched = vctx->sched.sched;

    ggml_cgraph * gf = whisper_vad_build_graph(*vctx);]=] [=[
    ggml_cgraph * gf = whisper_vad_build_graph(*vctx);]=] cpp "${cpp}")

    string(REPLACE [=[
    for (int i = 0; i < n_chunks; i++) {
        const int idx_start]=] [=[
    for (int i = 0; i < n_chunks; i++) {
        if (should_abort()) {
            return false;
        }
        const int idx_start]=] cpp "${cpp}")

    string(REPLACE [=[
            WHISPER_LOG_ERROR("%s: failed to compute VAD graph\n", __func__);
            break;
        }

        // Get the probability]=] [=[
            WHISPER_LOG_ERROR("%s: failed to compute VAD graph\n", __func__);
            return false;
        }
        if (should_abort()) {
            return false;
        }

        // Get the probability]=] cpp "${cpp}")

    string(REPLACE [=[
    WHISPER_LOG_INFO("%s: vad time = %.2f ms processing %d samples\n", __func__, 1e-3f * vctx->t_vad_us, n_samples);

    ggml_backend_sched_reset(sched);

    return true;]=] [=[
    WHISPER_LOG_INFO("%s: vad time = %.2f ms processing %d samples\n", __func__, 1e-3f * vctx->t_vad_us, n_samples);

    if (should_abort()) {
        return false;
    }
    cleanup.complete = true;
    return true;]=] cpp "${cpp}")

    file(MAKE_DIRECTORY "${overlay_dir}/include")
    file(WRITE "${overlay_cpp}" "${cpp}")
    file(WRITE "${overlay_header}" "${header}")
    file(SHA256 "${overlay_cpp}" cpp_sha256)
    file(SHA256 "${overlay_header}" header_sha256)
    if(NOT cpp_sha256 STREQUAL "b980b5d0425cd322c61d53b801bef2a14a4368f891a90f781f3a9855cf255454"
            OR NOT header_sha256 STREQUAL "001c3ef8fffd664d219089a549414983011719a535bcf07a3cc610e804b200ae")
        message(FATAL_ERROR "The Focal VAD overlay does not match both reviewed postimages.")
    endif()
    set_property(TARGET whisper PROPERTY SOURCES "${overlay_sources}")
    # Consumers and upstream examples must see the additive declaration too.
    # Keep the original source directory for whisper-arch.h and backend headers.
    target_include_directories(whisper BEFORE PUBLIC
        "$<BUILD_INTERFACE:${overlay_dir}/include>")
    message(STATUS "Focal research VAD overlay: cpp=${cpp_sha256}, header=${header_sha256}")
endfunction()
