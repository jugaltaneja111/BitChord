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
// Also the two models' front ends (mel_spectrogram.cpp, vocal_spectrogram.cpp)
// and the resampler, which Android reaches through mel_jni.cpp and
// vocal_jni.cpp. The models themselves run in BitChordModels.framework
// (native/models/ios), on ONNX Runtime.

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include "../analysis_json.h"
#include "../audio_analysis.h"
#include "../mel_spectrogram.h"
#include "../resampler.h"
#include "../vocal_spectrogram.h"

#define BITCHORD_EXPORT extern "C" __attribute__((visibility("default"), used))

namespace {

/** Bumped whenever a signature below changes; IosAnalyzerNative refuses any other. */
constexpr int32_t kAbiVersion = 2;

/** The rate the analyzer's window and hop constants assume (TrackFeatures.sampleRate). */
constexpr double kSampleRate = 11025.0;

/** A malloc'd copy of [values] for the caller to bitchord_buffer_free, or null. */
float* Copy(const std::vector<float>& values) {
  if (values.empty()) return nullptr;
  float* out = static_cast<float*>(std::malloc(values.size() * sizeof(float)));
  if (out == nullptr) return nullptr;
  std::memcpy(out, values.data(), values.size() * sizeof(float));
  return out;
}

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

/**
 * The front ends' fixed shapes, which the models were trained on: [mels, mel
 * rate, mel hop, vocal bins, vocal rate, vocal hop, vocal FFT]. iosKit checks
 * them against its own constants before trusting either model's input.
 */
BITCHORD_EXPORT void bitchord_frontend_shape(double* out) {
  if (out == nullptr) return;
  out[0] = static_cast<double>(bitchord::smart::kBeatSpectrogramMels);
  out[1] = bitchord::smart::kBeatSpectrogramSampleRate;
  out[2] = static_cast<double>(bitchord::smart::kBeatSpectrogramHop);
  out[3] = static_cast<double>(bitchord::smart::kVocalSpectrogramBins);
  out[4] = bitchord::smart::kVocalSpectrogramSampleRate;
  out[5] = static_cast<double>(bitchord::smart::kVocalSpectrogramHop);
  out[6] = static_cast<double>(bitchord::smart::kVocalSpectrogramFft);
}

/**
 * Beat This!'s log-mel input for [count] mono samples at [sample_rate] (which
 * must be the front end's 22,050 Hz): frames x mels floats, frame-major, with
 * the frame count in [frames]. Null when the input is unusable.
 */
BITCHORD_EXPORT float* bitchord_mel_compute(
    const float* samples, int64_t count, double sample_rate, int64_t* frames) {
  if (frames != nullptr) *frames = 0;
  if (samples == nullptr || count <= 0 || frames == nullptr) return nullptr;
  try {
    const std::vector<float> input(samples, samples + count);
    const auto spectrogram = bitchord::smart::ComputeBeatSpectrogram(input, sample_rate);
    float* out = Copy(spectrogram.values);
    if (out != nullptr) *frames = static_cast<int64_t>(spectrogram.frames);
    return out;
  } catch (...) {
    return nullptr;
  }
}

/**
 * open-unmix's magnitude STFT for planar stereo at [sample_rate] (44,100 Hz):
 * [2][bins][frames], bin-major as the model's tensor is laid out.
 */
BITCHORD_EXPORT float* bitchord_vocal_compute(
    const float* left, const float* right, int64_t count, double sample_rate, int64_t* frames) {
  if (frames != nullptr) *frames = 0;
  if (left == nullptr || right == nullptr || count <= 0 || frames == nullptr) return nullptr;
  try {
    const std::vector<std::vector<float>> channels{
        std::vector<float>(left, left + count), std::vector<float>(right, right + count)};
    const auto spectrogram = bitchord::smart::ComputeVocalSpectrogram(channels, sample_rate);
    float* out = Copy(spectrogram.values);
    if (out != nullptr) *frames = static_cast<int64_t>(spectrogram.frames);
    return out;
  } catch (...) {
    return nullptr;
  }
}

/** Windowed-sinc conversion of [count] mono samples; the length in [out_count]. */
BITCHORD_EXPORT float* bitchord_resample(
    const float* samples, int64_t count, double input_rate, double output_rate, int64_t* out_count) {
  if (out_count != nullptr) *out_count = 0;
  if (samples == nullptr || count <= 0 || out_count == nullptr) return nullptr;
  try {
    const std::vector<float> input(samples, samples + count);
    const auto output = bitchord::smart::Resample(input, input_rate, output_rate);
    float* out = Copy(output);
    if (out != nullptr) *out_count = static_cast<int64_t>(output.size());
    return out;
  } catch (...) {
    return nullptr;
  }
}

/** Releases a buffer one of the functions above returned; null is fine. */
BITCHORD_EXPORT void bitchord_buffer_free(float* buffer) { std::free(buffer); }
