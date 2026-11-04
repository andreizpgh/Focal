include_guard(GLOBAL)
include("${CMAKE_CURRENT_LIST_DIR}/focal-whisper-vad.cmake")

# Research only: compose the reviewed VAD API, SOT probability and beam sampler
# fixes. Existing standalone overlays keep rejecting arbitrary composition.
function(focal_whisper_beam_overlay)
    focal_whisper_vad_overlay()
    get_target_property(whisper_source_dir whisper SOURCE_DIR)
    get_target_property(whisper_binary_dir whisper BINARY_DIR)
    get_target_property(whisper_sources whisper SOURCES)
    set(vad_cpp "${whisper_binary_dir}/focal-vad/whisper.cpp")
    set(vad_header "${whisper_binary_dir}/focal-vad/include/whisper.h")
    set(overlay_dir "${whisper_binary_dir}/focal-beam")
    set(baseline_cpp "${overlay_dir}/whisper-baseline.cpp")
    set(overlay_cpp "${overlay_dir}/whisper.cpp")
    set(cpp_count 0)
    set(header_count 0)
    set(overlay_sources)
    foreach(source IN LISTS whisper_sources)
        get_filename_component(source_absolute "${source}" ABSOLUTE
            BASE_DIR "${whisper_source_dir}")
        if(source_absolute STREQUAL vad_cpp)
            math(EXPR cpp_count "${cpp_count} + 1")
            list(APPEND overlay_sources "${overlay_cpp}")
        else()
            list(APPEND overlay_sources "${source}")
        endif()
        if(source_absolute STREQUAL vad_header)
            math(EXPR header_count "${header_count} + 1")
        endif()
    endforeach()
    if(NOT cpp_count EQUAL 1 OR NOT header_count EQUAL 1)
        message(FATAL_ERROR "The Focal beam overlay requires exactly one reviewed VAD source and header.")
    endif()

    file(SHA256 "${vad_cpp}" cpp_sha256)
    file(SHA256 "${vad_header}" header_sha256)
    if(NOT cpp_sha256 STREQUAL "b980b5d0425cd322c61d53b801bef2a14a4368f891a90f781f3a9855cf255454"
            OR NOT header_sha256 STREQUAL "001c3ef8fffd664d219089a549414983011719a535bcf07a3cc610e804b200ae")
        message(FATAL_ERROR "The Focal beam overlay requires both reviewed VAD preimages.")
    endif()
    file(READ "${vad_cpp}" cpp)
    string(REPLACE [=[
                // init new transcription with sot, language (opt) and task tokens
                prompt.insert(prompt.end(), prompt_init.begin(), prompt_init.end());
]=] [=[
                // Locate SOT after any trusted text context, before language/task tokens.
                const size_t sot_index = prompt.size();
                // init new transcription with sot, language (opt) and task tokens
                prompt.insert(prompt.end(), prompt_init.begin(), prompt_init.end());
]=] cpp "${cpp}")
    string(REPLACE [=[
                whisper_batch_prep_legacy(state->batch, prompt.data(), prompt.size(), 0, 0);
]=] [=[
                whisper_batch_prep_legacy(state->batch, prompt.data(), prompt.size(), 0, 0);
                state->batch.logits[sot_index] = 1;
]=] cpp "${cpp}")
    string(REPLACE [=[
                    whisper_compute_logprobs(state->logits, n_logits, logprobs);
                    whisper_compute_probs(state->logits, n_logits, logprobs, probs);
]=] [=[
                    // Keep next-token sampling on the final prompt row; no-speech uses SOT.
                    const auto sot_begin = state->logits.begin() + sot_index * n_logits;
                    const std::vector<float> sot_logits(sot_begin, sot_begin + n_logits);
                    whisper_compute_logprobs(sot_logits, n_logits, logprobs);
                    whisper_compute_probs(sot_logits, n_logits, logprobs, probs);
]=] cpp "${cpp}")
    file(MAKE_DIRECTORY "${overlay_dir}")
    file(WRITE "${baseline_cpp}" "${cpp}")
    file(SHA256 "${baseline_cpp}" baseline_sha256)
    if(NOT baseline_sha256 STREQUAL "7b18969834da47224050bae38a9b9724826c5bfd58c17829e21041aa5bb77cf2")
        message(FATAL_ERROR "The Focal beam baseline does not match the reviewed SOT and VAD postimage.")
    endif()

    string(REPLACE [=[
        std::partial_sort(
                logits_id.begin(),
                logits_id.begin() + k, logits_id.end(),
                [](const pair_type & a, const pair_type & b) {
            return a.first > b.first;
        });
]=] [=[
        std::partial_sort(
                logits_id.begin(),
                logits_id.begin() + k, logits_id.end(),
                [](const pair_type & a, const pair_type & b) {
            return a.first > b.first || (a.first == b.first && a.second < b.second);
        });
]=] cpp "${cpp}")
    string(REPLACE [=[
    std::discrete_distribution<> dist(probs.begin(), probs.end());

    for (int i = 0; i < k; ++i) {
        const auto id = dist(decoder.rng);
]=] [=[
    for (int i = 0; i < k; ++i) {
        const auto id = logits_id[i].second;
]=] cpp "${cpp}")
    file(WRITE "${overlay_cpp}" "${cpp}")
    file(SHA256 "${overlay_cpp}" cpp_sha256)
    if(NOT cpp_sha256 STREQUAL "14499a7ba84bf9e00f64db2e7c9a16d5faf0e5a0cca6356e9d81616af91c5a2f")
        message(FATAL_ERROR "The Focal beam overlay does not match the reviewed combined postimage.")
    endif()
    set_property(TARGET whisper PROPERTY SOURCES "${overlay_sources}")
    set_property(TARGET whisper PROPERTY FOCAL_BEAM_SOURCE "${overlay_cpp}")
    set_property(TARGET whisper PROPERTY FOCAL_BEAM_BASELINE_SOURCE "${baseline_cpp}")
    set_property(TARGET whisper PROPERTY FOCAL_BEAM_HEADER_DIRECTORY
        "${whisper_binary_dir}/focal-vad/include")
    message(STATUS "Focal research beam overlay: cpp=${cpp_sha256}, header=${header_sha256}")
endfunction()
