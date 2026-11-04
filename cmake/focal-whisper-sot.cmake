include_guard(GLOBAL)

# Research only: apply the reviewed SOT fix to a build-directory copy of the
# pinned engine. Call after add_subdirectory has created the whisper target.
function(focal_whisper_sot_overlay)
    if(NOT TARGET whisper)
        message(FATAL_ERROR "The Focal SOT overlay requires the whisper target.")
    endif()
    get_target_property(whisper_imported whisper IMPORTED)
    if(whisper_imported)
        message(FATAL_ERROR "The Focal SOT overlay requires a source-built whisper target.")
    endif()

    get_target_property(whisper_source_dir whisper SOURCE_DIR)
    get_target_property(whisper_binary_dir whisper BINARY_DIR)
    get_target_property(whisper_sources whisper SOURCES)
    set(original "${whisper_source_dir}/whisper.cpp")
    set(overlay "${whisper_binary_dir}/focal-sot/whisper.cpp")
    set(original_count 0)
    set(overlay_sources)
    foreach(source IN LISTS whisper_sources)
        get_filename_component(source_absolute "${source}" ABSOLUTE
            BASE_DIR "${whisper_source_dir}")
        if(source_absolute STREQUAL original)
            math(EXPR original_count "${original_count} + 1")
            list(APPEND overlay_sources "${overlay}")
        else()
            list(APPEND overlay_sources "${source}")
        endif()
    endforeach()
    if(NOT original_count EQUAL 1)
        message(FATAL_ERROR "The Focal SOT overlay requires exactly one original whisper.cpp source.")
    endif()

    file(READ "${original}" content)
    string(REPLACE "\r\n" "\n" content "${content}")
    string(SHA256 original_sha256 "${content}")
    if(NOT original_sha256 STREQUAL "02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e")
        message(FATAL_ERROR "The Focal SOT overlay requires the pinned whisper.cpp preimage (4979e04f5dcaccb36057e059bbaed8a2f5288315).")
    endif()

    string(REPLACE [=[
                // init new transcription with sot, language (opt) and task tokens
                prompt.insert(prompt.end(), prompt_init.begin(), prompt_init.end());
]=] [=[
                // Locate SOT after any trusted text context, before language/task tokens.
                const size_t sot_index = prompt.size();
                // init new transcription with sot, language (opt) and task tokens
                prompt.insert(prompt.end(), prompt_init.begin(), prompt_init.end());
]=] content "${content}")
    string(REPLACE [=[
                whisper_batch_prep_legacy(state->batch, prompt.data(), prompt.size(), 0, 0);
]=] [=[
                whisper_batch_prep_legacy(state->batch, prompt.data(), prompt.size(), 0, 0);
                state->batch.logits[sot_index] = 1;
]=] content "${content}")
    string(REPLACE [=[
                    whisper_compute_logprobs(state->logits, n_logits, logprobs);
                    whisper_compute_probs(state->logits, n_logits, logprobs, probs);
]=] [=[
                    // Keep next-token sampling on the final prompt row; no-speech uses SOT.
                    const auto sot_begin = state->logits.begin() + sot_index * n_logits;
                    const std::vector<float> sot_logits(sot_begin, sot_begin + n_logits);
                    whisper_compute_logprobs(sot_logits, n_logits, logprobs);
                    whisper_compute_probs(sot_logits, n_logits, logprobs, probs);
]=] content "${content}")

    file(MAKE_DIRECTORY "${whisper_binary_dir}/focal-sot")
    file(WRITE "${overlay}" "${content}")
    file(SHA256 "${overlay}" overlay_sha256)
    if(NOT overlay_sha256 STREQUAL "fb5bd21a37f4378c2b2f6b3ac053f8d142211781530765d3cb1fa49c17caec03")
        message(FATAL_ERROR "The Focal SOT overlay does not match the reviewed postimage.")
    endif()
    set_property(TARGET whisper PROPERTY SOURCES "${overlay_sources}")
    message(STATUS "Focal research SOT overlay: ${overlay_sha256}")
endfunction()
