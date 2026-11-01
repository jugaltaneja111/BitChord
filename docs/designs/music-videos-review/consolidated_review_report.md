# Consolidated Goldfish Review: Music videos in Now Playing (iOS)

**Target:** [`docs/designs/music-videos.md`](../music-videos.md). The feature spec is lines 1-381 and the implementation plan is lines 382-618.

**Mode:** Consolidated report, all five stages, no pauses.

**Reviewer context:**
* The files the doc cites.
* `IosPlayer`, `IosCanvasVideo`, `IosPlayerHost`, `IosVersionAligner`, `InnerTubeXResolver`, `PlayerHost`, `project.yml` and `BitChordApp.swift`.
* The upstream copies of `MusicVideoPlayer.kt` and `VersionTimeMap.kt`.

| Stage | Reviewers | Status | Report |
| --- | --- | --- | --- |
| 5 Initial Zoom Out | zoom-out | completed | [zoom_out_report.md](zoom_out_report.md) |
| 6 Comprehension | comprehension | completed | [comprehension_report.md](comprehension_report.md) |
| 7 Critic | design, structural, style | completed | [critic_report.md](critic_report.md) |
| 8 Intermediate Zoom Out | zoom-out | completed | [intermediate_zoom_out_report.md](intermediate_zoom_out_report.md) |
| 9 Implementation Readiness | implementation-readiness, codebase-details, style | completed (readiness verdict: FAIL) | [implementation_readiness_report.md](implementation_readiness_report.md) |

## Overall verdict

**The architecture holds.** Every stage agreed with the core design:

* the audio is untouched;
* a muted video-only AVPlayer follows the audio clock;
* the shared parts are copied from upstream;
* rotation is unlocked only for video.

**The document is not ready to build from.** It has two design gaps that would show up as bugs on real phones:

* Bluetooth and AirPlay audio delay;
* skip-silence jumps.

It also contradicts itself in five places. Worst of all, most "copy from upstream" steps point at code that isn't in the repo.

## Action items by priority

### 🔴 Blockers: fix before any code

| # | Action | Sources |
| --- | --- | --- |
| A1 | **Compensate for audio output delay.** The sync target is the audio position minus the output delay. Read `AVAudioSession.outputLatency` on each tick. `IosAudioEngine` supplies the figure for each deck: the FFmpeg deck needs it, and for the AVPlayer deck it has to be checked on a device. Leave `PlaybackPosition` unchanged. Pick one AirPlay policy after a device measurement. Add an AirPods check to the on-device tests. | C1, A1 |
| A2 | **Follow skip-silence jumps.** Either `endGap` and the FFmpeg skip bump `position.seeks` and the sync pauses its drift check during a gap, or the sync reads the audio rate. Follow the audio rate in any case, since Automix ramps change it. Add IosAudioEngine.kt to the files. | I4, N5 |
| A3 | **Bring the upstream sources in.** Name the upstream commit. Copy every file that is ported into `docs/designs/context/`, or give its path. Write out the format-patch remap as commands. Spell out the parser's JSON paths. | I1, Stage 6 #3/#8 |
| A4 | **Give version switching an engine API.** Specify a new `swapStream` parameter (a mapper or an absolute start) and the point where it is evaluated, and use the same rule in the fallback load. Keep the one-way rule: the video resolves after the song changes, and is never pre-resolved by `IosPlayer`. State what the user sees mid-switch. | I2, C11, A5, Stage 5 #5 |
| A5 | **Make Step 0 runnable.** Include file 4 in it. List the 20 video ids. Add a stall and throughput check at 1080p. Make the decision rule clear: "≥18 of 20 ids return avc1 from one of the first 3 clients tried". | I3, I23, B5 |

### 🟡 Major: design decisions to settle

| # | Action | Sources |
| --- | --- | --- |
| A6 | **One owner for retries, and audio never affected.** The resolver runs only a stateless codec loop. The engine keeps upstream's `refused` map and passes it as `skipClients`. There is one cap, `MAX_REFUSED_CLIENTS = 3`. Video failures never call `exclude()` or `onRefused()`. Copy upstream's error rules exactly: `ESCALATED_REASONS`, `ageGated`, and no memory of network failures. Name the HTTP status source (`errorLog`). | C6, I13, I14, Stage 5 #2, A2 |
| A7 | **A "no picture" state.** Expose loading, showing or none for each video. Drive video mode, the spinner and `allowLandscape` from it, so a video that can't play falls back to its artwork with no landscape. | B2 |
| A8 | **iPad.** Accept upstream's tablet behaviour: the window shape decides. On iPad there are no geometry requests and the Fullscreen and Exit buttons are hidden. | Stage 5 #3, C9, A3 |
| A9 | **Mini player.** For v1 the mini player shows a static 16:9 thumbnail and never claims the live picture, so there is no 1080p stream into a 40 dp slot and no screen kept awake while browsing. This also removes the claim-priority plumbing (I7's `priority` parameter). If you'd rather keep upstream's live mini picture, state its data and battery cost instead. | B3, I7 |
| A10 | **Reuse the existing `next` call.** Parse the version pair from the `next` response the song menu already fetches, or share a cache of `next` responses. Specify the call, the signed-in check (`Innertube.cookie`) and the `LockedMap` cache. | B4, I16, C7 |
| A11 | **Make the aspect-ratio rules agree.** The card range and the 1.2:1 still-cover cutoff must match. State what happens to portrait (9:16) videos. | C10 |
| A12 | **One background rule.** When the last claim is released, wait 4 s. When the app goes to the background or the screen locks, unload immediately. When the app comes back, reload through `rememberIsForeground()`. Update Goal 4 and CUJ 3 to match. | C2, Stage 6 #1, I17 |
| A13 | **Phase the plan.** Group the files into numbered phases, each with a compile and test command. Include Android and JVM compiles. | I6, I20, Stage 6 #4 |
| A14 | **Remove duplicates of what already exists.** Delete file 19 (`switched` exists). Use the existing `HideStatusBar`, implemented on iOS with KVC `statusBarHidden`, and note that the "hide status bar" setting starts working. Drop the `automix_not_supported_video` string and stats row, which are already shipped. | I5, I8, I9, B1 |
| A15 | **Specify the iOS details.** The layer hand-off (`player` on the top view, nil on the others), the `readyForDisplay` poll, the repaint while paused, `trackArtSize`, one Swift mask export, a greyed menu row (IosMenus.kt), and a failure cache that can tell a cached null from a missing entry. | I10, I11, I15, I21 |
| A16 | **Tests that run.** Extract a pure `syncStep` with a test for every branch. Add tests for the codec filter and `supportedMask`. Background the smoke app with `simctl`. Use an `isVideo` song. Don't overlap the interruption check. Add a median-drift script. Give the on-device checks numbers. | I18, I19, I22 |
| A17 | **Fix the contradictions and stale text.** The `preferMusicOnly` text says "open question" but the decision was to keep it. The status reads "Approved" while questions are open: give each open question a default. Name one ordered H.264 fallback (InnerTubeX option, then HLS). Line 264 is wrong about the mini player. The "existing limit of 3" doesn't exist. | C3, C4, C5, Stage 5 #4, Stage 6 #2 |
| A18 | **Alternatives.** Add a host-time clock alternative and reject it: the FFmpeg deck has no AVFoundation timebase. Tie the rejections of B and C to their cons. | C8, A4 |
| A19 | **Add a Risks & Mitigations section and a short glossary.** The glossary covers upstream, B0/B3/B4, client, `next`, `isVideo` vs `isVideoOrigin`, and the UI terms. | C12, C13, Stage 6 #5-7, #14 |

### 🟢 Nits

Wrong names: `IosPlayer.song`, `…DidEnterBackgroundNotification`, `IosAutomix`, `CanvasPlayerView` being private. Unknown network state means 480p. Test paths and filters. File 22 should be Modify. `snapshotFlow`. Full plist orientation names. Wording and style issues listed in the Stage 7 and Stage 9 reports.

**Rejected as scope creep (Stage 8):**
* a "no video on cellular" switch;
* a user setting for rollback;
* sharing one player response between audio and video;
* pre-resolving the video before the swap;
* a new iPad landscape-with-card layout.

## Traceability matrix

| Action | Raw findings |
| --- | --- |
| A1 | Critic C1; Stage 8 A1 |
| A2 | Stage 9 I4 (codebase B1), N5 |
| A3 | Stage 9 I1 (readiness B1), codebase M10; Stage 6 #3, #8; Stage 7 C13 |
| A4 | Stage 9 I2, codebase M5; Critic C11; Stage 8 A5; Stage 5 #5 |
| A5 | Stage 9 I3, I23, codebase M8; Stage 8 B5; Critic C4 |
| A6 | Critic C6; Stage 5 #2; Stage 8 A2; Stage 9 I12, I13, I14, N6 |
| A7 | Stage 8 B2 |
| A8 | Stage 5 #3; Critic C9; Stage 8 A3 |
| A9 | Stage 8 B3; Stage 9 I7 (readiness M4, codebase M3) |
| A10 | Stage 8 B4; Stage 9 I16 (codebase M7); Critic C7; Stage 8 A6 |
| A11 | Critic C10 |
| A12 | Critic C2; Stage 6 #1; Stage 9 I17 (codebase M11) |
| A13 | Stage 9 I6, I20; Stage 6 #4 |
| A14 | Stage 5 #1; Stage 9 I5, I8, I9; Stage 8 B1 |
| A15 | Stage 9 I10, I11, I15, I21; Stage 8 B6, B8 |
| A16 | Stage 9 I18, I19, I22 |
| A17 | Critic C3, C4, C5; Stage 5 #4; Stage 6 #2; Stage 8 B7; codebase N10 |
| A18 | Critic C8; Stage 8 A4; structural #5 |
| A19 | Critic C12, C13; Stage 6 #5-7, #9-10, #14, #16 |
