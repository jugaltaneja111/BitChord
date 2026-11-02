# Design: Automix loop mix and Mix View on iOS (v1.9 B6)

Status: Approved, pending implementation (Phase 1 first). Not yet reviewed (egm-review).
Branch: `ios-port`. Upstream reference: kushagrasinghx/bitchord at `b690dc14`
(`refs/upstream/main`), mainly `cd6da981` (loop mix on the phone), `0d442854`
(Mix View popup), `42650b7b` (tempo ease and late handoff fix), `9a212595` and
`24e29f1c` (the web player's loop mix fixes).

## The Problem

Automix on iOS today plans each transition with the shared planner and plays it
as a two-deck blend: a fade between the two songs, tempo ramped by AVFoundation's
time-stretch. The Android app, from v1.9, has two more things that iOS users do
not get:

1. **The loop mix.** When both songs have a clean instrumental stretch near the
   join, Android does not fade one song into the other. It loops the last
   instrumental bars of the outgoing song, brings in an instrumental loop from
   the start of the next song at the outgoing tempo with its bass cut, ramps one
   shared tempo from the old BPM to the new one beat by beat, swaps the bass,
   drains the old loop away through a rising high-pass filter, and then lets the
   new song play on from its loop. A DJ does the same thing. A plain blend
   cannot do it, because a blend has only as much overlap as the two songs have
   left. A loop mix makes its own overlap out of repeated bars, so two songs
   with no outro or intro still get a long, beat-locked change.
2. **Mix View.** While a blend is playing, Android shows "Mixing" under the
   seek bar, and tapping it opens a popup. The popup shows both songs'
   waveforms (lows, mids and highs in three colours), the frame where they
   overlap, the beat grid, the fader lines the blend rides, and a moving
   playhead. It tells the listener what Automix is doing, and it is the main way
   people find out that Automix is more than a crossfade.

On iOS three things are missing:

- **The audio path.** Most songs play on AVPlayer (YouTube's AAC). AVPlayer
  cannot loop a few bars sample-exactly or be spliced into at a chosen sample.
  The loop mix needs both songs' regions decoded to PCM and rendered by
  Signalsmith Stretch.
- **The analysis.** Android's loop mix checks that a loop has no singing by
  using two ML models (Beat This! for downbeats and open-unmix for a vocal
  mask), run through ONNX. The iOS analyzer has neither model, as
  `docs/IOS_PORT.md` notes ("no ONNX beat or vocal models"). It also does not
  produce the beat grids at each end of the song (`headBeats`, `tailBeats`) or
  the vocal and loudness windows (`vocalWindows`, `levelWindows`) that
  `LoopMix.plan` reads.
- **The data Mix View draws.** iOS publishes no `MixTransitionView`: no
  three-band waveforms, no fader lines, no blend progress.

## Overview

Bring both features to the iOS app (the Compose app on `ios-port`), behaving as
Android's v1.9 does, so that the same pair of songs makes the same kind of
transition on both phones wherever the iOS analysis allows it.

- **Mix View** is mostly shared UI. The popup (`MixViewPopup.kt`), the "Mixing"
  label that opens it, and the `MixTransitionView` model are shared code
  upstream. iOS supplies three things through `PlayerHost`: the blend in flight
  laid out as a `MixTransitionView`, the progress through it, and each song's
  three-band waveform for the blend's window. It works for every Automix
  transition, including ordinary blends, so it does not wait on the loop mix.
- **The loop mix** adds a third deck. It is the upstream C++ renderer
  (`native/loopmix`, Signalsmith Stretch), built for iOS the way the Automix
  analyzer already is: compiled in CI into an embedded framework and bound at run
  time with dlopen. It plays through the app's existing AVAudioEngine
  (`FfmpegAudioEngine`). The shared, pure `LoopMix` planner and clock move into
  `shared/commonMain`, as they are upstream. iOS hands over from the outgoing
  player to the renderer at the loop start, and from the renderer to the
  incoming player after the release. On AVPlayer this cannot be sample-exact, so
  it follows the web player's approach: short handovers of identical audio,
  aligned by measuring both clocks (`HANDOVER_SEC` = 25 ms upstream).
- **When the loop mix is not possible**, Automix plays the blend it plays today.
  This covers a song with no clean loop, analysis that cannot vouch for a loop
  being instrumental, a missing framework, Automix's basic mode, a music video,
  and Listen Together. Nothing gets worse for a song that does not qualify.

## Goals

1. **Same transitions as Android where the analysis allows.** For a pair of
   songs whose iOS analysis gives `LoopMix.plan` the same inputs as Android,
   iOS picks the same loops, the same loop lengths (4, 2 or 1 bars) and the same
   tempo ramp, because both run the same shared planner code.
2. **No sung word is looped.** A loop plays only when lyrics or a vocal mask
   vouch that it is instrumental, with the upstream thresholds (at most 3% sung
   frames, no sung run over 0.2 s, 0.3 s of clear time before a lyric line). This
   is the rule from upstream's `9a212595` ("Automix never loops a sung word").
   Repeating a word is the worst way a loop mix can fail, and it is worse than
   no loop mix at all.
3. **Beat-locked by ear.** Through the whole mix, the two decks' beats stay
   within 10 ms of each other, because both decks follow one beat clock inside a
   single renderer. Each handover between a player and the renderer lands
   within 15 ms of the identical audio, so it sounds like one stream: no
   doubled transient, no audible comb filtering.
4. **Mix View on every Automix transition.** "Mixing" opens the popup for
   blends and loop mixes alike. It shows waveforms, the overlap frame, the beat
   grid, the faders and a playhead that moves at the heard position, matching
   Android's layout.
5. **The player stays honest during a loop mix.** The seek bar, the lyrics, the
   lock screen and pause or resume all keep making sense while the outgoing
   song is looping, which is upstream's `24e29f1c` for the web. Pausing pauses
   the mix. Seeking or skipping cancels it and goes straight to the incoming
   song.
6. **No regressions.** Every existing Automix behaviour (basic and advanced
   blends, filters, echo, tempo ramps, crossfade, gapless play, skip silence,
   the version aligner, the mid-song quality upgrade) works as it does today
   whenever no loop mix is planned.
7. **Cheap enough to leave on.** A loop mix decodes at most two regions of
   about 20 s each, ahead of time, on a background thread. While the mix
   renders, its CPU cost is measured on an iPhone 12 or newer and kept under 15%
   of one core, so it does not add audible dropouts or drain the battery.

## Non-Goals

- **ML models on iOS in this design.** Whether to bring Beat This! and
  open-unmix to iOS (through ONNX Runtime's iOS build or Core ML) is a separate
  decision, raised in the Technical Plan. This design states what the loop mix
  can do with and without them, and does not require them.
- **A loop mix with a music video playing**, as upstream rules out in
  `9a212595`. The picture cannot loop with the sound, so music videos keep
  today's blend.
- **A loop mix in Listen Together**, where the guest's phone cannot render the
  host's mix in step.
- **New Automix settings.** Upstream adds no switch for the loop mix: it is part
  of advanced Automix. iOS follows that. The only new user-facing surface is
  the Mix View popup.
- **Mix View on the lock screen, widgets or CarPlay.**
- **The desktop app's or web player's versions** of either feature. Their code
  is only a reference.
- **Changing the shared planner's rules.** The thresholds in `LoopMix` and
  `TransitionPlanner` are ported as they are. Tuning them is upstream's job.

## Open Questions (Overview level)

1. Should Mix View ship first, in its own build, since it does not depend on the
   loop mix? Default: yes. It is separable, shared-UI-heavy and low-risk, and it
   gives a way to see what the loop mix is doing when that lands.
2. Is a loop mix that only lyrics can vouch for (no vocal mask on iOS) good
   enough to ship, or should the loop mix wait for the vocal model? This
   decision goes in the Technical Plan, with numbers for how many songs each
   option covers.

## Owner decisions so far

- **D1, the vocal check (option C).** iOS runs both of Android's models through
  ONNX Runtime: open-unmix for the vocal mask (`vocals_umxhq_int8.onnx`, 9.1 MB)
  and Beat This! for beats and downbeats (`beat_this_final0_q8.onnx`, 23.1 MB,
  upstream's current weights). The iOS analysis then gives `LoopMix.plan` the
  same inputs as Android's. The cost is about 50–60 MB of app size: the two
  models plus the ONNX Runtime library.

## Technical Plan

### 1. Shape of the change

```
                 shared/commonMain (pure, tested on JVM)
  ┌───────────────────────────────────────────────────────────────────┐
  │ LoopMix (plan + Clock)   TransitionPlanner   MixTransitionView     │
  │ BeatTracker / VocalTracker post-processing (peaks, grid, mask)     │
  │ MixWaveform bands (3-band envelope from PCM)                       │
  │ ModelRunner interface  ◄── JVM: ai.onnxruntime  /  iOS: C bridge   │
  └───────────────────────────────────────────────────────────────────┘
        ▲ analysis inputs                         ▲ plan, clock, view
        │                                         │
  iosKit/automix                            iosKit (playback)
  ┌──────────────────────────────┐   ┌──────────────────────────────────┐
  │ IosAutomixAnalysis           │   │ IosAudioEngine (Automix decks)   │
  │  + head/tail regions,        │   │  + IosLoopMix: plans, decodes,   │
  │    44.1 kHz stereo (FFmpeg)  │   │    feeds the renderer node,      │
  │  + IosModels (dlopen         │   │    runs the two handovers        │
  │    BitChordModels.framework) │   │  + mix view flows                │
  └──────────────────────────────┘   └──────────────────────────────────┘
        │ dlopen                                │ dlopen
  BitChordModels.framework            BitChordLoopMix.framework
  (C bridge + ONNX Runtime,           (native/loopmix renderer +
   built in CI)                        Signalsmith Stretch, built in CI)
```

The new native code is two frameworks. Both are built in the macOS CI job and
loaded with dlopen and dlsym, as `BitChordAnalysis.framework` and FFmpeg are
today, because Kotlin/Native cannot run cinterop for Apple targets on the Linux
host that compiles `iosKit`.

### 2. Analysis on iOS: models, regions and the new fields

**ONNX Runtime.**
- `BitChordModels.framework` is a dynamic framework built from a small C bridge
  (`native/models/ios/bitchord_models_ios.cpp`), statically linked against
  Microsoft's prebuilt `onnxruntime.xcframework` (the `onnxruntime-c` CocoaPods
  archive, version pinned in CI).
- The bridge exposes four C functions:
  - `bcm_open(path) -> session`;
  - `bcm_run(session, input, shape, rank, output, outputCapacity) -> outputLength`;
  - `bcm_close(session)`;
  - `bcm_abi()`.
- `iosKit` binds them in `IosModels.kt`, checks the ABI version as
  `IosAnalyzerNative` does, and implements the shared `ModelRunner` interface.
- Sessions are opened lazily, one per model, with all graph optimizations on and
  one intra-op thread, so that analysis never competes with playback for cores.

**Shared tracker code.**
- Upstream's `BeatTracker` and `VocalTracker` sit in `jvmSharedMain` because
  they call `ai.onnxruntime` directly.
- Their maths (input layout, peak picking, tempo from beats, grid confidence,
  re-striding to the model's fixed width) moves to `commonMain` behind a
  `ModelRunner` interface. It takes a float array and a shape, and returns a
  float array.
- The JVM keeps a `ModelRunner` built on `ai.onnxruntime`, so Android and
  desktop behave as before.
- The spectrogram front ends (`mel_spectrogram.cpp`, `vocal_spectrogram.cpp`,
  `resampler.cpp`) are already in `native/analyzer`. They get exported through
  the existing iOS analyzer bridge (`bitchord_analysis_ios.cpp`, ABI version
  bumped).

**Models.**
- Both `.onnx` files are bundled in the app (`iosApp/iosApp/Models/`), copied
  from `refs/upstream/main:app/src/main/assets/` in CI.
- The repo's own `app/src/main/assets/beat_this_int8.onnx` is the older 4.5 MB
  weights. iOS uses upstream's current `beat_this_final0_q8.onnx`, because
  that is what Android v1.9 plans with.

**Regions.**
- Today `IosAutomixAnalysis` decodes the whole song to 11 kHz mono for the DSP
  analyzer. That stays as it is.
- Two extra windows are decoded with FFmpeg as 44.1 kHz planar stereo:
  - the head, from 0 s to the end of the incoming loop search
    (`IN_SEARCH_SEC` = 45 s, plus the vocal model's ~22.8 s window rounding);
  - the tail, the last `OUT_SEARCH_SEC` = 60 s.
- Each window gets:
  - Beat This! beats and downbeats, giving `headBeats` and `tailBeats`
    (`BeatSide`);
  - an open-unmix vocal curve, giving `vocalWindows` (`CurveWindow`);
  - an RMS level curve, giving `levelWindows`.
- The shapes and thresholds follow upstream `TrackAnalyzer.analyze`, `region`,
  `seamBeats`, `levelCurve` and `vocalMask`.

**Storage and settings.**
- `IosAnalysisStore` stores the new fields with the rest.
- The analysis schema version is bumped, so songs analysed before this change
  are analysed again when next queued.
- The existing "analyse on Wi-Fi only" setting covers the extra decode.

**Failure.** If the models framework or a model file fails to load, the new
fields stay empty. Analysis carries on exactly as today, and `LoopMix.plan`
then refuses every pair because no loop is vouched for. Nothing else changes.

### 3. Planning

- `LoopMix` (planner and `Clock`) and the upstream `TrackAnalysis` additions are
  copied into `shared/commonMain`.
- The `TransitionPlanner` changes from `0d442854` and `cd6da981` are also
  ported, with upstream's tests.
- The iOS Automix controller (in `IosAudioEngine` / `IosPlayer`) asks for a loop
  plan for the current pair the way upstream's `loopPlanFor` does:
  - only in advanced Automix (upstream now always plans as advanced);
  - never when either song is a music video (`isVideoOrigin`), a local file the
    analysis can't read, or in Listen Together;
  - only when both songs' lyric spans are known: fetched through the existing
    lyrics providers, and an empty list when a song has none;
  - only when the loop starts at least `LOOP_SETUP_SECONDS` after the playhead,
    so the regions can be decoded first.
- The plan is kept per pair and per analysis, and planned again after a seek.
- When `plan` returns null, the existing blend plays. The reason goes into
  Stats for nerds' Automix line (`automixDetail`), as upstream does.

### 4. Playback: the renderer deck and two handovers

**The renderer.**
- `native/loopmix/loop_mix_renderer.{h,cpp}` is used unchanged.
- It gets a C bridge (`native/loopmix/ios/bitchord_loopmix_ios.cpp`): create,
  fill deck N, set knots and envelopes, prepare, render, read a deck's heard
  position, fade out a deck, destroy.
- The bridge is built into `BitChordLoopMix.framework` with Signalsmith Stretch
  (header-only; its Accelerate FFT backend on Apple).

**Driving it.**
- `IosLoopMix.kt` decodes the outgoing and incoming loop regions (44.1 kHz
  stereo, from the same FFmpeg region decoder as analysis), fills both decks
  from the plan and its `Clock`, and calls `prepare`.
- The renderer plays on a new `AVAudioPlayerNode` attached to
  `FfmpegAudioEngine`'s graph.
- A feeder thread renders 4096-frame blocks ahead of time into
  `AVAudioPCMBuffer`s and schedules them, the way the FFmpeg player feeds its own
  node. No Kotlin runs on the real-time audio thread.

**Handover 1, outgoing player → renderer, at the loop start (mix time 0).**
- Mix frame 0 is pinned to a host time `H0`. That is when the outgoing player
  will present track time `loopStart`:
  - for an AVPlayer deck, read off the item's timebase against the host clock;
  - for an FFmpeg deck, read off its player node's `lastRenderTime` and
    `playerTime(forNodeTime:)`.
- The renderer node is started with `play(at: AVAudioTime(hostTime: H0 + first
  frame))`, so its audio lines up sample-for-sample with what the player is
  playing.
- Over `HANDOVER_SEC` (25 ms) the player fades out and the renderer fades in.
  The audio is identical, so this sounds like one stream. After that the
  outgoing player is paused.

**Handover 2, renderer → incoming player, after the release.**
- The incoming player is loaded early (`STARTUP_LEAD`), muted, and prerolled.
- For AVPlayer it is started with `setRate(1, time: inStartTrack, atHostTime:
  H0 + inStartAt)` and `automaticallyWaitsToMinimizeStalling` off. That puts it
  exactly on the deck's own timeline.
- An FFmpeg incoming deck is scheduled at the same host time.
- `offsetNow` is measured as upstream does. If the error is over 5 ms before
  the handover, the player is re-aligned with a fresh `setRate(…atHostTime:)`.
- At `clock.elementAt` the renderer's incoming deck fades out and the player
  fades in over 25 ms. The renderer is then torn down, and the incoming player
  becomes the playing track as at the end of a blend.

**Pause, seek and skip.**
- Pause stops the renderer node and both players, and remembers the mix frame.
- Resume re-pins `H0` from the remembered frame, then restarts the node and
  re-aligns the incoming player.
- Seeking, skipping, a route change that rebuilds the engine, or a call
  interruption cancels the mix. It falls back to the incoming song at its
  handover position, or to the target of the seek.
- That is upstream `24e29f1c`'s behaviour for the web player.

**What the player shows.**
- While the outgoing song loops, its reported position is the renderer's
  `heard` value for that deck (track seconds, looped), sampled each tick. The
  seek bar, the lyrics and the lock screen therefore follow the loop.
- The track change shows when the new song becomes the main one, which is the
  same point a blend uses (upstream `42650b7b`'s late handoff).

### 5. Mix View

**Shared code.**
- `MixTransitionView` and `MixDeck` (upstream `TrackAnalysisState.kt`),
  `MixViewPopup.kt`, the "Mixing" label tap (`PlayerControls.kt`, via
  `MixViewRequest`) and the four strings are copied into shared code.
- Upstream keeps the popup in `jvmSharedMain`. It is moved to `commonMain`, with
  the same remap used for music videos (`jvmSharedMain` → `commonMain`).
- `PlayerHost` gains `smartMixView: StateFlow<MixTransitionView?>` and
  `smartMixProgress: StateFlow<Float>`. Both default to empty, so Android and
  desktop are unchanged until they implement them.

**iOS.**
- When a blend or loop mix is committed, `IosAudioEngine` builds the
  `MixTransitionView` from the plan: the window, beat grid, fader curves and
  both songs' titles and covers.
- It then decodes each song's window plus `MIX_VIEW_CONTEXT` (0.24 of the
  overlap either side), and computes three-band waveforms (lows, mids, highs)
  with the shared `MixWaveform` code (upstream's band split, moved to
  `commonMain`).
- It publishes the view without waveforms first and fills them in when the
  decode finishes, which is under 1 s for a 30 s window. The popup draws empty
  decks until then.
- Progress is the blend's progress, or for a loop mix the mix seconds over its
  length, sampled every 50 ms while the popup is open.

### 6. Risks and how they are handled

| Risk | Handling |
|---|---|
| A sung word is looped | Only loops cleared by lyrics or the mask (D1); thresholds as upstream; the reason is logged per pair; on-device check of 30 pairs. |
| ONNX Runtime adds launch time or memory | Sessions open lazily on the analysis queue, never at launch; closed after 60 s idle; peak memory measured on device. |
| AVPlayer's host-time start drifts | Measured with `offsetNow` before handover 2; re-aligned if over 5 ms; a 25 ms equal-power handover hides up to about 10 ms. |
| The engine is rebuilt mid-mix (route change, interruption) | The mix is cancelled and falls back to the incoming song: the same rule as a seek. |
| The CI build of a framework fails | The app runs without it; the loop mix and models are off and the blend plays; CI checks that both frameworks are embedded, like the analyzer check. |
| App size grows by about 55 MB | Accepted in D1. Measured in CI and written into the release notes. |

## Alternatives Considered

### A. Lyrics only, no models (option A at gate D1)
How it works: no ONNX Runtime. A loop qualifies only when the synced lyrics
show no line touching it.
Rejected by the owner at D1. Songs without synced lyrics would never get a loop
mix. Downbeats from the DSP tracker can be off by a beat, and a loop entered on
beat 3 sounds wrong even when the beats line up. Nothing in a lyrics-only check
catches that.

### B. Vocal model only (option B at gate D1)
How it works: ONNX Runtime plus open-unmix, with downbeats from the DSP tracker.
Rejected by the owner at D1. It saves about 23 MB but leaves the downbeat error
from A. It also gives the same pair different loops on iOS and Android, which
breaks Goal 1.

### C. Core ML instead of ONNX Runtime
How it works: convert both models to `.mlmodel` with coremltools and run them on
the Neural Engine.
Rejected for now. Each model would need converting again whenever upstream
updates its weights, and checking that the conversion matches ONNX's output.
The int8 ONNX graphs use quantized operators that coremltools does not convert
one-to-one. Running them on ONNX Runtime keeps one set of weights, and one set
of numbers, across Android, desktop and iOS. Core ML stays an option if
analysis turns out too slow on device.

### D. Download the models on first use instead of bundling them
How it works: fetch the 32 MB of weights from upstream's release assets the
first time Automix analyses a song, and keep them in Application Support.
Rejected. The IPA is sideloaded and installed once, so its size is not paid
again on every update. A download adds a network failure path to analysis, and
a second "on Wi-Fi only" rule for it. It also adds a cache that iOS may purge.
The owner accepted the app size at D1.

### E. Render the loop mix in Kotlin
How it works: port the renderer and Signalsmith Stretch to Kotlin/Native and
call it from the feeder thread.
Rejected. Signalsmith Stretch is about 1,000 lines of C++ templates on top of an
FFT library, and it is tuned for vector code. A Kotlin port would be slower,
would differ in sound from Android's, and would be a second copy to maintain.
The C++ builds for iOS with the same CI path the analyzer already uses.

### F. Render through an AVAudioSourceNode instead of a fed player node
How it works: call the renderer from the source node's real-time render block,
so there is no buffering ahead.
Rejected. The render block would run Kotlin on the real-time audio thread,
where Kotlin/Native's memory manager can pause it and cause dropouts. Feeding
an AVAudioPlayerNode from a normal thread is what the FFmpeg player already
does safely. A player node can also be started at an exact host time, which
handover 1 needs.

### G. Web-style handovers (measure clocks, no scheduled start)
How it works: start the renderer and the incoming player when the measured
clocks say so, and cover any error with the 25 ms handover, as the web player
does.
Rejected. AVPlayer's `setRate(_:time:atHostTime:)` and the player node's
`play(at:)` start both at an exact host time. That removes most of the error
the web player has to live with, and meets Goal 3's 15 ms with room to spare.

### H. Ship Mix View and the loop mix together
How it works: one build with both features.
Rejected. Mix View depends on nothing in the loop mix, is mostly shared UI, and
works for every blend. Shipping it first gets it to users sooner, and gives a
way to see what the loop mix is doing once it lands.

## Detailed Implementation

The work comes in six phases. Each phase compiles and ships on its own, with
the loop mix inactive until Phase 4. Every phase ends with the same checks:

```
timeout 1500 ./gradlew :shared:compileKotlinIosArm64 :sharedUi:compileKotlinIosArm64 \
  :iosKit:compileKotlinIosArm64 :shared:compileKotlinJvm :sharedUi:compileKotlinJvm \
  :shared:jvmTest --offline -q 2>&1 | grep -E "^e:|FAILED"
```

Then an IPA build, dispatched by hand:
`gh workflow run ios.yml --ref ios-port -f app=true -f smoke=false -f release=true`.
The Android app is not rebuilt here. Where a phase changes a shared file Android
uses, `:app:compileDebugKotlin` is run once to check that Android still
compiles. There is no simulator test: the phone is the test bed.

Upstream sources are taken from `refs/upstream/main` (`b690dc14`) with
`git show`. Files under upstream `jvmSharedMain` are moved to `commonMain` as
they are copied, with the same remap used for music videos.

### Phase 1: Mix View

| File | Change | Why |
|---|---|---|
| `shared/src/commonMain/kotlin/com/music/bitchord/data/settings/TrackAnalysisState.kt` | Modify: add `MixTransitionView` and `MixDeck` (and their beat, fader and waveform fields) as upstream `0d442854`. | The model the popup draws; shared so every platform can publish it. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixWaveform.kt` | Create: the pure part of upstream `app/.../smart/MixWaveform.kt` (band split, `normalize`, block envelopes) taking planar PCM. Not its Android `MediaDataSource` reader. | iOS and Android compute the same three bands. |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/MixWaveformTest.kt` | Create: a sine at 60 Hz lands in lows, 1 kHz in mids, 8 kHz in highs; normalisation peaks at 1. | Checks the band split without a phone. |
| `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt` | Create: upstream's popup, moved from `jvmSharedMain` to `commonMain`. Its imports (`toImageBitmap`, `forPixelAccess`, Coil) are checked against the iOS source set. | The popup itself. |
| `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt` | Modify: add `smartMixView` and `smartMixProgress` with empty defaults. | The hooks the popup reads. |
| `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt` | Modify: the "Mixing" label takes taps (no ripple) and sets `MixViewRequest.open`, as upstream. | How the popup is opened. |
| `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` | Modify: add `MixViewOverlay(hazeState = playerHaze)` among the overlays. | Where the popup is drawn. |
| `sharedUi/src/commonMain/composeResources/values/strings.xml` | Modify: add `mix_view_bars`, `mix_view_one_bar`, `mix_view_seconds`, `mix_view_bpm`. | The popup's labels. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/ffmpeg/FfmpegRegionDecoder.kt` | Create: decode track seconds [from, to) of a file or URL to planar float stereo at a given rate (FFmpeg seek, decode, swresample). Shares `FileSource` and `Av` with `FfmpegMonoDecoder`. | Mix View waveforms now; model regions and loop regions later. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosMixView.kt` | Create: builds a `MixTransitionView` from a committed blend (planner output, both songs' metadata), decodes both windows plus `MIX_VIEW_CONTEXT`, fills waveforms, and publishes the two flows. Progress is sampled every 50 ms only while the popup is open. | iOS's side of Mix View. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosAudioEngine.kt` | Modify: when a blend commits, call `IosMixView.begin(plan, out, in)`; at its end or on cancel, call `IosMixView.end()`. Expose blend progress 0..1. | Feeds Mix View from the blend in flight. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosPlayerHost.kt` | Modify: override `smartMixView` and `smartMixProgress` with `IosMixView`'s flows. | Wires the popup on iOS. |

On the phone: start an Automix blend, tap "Mixing", and check that both
waveforms draw within 1 s and the playhead crosses the frame as the blend
plays.

### Phase 2: the models on iOS

| File | Change | Why |
|---|---|---|
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/ModelRunner.kt` | Create: `interface ModelRunner { fun run(input: FloatArray, shape: LongArray): FloatArray?; fun close() }` and a `ModelRunners` registry holding a factory per model name. | Lets the tracker code be shared while each platform runs ONNX its own way. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt` | Create: moved from `jvmSharedMain`, taking a `ModelRunner` instead of an `OrtSession`; `pickPeaks`, `tempoFromBeats`, `gridConfidence` unchanged. Brought up to upstream's current version. | One beat tracker for every platform. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/VocalTracker.kt` | Create: as above for `VocalTracker` and `VocalSpectrogram`; the fixed-width re-stride uses a `FloatArray`, not a `FloatBuffer`. | One vocal tracker. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.kt` | Create: `expect` front end (`compute`, `resample`, constants). | The trackers' input on every platform. |
| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt`, `VocalTracker.kt`, `MelSpectrogram.kt` | Delete (moved), leaving `OrtModelRunner.kt` (new, `ai.onnxruntime`) and `MelSpectrogram.jvm.kt` (`actual`, the existing JNI). | Android and desktop keep their runtime and native front end. |
| `shared/src/iosMain/kotlin/com/music/bitchord/playback/smart/MelSpectrogram.ios.kt` | Create: `actual` that calls a function pointer `iosKit` installs at start. `shared` cannot dlopen the analyzer framework itself. | The front end on iOS. |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/BeatTrackerTest.kt` | Create: upstream's peak-picking and tempo tests, plus a fake `ModelRunner` returning a known logit train; checks that the grid comes out at the expected beats. | Tracker maths tested without a model. |
| `native/models/ios/bitchord_models_ios.cpp` | Create: the C bridge over ONNX Runtime's C API (`bcm_abi`, `bcm_open`, `bcm_run`, `bcm_close`): one input, one output, float32, one intra-op thread, all optimizations. | What iOS calls to run a model. |
| `native/analyzer/ios/bitchord_analysis_ios.cpp` | Modify: export the mel and vocal spectrograms and the resampler; bump the ABI version. | The front ends already in C++, reached from iOS. |
| `scripts/fetch-onnxruntime.sh` | Create: download the pinned `onnxruntime-c` pod archive, verify its SHA-256, and unpack `onnxruntime.xcframework` to `iosKit/onnxruntime/`. | The runtime, fetched as FFmpeg is. |
| `scripts/fetch-models.sh` | Create: `git show` both `.onnx` files from upstream (or download them from upstream's release assets in CI, where the ref is missing) into `iosApp/iosApp/Models/`, with SHA-256 checks. | The weights the app bundles. |
| `iosApp/project.yml` | Modify: add a `BitChordModels` framework target (the bridge; links `onnxruntime.xcframework`; C++17; `-O2`; hidden symbols) embedded in the app; add `Models/*.onnx` as resources. | Builds and ships the runtime and the weights. |
| `.github/workflows/ios.yml` | Modify: run both fetch scripts; compile-check both bridges on Linux, as is done for the analyzer bridge (the models bridge against ORT's C headers only); after the IPA build, check that both frameworks and both models are in the app and their symbols are exported; print the IPA size. | A missing piece fails CI, not the user's phone. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosModels.kt` | Create: dlopen `BitChordModels`, check the ABI, and provide `ModelRunner`s for `beat_this` and `vocals_umxhq` from the bundled files. Sessions open lazily on the analysis queue and close after 60 s idle. | iOS's runtime binding. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosAnalyzerNative.kt` | Modify: bind the new front-end exports and install them for `MelSpectrogram.ios.kt`. | Wires the front end. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt` | Modify: add `headBeats`, `tailBeats`, `vocalWindows`, `levelWindows`, `BeatSide` and `CurveWindow` as upstream. | The fields `LoopMix` reads. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosAutomixAnalysis.kt` | Modify: after the whole-song DSP pass, decode the head and tail windows with `FfmpegRegionDecoder` and run both trackers and the level curve on them, following upstream `TrackAnalyzer.analyze`, `region`, `seamBeats`, `beatSide`, `levelCurve`, `vocalMask` and `mergeMasks`. The model downbeats replace the DSP ones where they are confident, as upstream does. | Gives iOS the same analysis as Android. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosAnalysisStore.kt` | Modify: store and restore the new fields; bump the schema version so older analyses are redone. | Keeps the new analysis. |

On the phone: analyse 10 songs, and for each check the stats-for-nerds Automix
line. Model beats and the mask should be present. The time per song is
recorded, and must stay under 2× today's.

### Phase 3: planning, no sound yet

| File | Change | Why |
|---|---|---|
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt` | Create: upstream's planner and `Clock`, unchanged. | The shared plan. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` | Modify: upstream's changes from `0d442854` and `cd6da981`. | Keeps the planner level with Android. |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/LoopMixTest.kt`, `SpokenOrLiveTest.kt` | Create: upstream's tests (moved to `commonTest`). | The planner is tested here, not on the phone. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosLoopPlanner.kt` | Create: upstream `loopPlanFor`, `loopRefusalFor`, `noteLoopVerdict` and `describeLoop` for iOS. It asks for lyric spans of both songs through `IosLyrics`, keys the plan by pair and analysis, plans again after a seek, and refuses music videos, Listen Together and unreadable local files. | Decides per pair whether a loop mix happens. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosAudioEngine.kt` | Modify: ask `IosLoopPlanner` before committing a blend; in this phase only log and publish the verdict (`automixDetail`), then play the blend. | A dry run on real queues before any audio changes. |

On the phone: play 30 queued pairs and copy the log. Count the pairs that would
loop mix. For each loop chosen, check by ear at its timestamps that no word is
in it.

### Phase 4: the renderer and the handovers

| File | Change | Why |
|---|---|---|
| `native/loopmix/loop_mix_renderer.h`, `.cpp` | Create: copied from upstream unchanged. | The renderer. |
| `native/third_party/signalsmith-stretch/`, `native/third_party/signalsmith-linear/` | Create: copied from upstream with their licences and the README entry. | The renderer's time-stretch. |
| `native/loopmix/ios/bitchord_loopmix_ios.cpp` | Create: the C bridge (`blm_abi`, `blm_create`, `blm_set_region`, `blm_set_knots`, `blm_set_envelope`, `blm_set_deck`, `blm_prepare`, `blm_first_frame`, `blm_render`, `blm_heard`, `blm_fade_out`, `blm_destroy`), matching what upstream `loopmix_jni.cpp` exposes. | iOS's handle on the renderer. |
| `iosApp/project.yml` | Modify: add a `BitChordLoopMix` framework target (`-O2`, Accelerate linked, hidden symbols), embedded. | Builds the renderer. |
| `.github/workflows/ios.yml` | Modify: compile-check the loop mix bridge on Linux with g++; check that the framework is embedded after the IPA build. | As for the analyzer. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosLoopMixNative.kt` | Create: dlopen and dlsym bindings for the bridge, with an ABI check. | Kotlin side of the bridge. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosLoopMix.kt` | Create: the session, ported from upstream `LoopMixSession` and `LoopMixPort`:<br>• decode both regions;<br>• fill the decks from the plan and `Clock`;<br>• an `AVAudioPlayerNode` on `FfmpegAudioEngine`, fed 4096-frame buffers by a feeder thread;<br>• pin `H0`;<br>• handover 1 (`play(at:)`, 25 ms equal-power);<br>• the incoming player started with `setRate(_:time:atHostTime:)`;<br>• `offsetNow`, with re-alignment over 5 ms;<br>• handover 2, then teardown. | Plays the loop mix. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/ffmpeg/FfmpegAudioEngine.kt` | Modify: attach and detach an extra player node, and tell the loop mix when the engine is rebuilt (so it cancels). | The renderer's output path. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosAudioEngine.kt` | Modify: when a loop plan exists and the frameworks loaded, run `IosLoopMix` instead of the blend. Expose each deck's presented host time (AVPlayer timebase; FFmpeg node time). Add `setRate(atHostTime:)` start and mute control for the incoming deck. | Hooks the loop mix into Automix. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/automix/IosMixView.kt` | Modify: build the view for a loop mix (both loops, ramp, faders from the clock) as upstream's controller does. | Mix View shows loop mixes. |

On the phone: play 10 pairs that loop mix, with AirPods and the speaker, on
AVPlayer and FFmpeg decks. Pass marks: no audible double transient at either
handover; the logged handover error is 5 ms or less in 9 of 10 runs; beats stay
locked by ear through the ramp; CPU during a mix is under 15% of one core in
Xcode's gauge (or Instruments if available).

### Phase 5: the player during a loop mix

| File | Change | Why |
|---|---|---|
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosPlayer.kt` | Modify: while a loop mix runs, take the reported position from the renderer's `heard` value for the deck that is audible; pause, resume, seek and skip go to `IosLoopMix` (pause re-pins `H0`; seek and skip cancel to the incoming song at its handover position or the seek target); the track change is published at handover 2. | Goal 5. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosInterruptions.kt` | Modify: an interruption during a loop mix cancels it, as a seek does. | No half-mix after a call. |
| `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosLyrics.kt` | Modify: if the lyrics view reads position anywhere other than `IosPlayer`, point it at `IosPlayer`'s position so it follows the loop. | Lyrics stay in step. |

On the phone: during a loop mix, pause and resume (the mix carries on), seek
(it goes straight to the target), skip (it goes to the incoming song), take a
call (it cancels cleanly), and lock the phone (the lock screen follows).

### Phase 6: docs and the record

| File | Change | Why |
|---|---|---|
| `docs/IOS_PORT.md` | Modify: replace "no ONNX beat or vocal models … the phone's filters, echo and tempo ramps are not rendered" with what now runs; add a "Loop mix and Mix View (v1.9 B6)" section. | Keeps the port notes true. |
| `docs/designs/automix-loop-mix.md` | Create: this document, finalized, with implementation notes added after Phase 5. | The record. |
| `iosApp/iosApp/Localization/*/Localizable.strings` | Modify: any new `tr()` strings (refusal reasons are logs and need no translation). | Translations. |

## Non-functional Requirements

- **App size.** +50–60 MB, measured in CI and stated in the release notes (D1).
- **Analysis time.** At most 2× today's per song on an iPhone 12. Still held to
  Wi-Fi when that setting is on.
- **Memory.** Peak under 250 MB while analysing with both sessions open. Each
  loop mix holds two ~20 s stereo regions (about 7 MB).
- **CPU while mixing.** Under 15% of one core.
- **Failure.** Any missing framework, model, region or plan leaves Automix
  exactly as it is today.

## Implementation notes

- **Phase 1 (built).**
  - Waveforms are not decoded with a new `FfmpegRegionDecoder`. They reuse
    `FfmpegMonoDecoder` on the song's copy on the phone, or on the low-bitrate
    copy Automix's analysis fetches (deleted afterwards). Each song is decoded
    whole at 11 kHz mono, which takes about a second, and only the window's
    three bands are kept. Above 5.5 kHz nothing is drawn, and the highs layer
    shows 2.5–5.5 kHz.
  - The region decoder is still planned for Phase 2, where the models need
    44.1 kHz stereo.
  - The fader lines are sampled from a fresh `IosAutomixRide` of the same plan,
    because `at()` carries filter state from call to call.
  - The view is published right after `startNext`, because starting a blend
    ends the previous one and clears the view.
  - The shared band split is `MixWaveformBands` (`shared/commonMain`). Its name
    differs from the Android app's `MixWaveform` so the two don't clash in the
    same package.
- **Phase 2 (built).**
  - The trackers did not move into `commonMain`. The JVM's `BeatTracker`,
    `VocalTracker` and `MelSpectrogram` stay as they are, with their
    direct-buffer memory handling for Android's small heaps. The arithmetic
    that doesn't touch a runtime is in `shared/commonMain/.../ModelAnalysis.kt`:
    - `BeatModel`: chunking, peak picking, tempo, grid confidence, and the grid
      from the two logit heads;
    - `VocalModel`: the fixed-width re-stride and the 200 Hz–4 kHz band curve;
    - `ModelRegions`: Android's pass-2 windows, beat seaming, per-side beats,
      level curve, mask merge, and the assembly into a `TrackAnalysis`.
    It is tested in `ModelAnalysisTest` (12 tests). There is no `ModelRunner`
    interface or `expect` front end: iOS calls the models directly in
    `IosModels`.
  - The front ends are reached through `BitChordAnalysis` (ABI 2), which now
    compiles `mel_spectrogram.cpp`, `vocal_spectrogram.cpp` and `resampler.cpp`.
    `bitchord_frontend_shape` is checked against the Kotlin constants.
  - `BitChordModels` uses ONNX Runtime 1.22.0 (`onnxruntime-c` pod archive,
    SHA-256 pinned), with the macOS slice dropped.
  - The bridge returns every output, since Beat This! has two heads. It was run
    on Linux against both models: a 40-click track gave 39 beat peaks, and the
    vocal model gave its full `[1, 2, 2049, 960]` output.
  - Sessions close when the analysis queue empties, not after 60 s idle. A use
    count keeps a session from closing while an orphaned run still holds it.
  - Regions use `FfmpegMonoDecoder` in a new stereo region mode: it seeks, then
    drops samples up to the exact start. No separate `FfmpegRegionDecoder`.
  - The vocal threshold stays at the current 0.6, with the DSP mask unscaled,
    as this repo's Android app stores it. Upstream's 0.25 threshold and
    `heuristicVocalOnModelScale` arrive with Phase 3's planner port, for every
    platform at once.
  - Stored analyses record whether the models ran (schema 2). One made while
    the models could not load is redone once they can.
  - The on-device check is Settings › "Automix model check". It measures ten
    searched songs as Automix would, without storing them, and copies a report:
    model beats, vocal curves, times, and the time with models against without.
