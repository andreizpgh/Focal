# Upstream invokes project() twice; defer one research setup until targets exist.
include_guard(GLOBAL)
if(CMAKE_VERSION VERSION_LESS 3.19)
    message(FATAL_ERROR "The Focal beam research entry requires CMake 3.19 or newer.")
endif()
include("${CMAKE_CURRENT_LIST_DIR}/focal-whisper-beam.cmake")

function(focal_whisper_beam_research)
    focal_whisper_beam_overlay()
    get_target_property(source_dir whisper SOURCE_DIR)
    get_target_property(overlay_cpp whisper FOCAL_BEAM_SOURCE)
    get_target_property(baseline_cpp whisper FOCAL_BEAM_BASELINE_SOURCE)
    get_target_property(header_dir whisper FOCAL_BEAM_HEADER_DIRECTORY)
    get_target_property(engine_definitions whisper COMPILE_DEFINITIONS)
    foreach(kind IN ITEMS baseline candidate)
        if(kind STREQUAL baseline)
            set(target focal-whisper-beam-baseline-check)
            set(source "${baseline_cpp}")
        else()
            set(target focal-whisper-beam-check)
            set(source "${overlay_cpp}")
        endif()
        add_executable(${target}
            "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../scripts/whisper-beam-check.cpp")
        target_compile_features(${target} PRIVATE cxx_std_17)
        target_compile_definitions(${target} PRIVATE ${engine_definitions}
            "FOCAL_TEST_ENGINE_SOURCE=\"${source}\"")
        target_include_directories(${target} PRIVATE
            "${header_dir}" "${source_dir}" "${source_dir}/../include")
        # The harness includes the actual engine translation unit to call its
        # static sampler. Linking whisper too would duplicate its definitions.
        target_link_libraries(${target} PRIVATE ggml)
    endforeach()
endfunction()

cmake_language(DEFER CALL focal_whisper_beam_research)
