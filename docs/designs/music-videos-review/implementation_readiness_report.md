# Stage 9: Implementation Readiness Test

**Target:** `docs/designs/music-videos.md`, the Detailed Implementation Plan (lines 382-618)

| Reviewer | Status |
| --- | --- |
| `egm-implementation-readiness` | completed. Verdict: **FAIL** under the Zero-Decision Rule (3 Blockers, 10 Majors, 7 Nits) |
| `egm-codebase-details` | completed (2 Blockers, 13 Majors, 12 Nits) |
| `egm-style` | completed (0 Blockers, 5 Majors, 25 Nits) |

Findings that more than one reviewer raised are merged into one entry. The tag after each finding says which reviewer raised it: R = readiness, C = codebase details, S = style.

## Blockers

* **I1. Upstream sources are missing (R).** Most files say "copy or port from upstream", but only two upstream files are in the repo. These have no source to work from:
  * `parseVersionPair` and `timeMapOf`, including their JSON paths;
  * `extractVideo` and `VideoStream`;
  * the `PlayerHost` hook signatures;
  * `MusicVideoLayout.kt`;
  * the NowPlayingScreen hunks;
  * `ScreenOrientation`;
  * the string keys;
  * the test fixtures.

  The "`git format-patch` remap" is not documented anywhere. **Fix:** name the upstream commit and the path of each item, copy the sources into `docs/designs/context/`, and write out the remap commands.
* **I2. Segment mapping needs an engine API that doesn't exist (R, C).** `IosAudioEngine.swapStream` only takes a fixed `offsetMs`, which it reapplies in `inStep()` (IosAudioEngine.kt:655). The fallback load at IosPlayer.kt:1059 also uses it. **Fix:** add IosAudioEngine.kt to the files, specify a mapper or absolute-start parameter and where it is evaluated, and add a test across a skit gap.
* **I3. Step 0 depends on code that doesn't exist yet (R, C).** Step 0 calls `extractVideo`, which is only added in file 4, and it names no sample videos. **Fix:** fold file 4 into Step 0, and list the 20 video ids or the rule for choosing them, and say where the results are recorded.
* **I4. Skip-silence doesn't bump `position.seeks` (C).** `seeks++` only happens on load, version swap and user seek (IosAudioEngine.kt:488, 562, 693, 841). On the AVPlayer path, the skip-silence gap fast-forwards at `GAP_RATE` and then `endGap` seeks back, so the sync loop sees audio running several times faster. On the FFmpeg path, dropped silence only shows up in `skippedMs`. **Fix:** either bump `seeks` and pause the drift check during a gap, or have the sync read the audio rate. Add IosAudioEngine.kt to the files.
* **I5. File 19 duplicates code that already exists (R, C; also Stage 5 #1).** `IosListeningRecorder.switched` already handles this and is already called. **Fix:** delete file 19.

## Major

* **I6. There are no phases (R, C, S).** The 22 files are a flat list, and some need to land together: files 10 and 11 must ship together, file 14 needs 6 and 13, and file 15 needs 11. **Fix:** group the files into numbered phases, each with its own compile and test commands.
* **I7. The PlayerHost hook signatures are incomplete (R, C).**
  * `MusicVideo` has no `priority` parameter, even though upstream's has `priority: Int = 1`.
  * The types and defaults aren't given for `musicVideoAspect`, `musicVideoQuality` and `setMusicVideoQuality`.
* **I8. The status bar function already exists (R, C; also Stage 8 B1).**
  * Use the existing `HideStatusBar`. Name the KVC `statusBarHidden` call, because `UIViewControllerBasedStatusBarAppearance` is false, and give the rule for stacking requests.
  * Note that this also turns on iOS support for the "hide status bar" setting.
* **I9. A string and a stats row already exist (C).** `automix_not_supported_video` and its stats row are already in strings.xml:10 (in 17 locales) and PlayerControls.kt:405. Re-adding the key would fail with a duplicate resource. **Fix:** drop both from files 9 and 12.
* **I10. `trackArtSize` doesn't exist (C).** **Fix:** define it, or give the literal change to PlayerQueue.kt:776.
* **I11. How the layer moves between views isn't defined (R).** Each view has its own `AVPlayerLayer`. **Fix:** specify the mechanism:
  * set `playerLayer.player` on the top claim and nil on the others;
  * poll `readyForDisplay` at a stated interval;
  * repaint while paused with a seek, as upstream does.
* **I12. AVPlayer doesn't give HTTP status codes directly, and chunking isn't decided (R; also Stage 8 B5).** **Fix:** name the source of the status code (`errorLog().events.last.errorStatusCode`), and say whether chunked fetching is dropped or how it is done.
* **I13. The error rules contradict upstream (R).** Upstream doesn't remember NETWORK failures or unexpected errors. It escalates on `AGE_RESTRICTED` and `NO_PLAYABLE_STREAM` and keeps an `ageGated` set. **Fix:** copy upstream's rules exactly.
* **I14. The codec-retry contract is underspecified (R, C).** It doesn't say how the retry interacts with:
  * `skipClients`, `excludedFor()` and the shared `exclude()` (Stage 7 C6);
  * one shared budget or two;
  * the real InnerTubeX field name (`codecs` vs `videoCodecs`);
  * what happens when every client fails (return null or throw);
  * bumping `sessionGeneration` from `onSessionChanged()`.
* **I15. The menu and the failure cache have no file (R, C).** The menu row is hidden today, not greyed out (IosMenus.kt:336). `videoVersionCache[id]?.let` can't tell a cached failure from no entry, so every failure searches again (YtMusicRepository.kt:473). The message text is still to be settled. **Fix:** add IosMenus.kt and the cache fix to the files.
* **I16. `officialVersion` is underspecified (C; also Stage 8 B4).** It needs:
  * the call: `next` forces `isAudioOnly` and RDAMVM, and `nextPlaylist` is the other option;
  * the signed-in test (`Innertube.cookie`);
  * the cache key, built on `LockedMap`.
* **I17. Nothing reloads the video when the app comes back to the foreground (C).** **Fix:** gate the claim on the existing `rememberIsForeground()` (ForegroundState.kt:37), as upstream does.
* **I18. The smoke test can't run as written (R, C).** Four problems:
  * The app can't send itself to the background. It needs an `xcrun simctl` step.
  * The smoke song has `isVideo = false`.
  * The interruption check pauses audio at 24-32 s, which overlaps the video-check window.
  * There's no script to compute the median, and CI runs signed out.
* **I19. Tests don't cover the risky logic (R).** **Fix:** extract a pure `syncStep(...)` into commonMain and test every branch. Also add tests for the codec filter (with a fake extractor) and for `supportedMask`.
* **I20. Verification only compiles the iOS targets (C).** The new `expect` declarations and InnerTubeXResolver changes also affect Android and the desktop JVM. **Fix:** add the jvm and Android compile tasks.
* **I21. Two Swift mask exports (R, C).** **Fix:** pick one, name it in Swift, and say how it detects iPad. Also say which root view controller gets `setNeedsUpdate…`.
* **I22. On-device checks have no pass/fail criteria (S).** "Lip-sync by eye" and the battery temperature check need a number to pass or fail against.
* **I23. The Step 0 decision rule is ambiguous (S).** "Within 3 clients" doesn't say whether that is per video or overall.

## Nits

* **Wrong names (R, C):**
  * `IosPlayer.current` should be `IosPlayer.song`.
  * `UIApplicationDidEnterBackground` should be `UIApplicationDidEnterBackgroundNotification`.
  * There is no `IosAutomix` object; the logic is in `IosPlayer.automixTransition`.
  * Line 264 says iOS has its own mini player, but it uses the shared one.
* **Repository cache (C).** Say "use `LockedMap`" instead of "the `SynchronizedObject` pattern".
* **Unknown network state (R, C).** Say what happens when `IosNetwork.metered` is null.
* **Test files (R, C).** Write out the elided test path. The existing innertube tests are in `commonTest`. The `*VersionPair*` filter won't match a separate `VersionTimeMap` test class.
* **File 22 (R, C, S).** It is marked "Create" but should be "Modify".
* **`CanvasPlayerView` is private (C).** Reuse it as internal, or say the code is copied.
* **Snapshot state (C).** `position.seeks` is Compose snapshot state, so it needs `snapshotFlow`.
* **Audio rate (C).** Follow the audio deck's rate, using `setHeardRate`, as upstream's `speed * (1+nudge)` does.
* **Plist names (C).** Use the full orientation names: `UIInterfaceOrientationLandscapeLeft` and `UIInterfaceOrientationLandscapeRight`.
* **`OrientationController` (C).** Put it in ScreenOrientation.kt.
* **Automix analysis (R).** Say whether `IosAutomixAnalysis.request` is also skipped for video rows.
* **Style (S):**
  * contractions;
  * "at once";
  * "kicked on";
  * "fixed up by hand";
  * "nerd-stats";
  * the Haze non sequitur;
  * "skit gap";
  * undefined CUJ, upstream and owner;
  * "small" and "short";
  * "See 19";
  * the "*Drafted with EGM*" footer.
