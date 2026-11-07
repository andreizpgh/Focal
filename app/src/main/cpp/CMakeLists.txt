cmake_minimum_required(VERSION 3.22.1)
project(focal_speech LANGUAGES CXX)

# Focal's JNI bridge over the sherpa-onnx C API. The prebuilt runtime libraries come from the
# pinned, SHA-256-verified sherpa-onnx AAR: Gradle's extractSpeechRuntime task unpacks them into
# FOCAL_SPEECH_RUNTIME_DIR/<abi>/ before CMake runs. The C API header is vendored unchanged from
# the same sherpa-onnx commit (third_party/sherpa-onnx, Apache-2.0).

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT FOCAL_SPEECH_RUNTIME_DIR)
  message(FATAL_ERROR "FOCAL_SPEECH_RUNTIME_DIR is not set. Build through Gradle, which extracts the runtime.")
endif()
set(focal_runtime "${FOCAL_SPEECH_RUNTIME_DIR}/${ANDROID_ABI}")

# Importing both libraries makes the Android Gradle plugin package them with the bridge.
foreach(library sherpa-onnx-c-api onnxruntime)
  if(NOT EXISTS "${focal_runtime}/lib${library}.so")
    message(FATAL_ERROR "Missing ${focal_runtime}/lib${library}.so. Run python3 scripts/fetch-model.py --runtime-only")
  endif()
  add_library(${library} SHARED IMPORTED)
  set_target_properties(${library} PROPERTIES IMPORTED_LOCATION "${focal_runtime}/lib${library}.so")
endforeach()

add_library(focal_speech SHARED focal_speech.cpp)
target_include_directories(focal_speech PRIVATE third_party)
target_compile_options(focal_speech PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
# Device tests' native crash (NativeCrashHook): debug builds only, never in a release library.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
  target_compile_definitions(focal_speech PRIVATE FOCAL_TEST_HOOKS=1)
endif()
target_link_libraries(focal_speech PRIVATE sherpa-onnx-c-api onnxruntime)
# 16 KB pages (Android 15+ devices); every undefined symbol must resolve against the runtime.
target_link_options(focal_speech PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--no-undefined")
