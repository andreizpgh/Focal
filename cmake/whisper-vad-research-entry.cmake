# Upstream invokes project() twice; defer one research setup until targets exist.
include_guard(GLOBAL)
if(CMAKE_VERSION VERSION_LESS 3.19)
    message(FATAL_ERROR "The Focal VAD research entry requires CMake 3.19 or newer.")
endif()
include("${CMAKE_CURRENT_LIST_DIR}/focal-whisper-vad.cmake")

function(focal_whisper_vad_research)
    focal_whisper_vad_overlay()
    add_executable(focal-vad-api-check
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../scripts/whisper-vad-api-check.cpp")
    target_compile_features(focal-vad-api-check PRIVATE cxx_std_17)
    target_link_libraries(focal-vad-api-check PRIVATE whisper)
endfunction()

cmake_language(DEFER CALL focal_whisper_vad_research)
