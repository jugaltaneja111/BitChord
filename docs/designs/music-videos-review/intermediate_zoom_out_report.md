# Stage 8: Intermediate Zoom Out

**Target:** `docs/designs/music-videos.md`, the full file, lines 1-618.

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |

**Verdict:** The architecture holds:

* a muted follower AVPlayer;
* copy upstream wherever possible;
* audio left untouched.

There are no blockers. Some fixes proposed in earlier stages need to be made smaller before they are applied.

## Part A: How the earlier fixes fit

* **A1 🟡, C1 output latency.** Keep the correction, but make the mechanism smaller.
  * Read `AVAudioSession.outputLatency` on every 250 ms tick. It already reflects the current route, so no per-route tracking is needed.
  * Take the latency figure from `IosAudioEngine`, which knows which deck is active:
    * the FFmpeg deck counts frames handed to its player node, so it needs the correction;
    * on the AVPlayer deck, check on a device whether `currentTime` already includes the latency.
  * Don't change `PlaybackPosition`, because lyrics and Listen Together read it.
  * For AirPlay, pick one approach after measuring. Don't build both.
  * Add the AirPods check to the on-device list, not as a new user journey.
* **A2 🟢, C6 and Stage 5 #2 retries.**
  * The resolver runs only the codec retry loop and keeps no state between calls.
  * The engine owns the `refused` memory, the same as upstream, passed in as `skipClients`.
  * One cap of 3, using upstream's `MAX_REFUSED_CLIENTS`.
  * Never call `exclude()` or `onRefused()`.
* **A3 🟡, iPad.** Card-in-landscape is a new layout that upstream doesn't have, so it adds scope. Accept upstream's tablet behaviour instead:
  * the window shape decides when full screen is used;
  * no geometry requests on iPad;
  * the Fullscreen and Exit buttons are hidden on iPad.
* **A4 🟢, C8 host-time clock.** Document it as a rejected alternative. It needs a shared AVFoundation timebase, and the FFmpeg/AVAudioEngine deck doesn't have one.
* **A5 🟡, C11 pre-resolve.** Pre-resolving would make `IosPlayer` drive the video engine, which breaks the one-way dependency.
  * Keep resolving when the song changes.
  * Specify what the user sees while the video loads.
* **A6 🟢, C7 shared player response.** Sharing one response between audio and video needs a change to InnerTubeX. Drop it. Keep the request estimate and the debounce.
* **A7 🟢, cellular off switch and rollback setting.** YAGNI for v1. Reject both.
* **A8.** No architectural concern with C2-C5, C10, C12-C14 or the Stage 6 fixes.

## Part B: New findings

* **B1 🟡: `HideStatusBar(hidden)` already exists.**
  * It is declared in `StatusBarIcons.kt:18-19`, its iOS actual is a no-op (lines 53-54), and `NowPlayingScreen.kt:770` calls it.
  * Implement the existing iOS actual instead of adding a new `HideSystemBars`.
  * Side effect: the "Hide status bar" player setting starts working on iOS.
* **B2 🟡: the UI and the orientation flag have no "no picture" state.**
  * `showsMusicVideo(song)` only checks the song.
  * For a video with no H.264 stream, or one that failed:
    * the spinner never ends;
    * landscape unlocks into an empty full-screen view.
  * Expose loading / showing / none, and use it for both video mode and `allowLandscape`.
* **B3 🟡: the mini player's live picture keeps streaming and keeps the screen awake while browsing.**
  * It streams 1080p into a 71×40 dp slot.
  * Simplest fix for v1: the mini player shows a static 16:9 thumbnail.
  * Otherwise, state the data and battery cost in the NFRs.
* **B4 🟡: `officialVersion` adds a `next` call that is already being made.**
  * `IosMenus.kt:279` → `YtMusicRepository.songMenu` → `Innertube.next` already fetches it, and so do lyrics.
  * Parse the pair from the menu's response, or share a cache of `next` responses.
  * Line 353's claim that the pair comes "free" is false as the design stands.
* **B5 🟡: "bounded byte ranges" don't happen on the iOS AVPlayer path.**
  * Upstream uses a 1 MiB chunked source. iOS uses a plain `AVURLAsset`.
  * googlevideo throttles single unbounded requests.
  * Add a 1080p stall and throughput check to Step 0, and name chunking (a resource-loader delegate) as the fallback.
  * Correct line 181.
* **B6 🟢: the orientation plumbing could collapse into the `StatusBarIcons.ios.kt` pattern.**
  * At minimum, keep one mask export.
* **B7 🟢: line 264 says iOS has its own mini player.** It doesn't; it uses the shared one (`IosApp.kt:215`).
* **B8 🟢: `CanvasPlayerView` is private.** Either make it internal and reuse it, or say the copy is deliberate.
