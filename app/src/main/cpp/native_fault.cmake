include_guard(GLOBAL)

function(focal_native_fault_replace variable before after)
    string(FIND "${${variable}}" "${before}" first)
    if(first LESS 0)
        message(FATAL_ERROR "Native fault source anchor is missing.")
    endif()
    string(LENGTH "${before}" length)
    math(EXPR tail_start "${first} + ${length}")
    string(SUBSTRING "${${variable}}" ${tail_start} -1 tail)
    string(FIND "${tail}" "${before}" second)
    if(NOT second EQUAL -1)
        message(FATAL_ERROR "Native fault source anchor is ambiguous.")
    endif()
    string(REPLACE "${before}" "${after}" result "${${variable}}")
    set(${variable} "${result}" PARENT_SCOPE)
endfunction()

# Called only after the whisper/focal targets and, for patched tests, the exact
# ownership overlay exist. The normal build does not include or call this file.
function(focal_native_fault_overlay)
    if(NOT FOCAL_NATIVE_FAULT_TESTS OR NOT FOCAL_ANDROID_DEBUG_BUILD
            OR NOT FOCAL_NATIVE_FAULT_VARIANT MATCHES "^(baseline|patched)$")
        message(FATAL_ERROR "Native fault hooks require an explicit debug-only test variant.")
    endif()
    if(NOT TARGET whisper OR NOT TARGET focal)
        message(FATAL_ERROR "Native fault hooks require source-built whisper and focal targets.")
    endif()
    get_target_property(applied focal FOCAL_NATIVE_FAULT_APPLIED)
    get_target_property(imported whisper IMPORTED)
    if(applied OR imported)
        message(FATAL_ERROR "Native fault overlay cannot be repeated or applied to imported libraries.")
    endif()
    get_target_property(whisper_source_dir whisper SOURCE_DIR)
    get_target_property(whisper_sources whisper SOURCES)
    get_target_property(focal_source_dir focal SOURCE_DIR)
    get_target_property(focal_sources focal SOURCES)
    set(output_dir "${CMAKE_CURRENT_BINARY_DIR}/native-fault")
    set(source_count 0)
    set(replaced_sources)
    foreach(source IN LISTS whisper_sources)
        get_filename_component(name "${source}" NAME)
        if(name STREQUAL "whisper.cpp")
            math(EXPR source_count "${source_count} + 1")
            get_filename_component(original "${source}" ABSOLUTE BASE_DIR "${whisper_source_dir}")
            list(APPEND replaced_sources "${output_dir}/whisper.cpp")
        else()
            list(APPEND replaced_sources "${source}")
        endif()
    endforeach()
    if(NOT source_count EQUAL 1)
        message(FATAL_ERROR "Native fault overlay requires exactly one whisper.cpp source.")
    endif()
    file(READ "${original}" content)
    string(SHA256 source_sha "${content}")
    if(FOCAL_NATIVE_FAULT_VARIANT STREQUAL "baseline")
        set(expected "02566a39137a14e0798ec0d88e78bd626f21e7549bc27d1cb5e4a30b101a0f7e")
    else()
        set(expected "8cc010e36be180b2843287562cd69104a2b1f11e4d7a95827fffce7db70ed64b")
    endif()
    if(NOT source_sha STREQUAL expected)
        message(FATAL_ERROR "Native fault source is not the exact selected original/ownership postimage.")
    endif()
    focal_native_fault_replace(content "#include \"whisper.h\"" "#include \"whisper.h\"\n#include \"native_fault_hooks.h\"")
    focal_native_fault_replace(content [=[
        // if there is a very short audio segment left to process, we remove any past prompt since it tends
]=] [=[
        focal_native_fault::encoded(ctx, state);

        // if there is a very short audio segment left to process, we remove any past prompt since it tends
]=])
    focal_native_fault_replace(content [=[
                if (state->kv_self_n_dec < n_decoders_cur) {
]=] [=[
                if (state->kv_self_n_dec < n_decoders_cur) {
                    focal_native_fault::GrowthScope fault_growth(ctx, state, state->kv_self_n_dec, n_decoders_cur);
]=])
    if(FOCAL_NATIVE_FAULT_VARIANT STREQUAL "baseline")
        focal_native_fault_replace(content
            "cache.buffer = ggml_backend_alloc_ctx_tensors(ctx, backend);"
            "cache.buffer = focal_native_fault::allocate_kv(ctx, backend);")
        set(expected_post "71f391ac47b92aa9dc3e1e1f949b5f136e881c1252a5c57e408ae7bedbb26221")
    else()
        focal_native_fault_replace(content
            "ggml_backend_alloc_ctx_tensors(ctx.get(), backend), ggml_backend_buffer_free);"
            "focal_native_fault::allocate_kv(ctx.get(), backend), ggml_backend_buffer_free);")
        set(expected_post "b3b3df810fd13e5a23821b3369fe3eed9655a71a9cffd060898950667f9e8cf0")
    endif()
    string(SHA256 post_sha "${content}")
    if(NOT post_sha STREQUAL expected_post)
        message(FATAL_ERROR "Native fault engine transform differs from its reviewed postimage.")
    endif()

    set(jni_count 0)
    set(replaced_focal_sources)
    foreach(source IN LISTS focal_sources)
        get_filename_component(name "${source}" NAME)
        if(name STREQUAL "whisper_jni.cpp")
            math(EXPR jni_count "${jni_count} + 1")
            get_filename_component(jni_original "${source}" ABSOLUTE BASE_DIR "${focal_source_dir}")
            list(APPEND replaced_focal_sources "${output_dir}/whisper_jni.cpp")
        else()
            list(APPEND replaced_focal_sources "${source}")
        endif()
    endforeach()
    if(NOT jni_count EQUAL 1)
        message(FATAL_ERROR "Native fault overlay requires exactly one whisper_jni.cpp source.")
    endif()
    file(READ "${jni_original}" jni)
    string(SHA256 jni_sha "${jni}")
    if(NOT jni_sha STREQUAL "9c2542b62ca58a7969ea171fc987c1b0724e9a737d0160e873c847ec882693aa")
        message(FATAL_ERROR "Native fault JNI source has changed; review the test transform again.")
    endif()
    focal_native_fault_replace(jni "#include <jni.h>" "#include <jni.h>\n#include \"native_fault_hooks.h\"")
    focal_native_fault_replace(jni "    ~Engine() { whisper_free(ctx); }" [=[
    ~Engine() { whisper_free(ctx); focal_native_fault::destroyed(faultToken); }
    std::uint64_t faultToken = 0;
]=])
    focal_native_fault_replace(jni [=[
    const int result = whisper_full(engine->ctx, p, audio.data(), static_cast<int>(audio.size()));
]=] [=[
    focal_native_fault::DecodeScope fault_decode(handle, engine->ctx, engine->faultToken);
    const int result = whisper_full(engine->ctx, p, audio.data(), static_cast<int>(audio.size()));
    fault_decode.returned(result);
]=])
    string(SHA256 jni_post_sha "${jni}")
    if(NOT jni_post_sha STREQUAL "f685f5a247f01666a35420f945db1483f1b35ed2f27c2d9cc05eac3c3d1291de")
        message(FATAL_ERROR "Native fault JNI transform differs from its reviewed postimage.")
    endif()

    file(MAKE_DIRECTORY "${output_dir}")
    file(WRITE "${output_dir}/whisper.cpp" "${content}")
    file(WRITE "${output_dir}/whisper_jni.cpp" "${jni}")
    set_property(TARGET whisper PROPERTY SOURCES "${replaced_sources}")
    set_property(TARGET focal PROPERTY SOURCES "${replaced_focal_sources}")
    set_property(TARGET focal PROPERTY FOCAL_NATIVE_FAULT_APPLIED TRUE)
    target_sources(focal PRIVATE "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/native_fault_hooks.cpp")
    target_include_directories(whisper PRIVATE "${CMAKE_CURRENT_FUNCTION_LIST_DIR}")
    target_include_directories(focal PRIVATE "${CMAKE_CURRENT_FUNCTION_LIST_DIR}" "${whisper_source_dir}/../ggml/include")
    target_compile_definitions(focal PRIVATE FOCAL_NATIVE_FAULT_SOURCE_SHA="${source_sha}")
    message(STATUS "Focal native fault test: ${FOCAL_NATIVE_FAULT_VARIANT}, engine=${post_sha}, jni=${jni_post_sha}")
endfunction()
