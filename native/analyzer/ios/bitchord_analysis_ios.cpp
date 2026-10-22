/*
 * Copyright (C) 2026 Kushagra Singh
 *
 * Part of BitChord, licensed under the GNU General Public License, version 3
 * or later. It links the analyzer ported from Orchard, which remains governed
 * by the GNU Affero General Public License, version 3 or later, as part of
 * that combination (see audio_analysis.h).
 */

// The iOS app's bridge to the whole-track DSP analyzer: what
// app/src/main/cpp/jni/analysis_jni.cpp is to Android and the desktop, as a
// plain C ABI instead of JNI.
//
// Built into BitChordAnalysis.framework (iosApp/project.yml) and bound at run
// time by iosKit's IosAnalyzerNative with dlopen/dlsym, the way FFmpeg is:
// Kotlin/Native cannot run cinterop for Apple targets on the Linux host that
// cross-compiles iosKit in CI. So nothing here is declared to Kotlin; the
// signatures below are restated there and must stay in step with it, and
// kAbiVersion is what tells a mismatch apart from a missing library.
//
// Only the DSP pass runs on iOS. The Beat This! beat model and the open-unmix
// vocal model need ONNX Runtime, which the iOS build does not have, so the
// tempo, beat grid and vocal mask are the analyzer's own autocorrelation and
// spectral heuristics -- the Phase 1 numbers the policy already degrades on.

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include "../analysis_json.h"
#include "../audio_analysis.h"

#define BITCHORD_EXPORT extern "C" __attribute__((visibility("default"), used))

namespace {

/** Bumped whenever a signature below changes; IosAnalyzerNative refuses any other. */
constexpr int32_t kAbiVersion = 1;

/** The rate the analyzer's window and hop constants assume (TrackFeatures.sampleRate). */
constexpr double kSampleRate = 11025.0;

}  // namespace

BITCHORD_EXPORT int32_t bitchord_analysis_abi_version(void) { return kAbiVersion; }

BITCHORD_EXPORT double bitchord_analysis_sample_rate(void) { return kSampleRate; }

/**
 * Analyses [count] mono float samples at [sample_rate] covering [duration]
 * seconds, and returns the result as a NUL-terminated JSON string the caller
 * releases with bitchord_analysis_free -- or null when the input is unusable
 * or memory ran out. Never throws: an exception must not unwind into Kotlin.
 *
 * Synchronous, O(n) and allocating, so it belongs on a worker thread.
 */
BITCHORD_EXPORT char* bitchord_analysis_run(
    const float* samples,
    int64_t count,
    double sample_rate,
    double duration) {
  if (samples == nullptr || count <= 0) return nullptr;
  try {
    const std::vector<float> input(samples, samples + count);
    const bitchord::smart::AnalysisResult result =
        bitchord::smart::AnalyzeAudio(input, sample_rate, duration);
    const std::string json = bitchord::smart::json::ToJson(result);
    char* out = static_cast<char*>(std::malloc(json.size() + 1));
    if (out == nullptr) return nullptr;
    std::memcpy(out, json.c_str(), json.size() + 1);
    return out;
  } catch (...) {
    return nullptr;
  }
}

/** Releases a string bitchord_analysis_run returned; null is fine. */
BITCHORD_EXPORT void bitchord_analysis_free(char* json) { std::free(json); }
