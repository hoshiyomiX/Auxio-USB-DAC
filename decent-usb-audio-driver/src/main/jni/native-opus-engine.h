/**
 * @file native-opus-engine.h
 * @brief Native Ogg/Opus decode -> USB audio engine.
 *
 * Mirrors native-audio-engine.h (the FLAC engine) for Opus files.
 * The NativeOpusEngine struct is opaque to Kotlin -- controlled via JNI.
 *
 * Pipeline (single native thread, zero JNI in hot path):
 *   Ogg page parser -> Opus packet -> opus_decode() -> int16 PCM
 *   -> padInt16ToInt32() (if DAC is 32-bit) -> submitPcmToUrbs()
 *
 * Ogg demuxing is implemented inline (no libogg dependency) -- a minimal
 * sequential Ogg page parser sufficient for bit-perfect playback. Seek
 * uses bisection on file offset + granule_position from page headers.
 */

#ifndef NATIVE_OPUS_ENGINE_H
#define NATIVE_OPUS_ENGINE_H

#include <cstdint>
#include <atomic>

// Forward declarations
struct UsbAudioContext;

#endif  // NATIVE_OPUS_ENGINE_H
