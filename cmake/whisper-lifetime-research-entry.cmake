# The upstream project() hook runs twice; schedule the optional overlay once.
include_guard(GLOBAL)
if(CMAKE_VERSION VERSION_LESS 3.19)
    message(FATAL_ERROR "The Focal lifetime research entry requires CMake 3.19 or newer.")
endif()
include("${CMAKE_CURRENT_LIST_DIR}/focal-whisper-lifetime.cmake")
cmake_language(DEFER CALL focal_whisper_lifetime_overlay)
