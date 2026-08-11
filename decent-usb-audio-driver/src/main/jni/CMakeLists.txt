cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# ── libFLAC (reuse xiph/flac source from decoder-flac module) ──
set(FLAC_MODULE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../decent-media3-decoder-flac/src/main/jni")
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
add_subdirectory("${FLAC_MODULE_DIR}/libflac"
                 "${CMAKE_CURRENT_BINARY_DIR}/libflac"
                 EXCLUDE_FROM_ALL)

# ── libopus (xiph/opus source, downloaded by setup.sh) ──
#
# Path C native Opus decoder for bit-perfect OGG/Opus playback via USB DAC.
# libopus is built as a static library and linked into libdecent_usb_audio.so.
# Build requires setup.sh to have been run (downloads libopus source to
# src/main/jni/libopus/). If libopus is not present, the Opus JNI symbols
# will be missing from the .so -- Kotlin side detects this at runtime via
# UnsatisfiedLinkError and falls back to the FFmpeg pipeline.
set(OPUS_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libopus")

if(EXISTS "${OPUS_SRC_DIR}/CMakeLists.txt")
    message(STATUS "libopus source found at ${OPUS_SRC_DIR} -- building Opus native decoder")
    set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
    set(OPUS_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(OPUS_INSTALL_DOCS OFF CACHE BOOL "" FORCE)
    set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
    add_subdirectory("${OPUS_SRC_DIR}"
                     "${CMAKE_CURRENT_BINARY_DIR}/libopus"
                     EXCLUDE_FROM_ALL)
    set(OPUS_AVAILABLE TRUE)
else()
    message(WARNING "libopus source NOT found at ${OPUS_SRC_DIR} -- "
                    "Opus native decoder will be unavailable. "
                    "Run decent-usb-audio-driver/setup.sh to enable "
                    "bit-perfect Opus playback.")
    set(OPUS_AVAILABLE FALSE)
endif()

# ── Main library: USB audio driver + native audio engines ──
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
    native-audio-engine.cpp
    "${FLAC_MODULE_DIR}/flac_parser.cc"
)

# Conditionally add Opus engine source (only when libopus is available)
if(OPUS_AVAILABLE)
    target_sources(decent_usb_audio PRIVATE native-opus-engine.cpp)
    target_include_directories(decent_usb_audio PRIVATE "${OPUS_SRC_DIR}/include")
    target_compile_definitions(decent_usb_audio PRIVATE OPUS_NATIVE_AVAILABLE=1)
endif()

target_include_directories(decent_usb_audio PRIVATE
    "${FLAC_MODULE_DIR}/include"
    "${FLAC_MODULE_DIR}/libflac/include"
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio
    ${log-lib}
    FLAC
)

# Link libopus (static) only when available
if(OPUS_AVAILABLE)
    target_link_libraries(decent_usb_audio opus)
endif()
