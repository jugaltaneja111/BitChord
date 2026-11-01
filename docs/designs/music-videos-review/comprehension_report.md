# Stage 6: Comprehension Test

**Target:** `docs/designs/music-videos.md`. FeatureRange is lines 1-381 and DetailedRange is lines 382-618.

**Reviewer:** `egm-comprehension` (completed)

**What this proposes**

When a music-video row plays on iOS, a second, muted AVPlayer shows the video-only stream in a card in Now Playing.

* **Sync:** it follows `IosPlayer`'s audio clock through a 250 ms loop.
  * Drift under 450 ms is corrected by changing speed by up to 10%.
  * Larger drift is corrected by a seek.
  * While the audio is paused, the picture snaps to the frame.
* **Codec:** only H.264 (`avc1`) is accepted, and the resolver tries more YouTube clients until one offers it. If none does, the track plays over its artwork.
* **Copied from upstream Android:** the segment-based `VersionTimeMap`, the parsing of song/video pairs, and the fullscreen and quality UI. Song/video switching lands at the matching moment, with waveform alignment as the fallback.
* **iPhone landscape:** allowed only while Now Playing shows a video, through a Kotlin flag that a new Swift AppDelegate reads.
* **Streaming:** the video streams only while a screen claims the picture, plus a 4 s grace period.
* **Quality:** Auto is 1080p on Wi-Fi and 480p on metered connections, or the user picks a height and it is remembered.

**How the existing system works, per this document**

* **Playback:** iOS plays video rows as audio over a 16:9 thumbnail. The sound goes through `IosPlayer`, using AAC on AVPlayer or Opus on FFmpeg, from YouTube only.
* **Video rows** skip add-on sources and the quality upgrade.
* **"Convert to video"** already exists.
  * It uses `IosVersionAligner`, which compares the first 45 s of both waveforms.
  * It needs both streams, gives up after 20 s, and fails on remixes and long intros.
  * The switch crossfades over 550 ms.
* **Orientation:** iPhone is portrait-only in `project.yml`, while iPad rotates.
* **Codec choice:** InnerTubeX v0.7.0 can't filter by codec, and it ranks VP9 the same as H.264.
* **Landscape:** shared UI treats a window that is wider than it is tall as landscape (`landscapePlayerAvailable`).

## Findings

1. 🟡 **Major: backgrounding is described two ways.**
   * Goal 4 and CUJ 3 say the video is released "after 4 s" in the background or on lock (lines 69, 115).
   * §2, the NFRs, File 13 and the smoke test say it unloads "at once", within 1 s (lines 214, 360, 509, 593).
   * Locking the phone sends the app to the background, so the two can't both be right.
2. 🟡 **Major: the `preferMusicOnly` text contradicts itself.**
   * Line 282 calls it an "open question".
   * Lines 376 and 569 say the owner decided to keep it.
3. 🟡 **Major: undefined project labels.**
   * "B0" (lines 302 and 369, the audio-safety rule) and "B3/B4" (lines 457, 473 and 502) are never defined.
   * The "`git format-patch` remap" procedure (line 473) is never described.
4. 🟡 **Major: work order is unclear.**
   * Line 384 says "each step ends with … passing", but only Step 0 is a step. Files 1-22 follow with no grouping or order.
   * Some files depend on others: 13 needs 4 and 6, and 15 needs 11.
5. 🟡 **Major: "client", "client profile" and `next` are never explained.**
   * The codec fallback and the Step 0 decision rule ("within 3 clients") depend on them.
6. 🟡 **Major: `isVideo` and `isVideoOrigin` are both used without explanation.**
   * They appear at lines 264, 280, 479, 510 and 567.
   * The difference matters after "Convert to audio".
7. 🟢 **Nit: product features with no gloss.** Automix, Listen Together, add-on sources and the quality upgrade, Smart alignment, and the nerd-stats line.
8. 🟢 **Nit: "upstream" is never identified.**
   * The document doesn't say which repo or commit upstream is.
   * The context files aren't linked.
   * `MusicVideoEngine` and the web `videoDeck` aren't pointed to.
9. 🟢 **Nit: "ATV" and `fits()` are undefined** (line 407).
10. 🟢 **Nit: YouTube and project terms with no gloss.** SABR, PoToken, googlevideo, `sessionGeneration`, `onVideoRefused` and `ios-latest`.
11. 🟢 **Nit: the adaptive seek-lead rule isn't stated** (lines 206 and 517).
12. 🟢 **Nit: the claim priority of `FullscreenVideoPlayer` isn't given** (line 211).
13. 🟢 **Nit: line 239 is garbled, and the status-bar mechanism isn't described.**
14. 🟢 **Nit: UI jargon.** "Hero", "paused shrink", "card morph", "docking" and "square sleeve" are used without explanation (lines 253-259 and 462-471).
15. 🟢 **Nit: process references.** Line 286 mentions "no brainstorm brief" and "the rotation decision", which mean nothing to a reader outside the design process.
16. 🟢 **Nit: the relationship between `IosNowPlaying` and the shared `NowPlayingScreen` isn't explained.**
