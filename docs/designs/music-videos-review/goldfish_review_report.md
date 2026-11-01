# Goldfish Annotated Review Report

**Status: resolved.** Auto accept was applied on 2026-11-01: 17 fixes plus 2 owner decisions (E1: live mini player; E2: picture hidden on AirPlay). Nothing was contested; only wording nits were declined. The revised spec is [`music-videos.md`](../music-videos.md).

**Target:** [`docs/designs/music-videos.md`](../music-videos.md), full file.

**Source:** [consolidated_review_report.md](consolidated_review_report.md). The action numbers A1–A19 follow that report.

**Counts:** PROPOSE FIX 17 (and two more that wait on an ESCALATE answer) · ESCALATE 2 · CONTEST 0 · DECLINE (wording nits only).

## ESCALATE: owner decisions

### E1 (A9) [`zoom-out`]. Mini player live picture. Severity: Medium
*   **Affected scope:** Goal 6; §2 (claims and priorities); File 9 (MiniPlayer).
*   **Discrepancy:** The mini player claims the picture at priority 0. While the app is open it streams at Auto quality (1080p on Wi-Fi) into a 40 dp slot, and it keeps the screen awake while the user browses.
*   ***AI Suggestion:*** ESCALATE. This is a product choice between matching upstream and saving data and battery.
*   ***Options:***
    *   (a) The mini player shows a still 16:9 thumbnail and never claims the picture. The priority logic goes away.
    *   (b) Keep the live picture, matching upstream, and state its data and battery cost in the NFRs.
*   ***Owner decision:*** **(b) Live picture.** The mini player claims the picture at priority 0. The NFRs state the cost: while the app is open, a video row keeps streaming and keeps the screen awake.

### E2 (A1) [`design`]. AirPlay policy. Severity: Medium
*   **Affected scope:** Non-Goals (AirPlay video); §2; NFRs.
*   **Discrepancy:** AirPlay 2 adds about 2 s of output delay.
*   ***AI Suggestion:*** ESCALATE.
*   ***Options:***
    *   (a) Hide the picture while the route is AirPlay, and show the artwork.
    *   (b) Show the picture, delayed by the reported latency, and accept that it may be off by a few hundred milliseconds.
*   ***Owner decision:*** **(a) Hide the picture on AirPlay.** When the current route's output port is `AVAudioSessionPortAirPlay`, the state is `None` (artwork shown, landscape stays off) and the stream unloads. It comes back when the route changes.

## PROPOSE FIX: Blockers

### A1 [`design`]. Output latency is ignored. Severity: High
*   **Affected scope:** §2 "What it follows" and "How it keeps the two together"; Goal 1; on-device verification.
*   ***Proposed edit:***
    *   §2: the sync target becomes `positionMs + elapsed − heardLatencyMs`.
    *   `IosAudioEngine` exposes `heardLatencyMs` for the active deck and reads it every tick. Its value:
        *   FFmpeg deck: `AVAudioSession.outputLatency` plus the node's scheduled lead.
        *   AVPlayer deck: the same, unless a device test shows `currentTime` already includes it.
    *   `PlaybackPosition` stays unchanged.
    *   The AirPlay rule follows the answer to E2.
    *   Add a check to the on-device list: "AirPods: offset judged with the clap test ≤ 80 ms".

### A2 [`codebase-details`]. Skip-silence isn't followed. Severity: High
*   **Affected scope:** §2 "What it follows"; File 13; new file entry for `IosAudioEngine.kt`.
*   ***Proposed edit:***
    *   `IosAudioEngine` bumps `position.seeks` when a silence gap ends, on both the AVPlayer path (`endGap`) and the FFmpeg path (skip accounting).
    *   It exposes `inGap: Boolean` and `heardRate: Float`.
    *   The sync loop holds the video still while `inGap` is true, then snaps back into place.
    *   The speed nudge becomes `heardRate × (1 + nudge)`, as upstream does with `speed`, so Automix tempo ramps carry over.

### A3 [`implementation-readiness`]. Upstream sources are missing. Severity: High
*   **Affected scope:** Overview; every "copy from upstream" file entry.
*   ***Proposed edit:***
    *   Add an "Upstream reference" subsection:
        *   repo `kushagrasinghx/bitchord`, commit `b690dc14`;
        *   the video work is in commits `ba81c409` and `77634fb6`;
        *   a table giving the upstream path for each ported item.
    *   Copy these into `docs/designs/context/`:
        *   `MusicVideoLayout.kt`
        *   `PlayerHost.kt`
        *   `InnertubeParser.kt`
        *   `InnerTubeXResolver.kt`
        *   `YtMusicRepository.kt`
        *   `ScreenOrientation.kt` and `.android.kt`
        *   `VersionPairTest.kt`
        *   a `NowPlayingScreen.diff`
    *   Write out the remap commands: `git format-patch -1 <sha> -- <paths>`, then `sed 's#jvmSharedMain#commonMain#g'`, then `git apply -3`, then fix rejects by hand.
    *   Give the JSON paths: `playlistPanelVideoWrapperRenderer.{primaryRenderer,counterpart[].counterpartRenderer}.playlistPanelVideoRenderer`, and `counterpart[].segmentMap.segment[]{primaryVideoStartTimeMilliseconds,counterpartVideoStartTimeMilliseconds,durationMilliseconds}`. These are to be confirmed against the copied parser.

### A4 [`implementation-readiness`, `codebase-details`]. Version switch needs an engine API. Severity: High
*   **Affected scope:** §5; File 18; new `IosAudioEngine.kt` entry.
*   ***Proposed edit:***
    *   `swapStream(…, mapPosition: ((Long) -> Long)? = null)`. When it is set, `inStep()` uses `mapPosition(active)` and ignores `offsetMs`.
    *   The fallback `load` uses `mapPosition(position)` in the same way.
    *   The video resolves only after the song changes, with no pre-resolve. Until the first frame, the user sees the artwork with a spinner.
    *   Add a skit-gap switch test.

### A5 [`implementation-readiness`, `codebase-details`, `style`]. Step 0 can't run. Severity: High
*   **Affected scope:** Step 0.
*   ***Proposed edit:***
    *   Step 0 becomes Phase 0. It includes File 4 and a temporary Autoplay check.
    *   The sample is 20 ids, listed in a new appendix: 10 official music videos and 10 user uploads, half from before 2015.
    *   Rule: at least 18 of 20 ids give `avc1` from one of the first 3 clients tried.
    *   Add a check that 1080p plays 60 s with no stall over 1 s. If it fails, an `AVAssetResourceLoaderDelegate` chunker becomes the fallback.

## PROPOSE FIX: Majors

### A6. Retries and audio isolation. Severity: Medium
*   The resolver's codec loop keeps no state. The engine keeps upstream's `refused` map and passes it as `skipClients`.
*   There is one cap, `MAX_REFUSED_CLIENTS = 3`.
*   The engine never calls `exclude()` or `onRefused()`.
*   Copy `ESCALATED_REASONS` and `ageGated`. Don't remember network failures.
*   Read the HTTP status from `AVPlayerItem.errorLog().events.last.errorStatusCode`.
*   Use the InnerTubeX field name as it really exists (to check against the klib).

### A7. "No picture" state. Severity: Medium
*   Add a `musicVideoState: StateFlow<Loading | Showing | None>` hook.
*   Video mode, the spinner and `allowLandscape` all key on it. With `None`, the artwork shows and landscape stays off.

### A8. iPad. Severity: Medium
*   Keep upstream's tablet behaviour: the window shape decides.
*   On iPad, make no geometry requests and hide the Fullscreen and Exit buttons.

### A10. Reuse `next`. Severity: Medium
*   `YtMusicRepository` gets a `next` response cache (`LockedMap`, 10 min, 32 entries). `songMenu`, the lyrics and `officialVersion` all read from it.
*   The signed-in test is `Innertube.cookie != null`.
*   The time-map cache is a `LockedMap` keyed `"$from>$to"`.

### A11. Aspect ratio. Severity: Medium
*   The card range becomes 1.2–2.4, matching the cutoff.
*   Portrait (9:16) videos show as artwork. Add this to Non-Goals.

### A12. Background rule. Severity: Medium
*   Releasing the last claim starts a 4 s grace period.
*   Going to the background or locking the phone unloads immediately.
*   Coming back reloads through `rememberIsForeground()`.
*   Update Goal 4 and CUJ 3 to match.

### A13. Phases. Severity: Medium
Replace the flat file list with seven phases:

0.  Spike: File 4 and the codec log.
1.  Shared model, parser, repository and tests.
2.  PlayerHost hooks and the layout.
3.  Orientation: expect/actual, iOS and Swift.
4.  `IosMusicVideo`, host wiring and `IosAudioEngine`.
5.  Switching, Automix and menus.
6.  Smoke test and docs.

Each phase runs:
*   `:shared`, `:sharedUi` and `:iosKit` `compileKotlinIosArm64`;
*   `:shared:compileKotlinJvm` and `:sharedUi:compileKotlinJvm`;
*   `:app:compileDebugKotlin`;
*   the tests for that phase.

### A14. Duplicates. Severity: Medium
*   Delete File 19.
*   Use the existing `HideStatusBar`. Implement its iOS actual with KVC `statusBarHidden` and a request stack, and note that this also makes the "Hide status bar" setting work on iOS.
*   Drop the `automix_not_supported_video` and stats-row items.

### A15. iOS details. Severity: Medium
*   Picture handover: set `player` on the top view's layer and `nil` on the rest.
*   Poll `readyForDisplay` the way `IosCanvasVideo` does: every 100 ms, then every 1 s.
*   While paused, repaint with a seek to the current time.
*   Define `trackArtSize(base, isVideo)`, which returns `width = base × 16/9` for videos.
*   Export one Swift function, `MainViewControllerKt.supportedInterfaceOrientationsMask()`, which checks `UIDevice.userInterfaceIdiom`.
*   Grey out the menu row instead of hiding it (`IosMenus.kt`).
*   The failure cache stores an explicit `NotFound` marker.
*   The final message is "No video version found for this song".

### A16. Tests. Severity: Medium
*   Add a pure `syncStep(audioMs, videoMs, playing, inGap, nowMs, lastSeekAt, lead, rate) → Action` in commonMain, with a test case for each branch.
*   Test the codec filter with a fake extractor, and test `supportedMask`.
*   Smoke test:
    *   background the app with `xcrun simctl launch booted com.apple.Preferences`;
    *   play a `Song(isVideo = true)`;
    *   run after the interruption check;
    *   compute the median with an awk script.
*   On-device thresholds:
    *   clap test ≤ 80 ms;
    *   phone not hot to the touch after 15 min, with the battery reading in Settings under 40 °C.

### A17. Contradictions. Severity: Medium
*   Rewrite the `preferMusicOnly` text to match the decision.
*   Change the status to "Approved — pending Phase 0".
*   Default for signed-out users: today's behaviour.
*   One ordered fallback for missing H.264: first an InnerTubeX codec option, then HLS.
*   Correct the mini-player line and the "existing limit" line.

### A18. Alternatives. Severity: Medium
*   Add "F: host-time clock slaving" and reject it, because the FFmpeg/AVAudioEngine deck has no AVFoundation timebase.
*   Rewrite the rejections of B and C in terms of the cons already listed.

### A19. Risks and glossary. Severity: Medium
*   Add a Risks & Mitigations table.
*   Add a glossary covering: upstream, B0/B3/B4, client, `next`, `isVideo` vs `isVideoOrigin`, hero, sleeve and docking.

## PROPOSE FIX: Nits worth fixing
*   **Wrong names:**
    *   `IosPlayer.current` should be `IosPlayer.song`.
    *   `UIApplicationDidEnterBackground` should be `UIApplicationDidEnterBackgroundNotification`.
    *   `IosAutomix` should be `IosPlayer.automixTransition`.
    *   `CanvasPlayerView` should become `internal` and be reused.
*   **Unknown network:** treat a null `metered` value as metered (480p).
*   **Tests:** put them in `shared/src/commonTest/.../innertube/VersionPairTest.kt`, plus a separate `VersionTimeMapTest`.
*   **File 22:** change its marker to Modify.
*   **Observing `position.seeks`:** use `snapshotFlow`.
*   **Plist values:** use the full `UIInterfaceOrientationLandscapeLeft` and `UIInterfaceOrientationLandscapeRight`.
*   **`OrientationController`:** move it into `ScreenOrientation.kt`.
*   **`IosAutomixAnalysis.request`:** skip it for video rows.
*   **Defaults:** state the defaults for every hook.
*   **Line 239:** fix the wording.
*   **Line 209:** name `automaticallyWaitsToMinimizeStalling`.

## DECLINE
Wording nits in passages that the fixes above rewrite anyway:
*   contractions;
*   "at once" and "kicked on";
*   "small" and "short";
*   the "*Drafted with EGM*" footer, which the plugin's provenance convention keeps.
