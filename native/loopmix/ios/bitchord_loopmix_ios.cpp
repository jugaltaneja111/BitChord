// BitChord: the iOS app's bridge to the Automix loop mix renderer
// (native/loopmix/): what app/src/main/cpp/jni/loopmix_jni.cpp is to Android,
// as a plain C ABI instead of JNI.
//
// Built into BitChordLoopMix.framework (iosApp/project.yml) and bound at run
// time by iosKit's IosLoopMixNative with dlopen/dlsym, as the analyzer is:
// Kotlin/Native cannot run cinterop for Apple targets on the Linux host that
// cross-compiles iosKit. The signatures below are restated there and must stay
// in step with it; kAbiVersion tells a mismatch apart from a missing library.
//
// Every call but blm_render, blm_heard and blm_fade_out is made while the mix
// is set up. blm_render runs on the loop mix's feeder thread, one block at a
// time and in order; blm_heard and blm_fade_out may come from any thread.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#include <algorithm>
#include <cstdint>
#include <new>
#include <vector>

#include "loopmix/loop_mix_renderer.h"

#define BITCHORD_EXPORT extern "C" __attribute__((visibility("default"), used))

using bitchord::loopmix::Deck;
using bitchord::loopmix::Envelope;
using bitchord::loopmix::Renderer;

namespace {

/** Bumped whenever a signature below changes; IosLoopMixNative refuses any other. */
constexpr int32_t kAbiVersion = 1;

/** The renderer and the scratch block blm_render_planar renders into. */
struct LoopMixHandle {
  explicit LoopMixHandle(int sampleRate) : renderer(sampleRate) {}
  Renderer renderer;
  std::vector<float> interleaved;
};

LoopMixHandle* From(void* handle) { return static_cast<LoopMixHandle*>(handle); }

Deck* DeckOf(void* handle, int32_t index) {
  if (handle == nullptr || index < 0 || index > 1) return nullptr;
  return &From(handle)->renderer.deck(index);
}

}  // namespace

BITCHORD_EXPORT int32_t blm_abi(void) { return kAbiVersion; }

BITCHORD_EXPORT void* blm_create(int32_t sample_rate) {
  if (sample_rate <= 0) return nullptr;
  return new (std::nothrow) LoopMixHandle(sample_rate);
}

BITCHORD_EXPORT void blm_destroy(void* handle) { delete From(handle); }

/** One deck: its region (planar, at the renderer's rate), loop, beat knots and stop. Copied. */
BITCHORD_EXPORT void blm_set_deck(void* handle, int32_t index, const float* left, const float* right,
                                  int64_t frames, double region_start, int64_t loop_start, int64_t loop_end,
                                  double release_at, const double* knot_frames, const double* knot_positions,
                                  int32_t knots, int32_t run_on, double stop_at, double preroll, float level) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr || left == nullptr || right == nullptr || frames < 0) return;
  deck->left.assign(left, left + frames);
  deck->right.assign(right, right + frames);
  deck->regionStart = region_start;
  deck->loopStart = loop_start;
  deck->loopEnd = loop_end;
  deck->releaseAt = release_at;
  if (knots > 0 && knot_frames != nullptr && knot_positions != nullptr) {
    deck->knotTimes.assign(knot_frames, knot_frames + knots);
    deck->knotPositions.assign(knot_positions, knot_positions + knots);
  } else {
    deck->knotTimes.clear();
    deck->knotPositions.clear();
  }
  deck->runOn = run_on != 0;
  deck->stopAt = stop_at;
  deck->preroll = preroll;
  deck->level = level;
}

/** Automation over mix seconds: kind 0 gain (linear), 1 the 250 Hz shelf in dB, 2 the high-pass in Hz. */
BITCHORD_EXPORT void blm_set_envelope(void* handle, int32_t index, int32_t kind, const double* times,
                                      const float* values, int32_t points, int32_t exponential) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  Envelope* envelope = kind == 0 ? &deck->gain : kind == 1 ? &deck->lowDb : kind == 2 ? &deck->highPassHz : nullptr;
  if (envelope == nullptr) return;
  if (points > 0 && times != nullptr && values != nullptr) {
    envelope->times.assign(times, times + points);
    envelope->values.assign(values, values + points);
  } else {
    envelope->times.clear();
    envelope->values.clear();
  }
  envelope->exponential = exponential != 0;
}

BITCHORD_EXPORT void blm_prepare(void* handle) {
  if (handle != nullptr) From(handle)->renderer.prepare();
}

BITCHORD_EXPORT int64_t blm_first_frame(void* handle) {
  return handle != nullptr ? From(handle)->renderer.firstFrame() : 0;
}

/** Renders [frames] stereo frames from mix frame [frame], interleaved, into [interleaved]. */
BITCHORD_EXPORT void blm_render(void* handle, int64_t frame, float* interleaved, int32_t frames) {
  if (handle == nullptr || interleaved == nullptr || frames <= 0) return;
  From(handle)->renderer.render(frame, interleaved, frames);
}

/** As blm_render, into two planes: AVAudioPCMBuffer's own layout, so nothing is reshuffled in Kotlin. */
BITCHORD_EXPORT void blm_render_planar(void* handle, int64_t frame, float* left, float* right, int32_t frames) {
  if (handle == nullptr || left == nullptr || right == nullptr || frames <= 0) return;
  LoopMixHandle* h = From(handle);
  if (h->interleaved.size() < size_t(frames) * 2) h->interleaved.resize(size_t(frames) * 2);
  float* block = h->interleaved.data();
  h->renderer.render(frame, block, frames);
  for (int32_t i = 0; i < frames; ++i) {
    left[i] = block[2 * i];
    right[i] = block[2 * i + 1];
  }
}

/** Fades deck [index] out over 25 ms once it plays track second [track_seconds] after its release; negative: never. */
BITCHORD_EXPORT void blm_fade_out(void* handle, int32_t index, double track_seconds) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  deck->fadeOutAt.store(track_seconds < 0 ? -1.0
                                          : deck->releasedPositionOf(track_seconds, From(handle)->renderer.sampleRate()));
}

/** Track seconds deck [index] played at the end of the last rendered block. */
BITCHORD_EXPORT double blm_heard(void* handle, int32_t index) {
  Deck* deck = DeckOf(handle, index);
  return deck != nullptr ? deck->heard.load() : 0.0;
}
