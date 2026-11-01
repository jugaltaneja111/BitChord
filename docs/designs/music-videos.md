# Music videos in Now Playing (iOS)

*   **Author:** jugaltaneja111 (the owner, who makes the product decisions below)
*   **Status:** Approved, pending Phase 0 (the H.264 coverage check, which is a go/no-go gate)
*   **Date:** 2026-11-01. Revised the same day after a Goldfish review; see [the review](music-videos-review/goldfish_review_report.md).

--------------------------------------------------------------------------------

## Overview

BitChord v1.9 on Android shows a music video's picture in Now Playing.

*   **The sound.** The audio player plays the video's own soundtrack, in the same way it plays any track.
*   **The picture.** A second player draws it. That player is muted and has its audio track turned off.
*   **Keeping them together.** The picture follows the audio player's clock:
    *   a drift of more than 25 ms is corrected by changing the picture's speed by up to 10%;
    *   a drift of more than 450 ms is corrected by jumping the picture to the audio's position.
*   **Switching between the song and its video.** YouTube Music sends a "segment map" that says which part of the video matches which part of the song. Upstream's `VersionTimeMap` reads it, so the other version picks up at the same moment in the music.

The iOS port has none of this. A video row plays as audio over its 16:9 thumbnail.

This design copies the shared parts from upstream:

*   the time map;
*   the parsing of song/video pairs;
*   `extractVideo`, which fetches a video stream;
*   the fullscreen and quality screens.

It rebuilds the Android-only parts in iosKit:

*   the second player, muted and kept in sync with the audio;
*   screen rotation;
*   hiding the status bar.

### Upstream reference

*   **"Upstream"** is the Android/desktop BitChord repository, `kushagrasinghx/bitchord`, at commit `b690dc14`. In this repo it is fetched as `refs/upstream/main`.
*   The iOS port branched from `edc1e6f`.
*   The music-video work is in upstream commits `ba81c409` ("full video support") and `77634fb6` (`VersionTimeMap`).
*   Copies of the upstream files, saved for this design, are in [`context/`](context/):

| Item ported | Upstream path (at `b690dc14`) | Copy |
| --- | --- | --- |
| `VersionTimeMap`, `VersionPair` | `shared/src/commonMain/.../data/model/VersionTimeMap.kt` | [upstream_VersionTimeMap.kt](context/upstream_VersionTimeMap.kt) |
| `parseVersionPair`, `timeMapOf`, `parseWatchQueue` | `shared/src/jvmSharedMain/.../data/innertube/InnertubeParser.kt` (lines 1280-1420) | [upstream_InnertubeParser.kt](context/upstream_InnertubeParser.kt) |
| `VideoStream`, `extractVideo`, `onVideoRefused`, `sessionGeneration` | `shared/src/jvmSharedMain/.../data/innertube/InnerTubeXResolver.kt` | [upstream_InnerTubeXResolver.kt](context/upstream_InnerTubeXResolver.kt) |
| `officialVersion`, `versionTimeMap` | `shared/src/jvmSharedMain/.../data/YtMusicRepository.kt` (lines 50-66, 425-524) | [upstream_YtMusicRepository.kt](context/upstream_YtMusicRepository.kt) |
| `PlayerHost` video hooks, `MusicVideoQualityUi`, `showsMusicVideo`, `MusicVideoSurface` | `sharedUi/src/jvmSharedMain/.../ui/player/PlayerHost.kt` | [upstream_PlayerHost.kt](context/upstream_PlayerHost.kt) |
| `FullscreenVideoPlayer`, `VideoCornerControls`, `VideoQualitySheet`, `VideoLoadingBackdrop` | `sharedUi/src/jvmSharedMain/.../ui/player/MusicVideoLayout.kt` | [upstream_MusicVideoLayout.kt](context/upstream_MusicVideoLayout.kt) |
| Now Playing video mode | `sharedUi/.../ui/player/NowPlayingScreen.kt` (diff `edc1e6f..b690dc14`) | [upstream_NowPlayingScreen.diff](context/upstream_NowPlayingScreen.diff) |
| `ScreenOrientation` | `sharedUi/src/jvmSharedMain/.../ui/theme/ScreenOrientation.kt`, `androidMain/.../ScreenOrientation.android.kt` | [upstream_ScreenOrientation.kt](context/upstream_ScreenOrientation.kt), [.android.kt](context/upstream_ScreenOrientation.android.kt) |
| Sync engine (reference only; Android-only) | `app/.../ui/player/MusicVideoPlayer.kt` | [upstream_MusicVideoPlayer.kt](context/upstream_MusicVideoPlayer.kt) |
| Parser test fixtures | `shared/src/jvmTest/.../innertube/VersionPairTest.kt` | [upstream_VersionPairTest.kt](context/upstream_VersionPairTest.kt) |

**How upstream changes are carried over.** This is the procedure used for the earlier v1.9 batches. Upstream keeps shared code in `jvmSharedMain`, but the port keeps it in `commonMain`. The steps:

1.  `git format-patch -1 --stdout <sha> -- <paths> > /tmp/p.patch`
2.  `sed -i 's#/jvmSharedMain/#/commonMain/#g' /tmp/p.patch`
3.  `git apply -3 /tmp/p.patch`
4.  Resolve any conflicts by hand.
5.  Replace JVM-only calls with the port's common equivalents:
    *   okhttp becomes the Ktor `appHttpClient`;
    *   `ConcurrentHashMap` becomes `LockedMap`;
    *   `System.currentTimeMillis` becomes `kotlin.time`.

## Glossary

*   **B0, B3, B4.** Earlier batches of the iOS v1.9 catch-up.
    *   **B0:** pausing for phone calls and unplugged headphones, and the fix for songs stopping in the middle of a queue (`IosInterruptions`, `FfmpegAudioEngine`).
    *   **B3:** Top Picks on Home.
    *   **B4:** Now Playing polish.
*   **Client / client profile.** The identity BitChord uses when it asks YouTube's InnerTube API for a stream, for example WEB_REMIX, IOS or TVHTML5. Different clients get different sets of formats. InnerTubeX tries them in its own order, and `skipClients` removes some of them for one request.
*   **`next`.** InnerTube's "watch next" endpoint (`Innertube.next(videoId)`). It returns the queue panel, and each row's song or video counterpart along with its segment map.
*   **`isVideo` / `isVideoOrigin`** (`Song`, in `Models.kt`).
    *   `isVideo` means this row *is* a music video now.
    *   `isVideoOrigin` means the row started as a video, including a video that was converted to its audio version. It defaults to `isVideo`.
    *   The picture keys on `isVideo`. The Automix skip keys on `isVideoOrigin`, as upstream's does.
*   **OMV / ATV / UGC.** YouTube's `musicVideoType` values: an official music video, an "art track" (a catalogue song with a still cover), and a user upload.
*   **Hero, sleeve, docking.** Upstream's names for parts of Now Playing:
    *   **hero:** the full-bleed artwork mode;
    *   **sleeve:** the square cover;
    *   **docking:** the shrink into the mini player.
*   **Automix.** BitChord's DJ-style transitions: beat-matched with tempo ramps and EQ swaps, as opposed to a plain crossfade.
*   **Listen Together.** BitChord's shared listening party, where everyone hears the same thing.

## The Problem

*   **Choosing a video row shows nothing different from the song row.** OMV and UGC rows play exactly like songs.
*   **"Convert to video" only changes the audio.** The iOS menu row already exists. To line the two versions up, it compares the first 45 s of their waveforms (`IosVersionAligner`). That comparison:
    *   needs both streams to be downloaded first;
    *   gives up after 20 s;
    *   can only produce one fixed offset for the whole song.

    YouTube's segment map, which signed-in users already receive in the `next` response, gives a separate mapping for each part of the song and needs no download.
*   **No full screen on iPhone.** The iPhone build only allows portrait (`iosApp/project.yml`).

The risks are listed in [Risks & Mitigations](#risks--mitigations).

## Goals

1.  **Picture in Now Playing.** When a video row plays and Now Playing is open, the picture shows in a card.
    *   The card uses the video's own aspect ratio, between 1.2:1 and 2.4:1. Most videos are 16:9.
    *   Within 1 s of the first frame, the picture is within 25 ms of the sound as heard (see [§2](#2-the-video-player-and-sync-ioskit-new-iosmusicvideo)), and it stays there.
2.  **Full screen.** On iPhone, turning the phone sideways or tapping Fullscreen shows the video full screen in landscape.
    *   The status bar is hidden.
    *   A tap shows the controls. They hide again after 3.5 s with no touch while playing.
    *   Turning the phone upright returns to Now Playing.
    *   iPad follows its window shape, as upstream tablets do. See [§3](#3-rotation).
3.  **Switching between song and video.** Switching uses YouTube's segment map when one exists, and waveform alignment when it doesn't. Either way, playback continues at the same moment in the music, using the existing 550 ms crossfade.
4.  **Data only while a picture is claimed.**
    *   The video stream is open only while a picture is on screen, in Now Playing, full screen or the mini player.
    *   When the last picture goes, the stream is released 4 s later. The grace period covers a rotation, or a sheet being closed and reopened.
    *   When the app goes to the background or the screen locks, the stream is released immediately.
    *   The audio is never affected by any of this.
5.  **Quality.** The user can choose Auto or a fixed height up to 1080p.
    *   Auto means 1080p on Wi-Fi.
    *   Auto means 480p on a metered connection, or when the network type is unknown.
    *   A height the user picks is remembered.
6.  **Video shape elsewhere.** A video row in the queue uses 16:9 artwork. The mini player shows the live picture in a 16:9 slot (owner's decision).
7.  **Same rules as Android.**
    *   Video rows always stream from YouTube: no add-on sources and no quality upgrade.
    *   Automix is off for them.
    *   In Listen Together the version can't be switched.

## Non-Goals

*   Picture-in-picture, or video on the lock screen or in CarPlay.
*   **AirPlay video.** While the audio route is AirPlay, the picture is hidden and the artwork shows (owner's decision). AirPlay adds about 2 s of delay, and the delay a speaker reports is not reliable.
*   VP9, AV1, or anything above 1080p.
*   Portrait videos (narrower than 1.2:1, such as 9:16). They show as artwork, like a still cover.
*   Saving videos for offline use, or caching them on disk.
*   Video stations, where a queue started from a video keeps playing more videos.
*   The desktop and web players.

## Open Questions

Each question has a default answer, so none of them blocks the work.

*   **H.264 coverage.**
    *   InnerTubeX v0.7.0 can't choose a codec: `ContentHints` has only `wantVideo` and `maxVideoHeight`, and `selectBestVideoFormat` ranks H.264 and VP9 the same.
    *   This design checks the codec of each result (`ExtractedStream.videoCodecs`) and asks another client when the result isn't H.264.
    *   How often no client offers H.264 is unknown. **Phase 0 measures it.**
    *   If coverage is too low, the fallbacks in order are:
        1.  ask InnerTubeX upstream for a codec preference, or fork it;
        2.  Alternative D, YouTube's HLS stream.

        Either one goes back to the owner, because it changes the library or the stream type.
*   **Signed-out users.** Signed-out `next` responses contain no song/video pairs. **Default:** switching falls back to search plus waveform alignment, which is how it works today.

## Critical User Journeys (CUJs)

### CUJ 1: Watch a music video

*   **Given:** A signed-in user on Wi-Fi with a video row in the queue.
*   **When:** The row starts playing and Now Playing is open.
*   **Then:**
    *   The artwork shows with a spinner until the first frame.
    *   Within 2 s on Wi-Fi the video replaces it, within 25 ms of the sound.
    *   The audio plays without a gap.

### CUJ 2: Full screen (iPhone)

*   **Given:** A video is playing in Now Playing.
*   **When:** The user turns the phone sideways or taps Fullscreen.
*   **Then:**
    *   The video fills the screen in landscape, with the status bar and the controls hidden.
    *   A tap shows the controls.
    *   Turning the phone upright, or tapping Exit, returns to Now Playing.

### CUJ 3: Lock the phone

*   **Given:** A video is playing.
*   **When:** The user locks the screen.
*   **Then:**
    *   The audio keeps playing.
    *   The video stream is released within 1 s.
    *   When the user unlocks and opens Now Playing, the video reloads and catches up, within 25 ms of the sound after about 1 s.

### CUJ 4: Switch between song and video

*   **Given:** The catalogue song is playing.
*   **When:** The user taps "Convert to video" in the track's menu.
*   **Then:**
    *   The audio crossfades over 550 ms to the video's soundtrack, at the same moment in the music. The segment map is used when there is one, and waveform alignment when there isn't.
    *   The card shows the artwork with a spinner until the first frame, usually within 2 s, then the picture.
    *   "Convert to audio" switches back the same way. The card returns to the artwork.

### CUJ 5: No video version exists

*   **Given:** A song with no video counterpart.
*   **When:** The user opens the track's menu.
*   **Then:**
    *   If no counterpart has been found yet, the user can tap "Convert to video". They then see "No video version found for this song", and playback carries on.
    *   From then on, for this session, the row is greyed out.

### CUJ 6: On cellular

*   **Given:** A metered connection.
*   **When:** A video shows.
*   **Then:** It plays at 480p, unless the user picked a height.

### CUJ 7: Headphones and AirPlay

*   **Given:** A video is playing on AirPods.
*   **When:** The user watches a cut to a drum hit or a clap.
*   **Then:** The picture and the sound line up within 80 ms, judged by eye.
*   **And when:** The user switches the output to an AirPlay speaker.
*   **Then:** The picture gives way to the artwork. It comes back when the output returns to the phone or the headphones.

## Technical Plan (Architecture)

### Shape of the solution

How the sound is produced does not change. A video row plays through the current audio path:

*   `IosPlayer`;
*   YouTube as the only source;
*   AAC through AVPlayer, or Opus through FFmpeg.

The picture comes from a second, muted `AVPlayer` that plays YouTube's **video-only** stream. That player follows `IosPlayer`'s clock and never drives it. Dependencies point one way only: `IosMusicVideo` reads `IosPlayer` and `IosAudioEngine`, and nothing in the audio path calls `IosMusicVideo`.

```text
           ┌──────────── shared (commonMain) ────────────┐
 next  ──▶ │ YtMusicRepository.nextCached(id)            │──▶ song menu, lyrics, officialVersion
           │ InnertubeParser.parseVersionPair             │
           │   └▶ VersionPair(song, VersionTimeMap?)      │
 player──▶ │ InnerTubeXResolver.extractVideo(id, maxH,    │──▶ VideoStream(url, w, h, codecs, heights)
           │   skipClients, requireCodecPrefix="avc1")    │
           │ MusicVideoSync.syncStep(...)  (pure)         │
           └──────────────────────────────────────────────┘
                                 │
           ┌──────────── iosKit ─▼────────────────────────┐
           │ IosAudioEngine: position, seeks, inGap,      │
           │   heardRate, heardLatencyMs, swapStream(map) │
           │ IosMusicVideo (object)                       │
           │   muted AVPlayer ◀── 250 ms loop + syncStep  │
           │   claims(priority) · 4 s grace · bg unload   │
           │   state: Loading | Showing | None            │
           │ IosOrientation mask ◀── Showing && NowPlaying│──▶ Swift AppDelegate
           └──────────────────────────────────────────────┘
                                 │ PlayerHost hooks
           ┌──────────── sharedUi ▼───────────────────────┐
           │ Now Playing video card, FullscreenVideoPlayer│
           │ corner controls, quality sheet, mini player  │
           └──────────────────────────────────────────────┘
```

### 1. Shared code (copied from upstream)

*   **`VersionTimeMap` and `VersionPair`.** Copied unchanged. Inside a matched segment a position maps exactly. Before a segment it maps to where the music starts. After the last segment it maps to the end.
*   **`InnertubeParser.parseVersionPair` and `timeMapOf`.** They read one `next` row:
    *   path: `playlistPanelVideoWrapperRenderer.{primaryRenderer, counterpart[]}`;
    *   each counterpart is `counterpart[].counterpartRenderer.playlistPanelVideoRenderer`, plus `counterpart[].segmentMap.segment[]`;
    *   each segment has `primaryVideoStartTimeMilliseconds`, `counterpartVideoStartTimeMilliseconds` and `durationMilliseconds`, sent as strings.
    *   The type comes from `musicVideoType`. Only `MUSIC_VIDEO_TYPE_ATV` counts as the song half. Any other type, or no type, counts as video.
*   **`YtMusicRepository`.**
    *   **`next` cache.** A new `nextCached(videoId)`: a `LockedMap` of `next` responses, kept 10 minutes, at most 32 entries. `songMenu`, YouTube lyrics and `officialVersion` all read from it, so opening the menu and switching cost one request between them.
    *   **`officialVersion(song, wantAudio)`.** Returns `null` immediately when `Innertube.cookie == null` (signed out). It stores the time map in both directions in a `LockedMap` keyed `"$from>$to"`, and `versionTimeMap(from, to)` reads it back.
    *   **Fallbacks.** `resolveAudio` and `resolveVideo` try `officialVersion` first, then fall back to today's search.
    *   **Failed lookups.** `videoVersionCache` stores an explicit `NotFound` marker, so it can tell "searched and found nothing" from "never searched". Today a cached `null` reads the same as a missing entry.
*   **`InnerTubeXResolver.extractVideo(videoId, maxHeight, skipClients, ageRestricted, requireCodecPrefix)`.**
    *   It requests `ContentHints(wantVideo = true, maxVideoHeight)` with no HLS and no SABR, and returns a `VideoStream`.
    *   With `requireCodecPrefix = "avc1"`, if `videoCodecs` doesn't start with `avc1`, it adds that client to a skip set for this call only and asks again, up to the caller's budget. If nothing qualifies, it returns `null`.
    *   It keeps no state between calls, and never touches the shared `exclude()` map, which audio uses. The `sessionGeneration` counter is bumped from the existing `onSessionChanged()`.

### 2. The video player and sync (iosKit, new `IosMusicVideo`)

One object owns one muted `AVPlayer`.

#### What it follows

`IosAudioEngine` exposes, alongside `position`:

*   **`heardLatencyMs`:** how far the sound you hear is behind `positionMs`.
    *   On the FFmpeg deck: `AVAudioSession.outputLatency` plus the node's scheduled lead.
    *   On the AVPlayer deck: `outputLatency`, unless a device test in Phase 4 shows that `currentTime` already accounts for it, in which case it is 0.
    *   It is read on every tick, so a route change is picked up with no extra listener.
*   **`heardRate`:** the speed of the active deck. Automix tempo ramps change it through `setHeardRate`.
*   **`inGap`:** true while skip-silence is skipping a silent gap.
*   **`position.seeks`:** this counter now also goes up when a skip-silence gap ends, on the AVPlayer path in `endGap` and on the FFmpeg path in its skip accounting. Today only loads, version swaps and user seeks bump it.

The sync target is `positionMs + (now − sampledAtNanos) × heardRate − heardLatencyMs`. `PlaybackPosition` itself doesn't change, because lyrics and Listen Together read it too. `IosMusicVideo` watches `position.seeks` and `IosPlayer.isPlaying` through `snapshotFlow`.

#### How it keeps the two together

There is one pure function in commonMain, `MusicVideoSync.syncStep(target, video, playing, inGap, nowMs, lastSeekAt, seekLead, rate) → Action`. It returns `Pause`, `Hold`, `Seek(to)`, `SetRate(r)` or `None`. Its constants are copied from upstream `MusicVideoEngine` ([context](context/upstream_MusicVideoPlayer.kt), lines 77-94 and 472-533).

*   **Audio paused:** the video pauses. If it is more than 40 ms off, it seeks to the exact frame, so scrubbing shows the right picture.
*   **In a skip-silence gap:** the video holds still. When the gap ends, the `seeks` bump makes it jump straight to the right place.
*   **Drift between 25 and 450 ms:** the video's speed is set to `rate × (1 + clamp(−drift / 1000, ±0.1))`.
*   **Drift over 450 ms:** at most once every 1.5 s, it seeks to `target + seekLead`.
    *   `seekLead` starts at 250 ms.
    *   After each seek it becomes the average of the old lead and how long that seek took to settle, kept between 0 and 1500 ms.

The loop runs every 250 ms on the main thread, and also immediately on play, pause or a `seeks` change. The player has `automaticallyWaitsToMinimizeStalling = false`, so only the sync loop decides its speed.

#### Where the picture is drawn

*   Each surface is a `UIView` whose layer is an `AVPlayerLayer`. It reuses `CanvasPlayerView` from `IosCanvasVideo.kt`, which becomes `internal`.
*   A surface claims the picture with a priority: Now Playing and full screen use 1, the mini player 0.
*   **Hand-off.** The claim with the highest priority gets `playerLayer.player = avPlayer`; every other claim's layer gets `nil`. A newly attached layer is given a frame while paused by seeking to the current time, as upstream's `repaint()` does.
*   **First frame.** `readyForDisplay` on the top layer is checked every 100 ms until true, then every 1 s, as `IosCanvasVideo` does. It drives `rendered`.
*   **No claims left:** a 4 s timer runs, then `unload()` stops the player and empties its items.
*   **Background:** on `UIApplicationDidEnterBackgroundNotification`, `unload()` runs immediately.
*   **Returning:** a surface claims only while `rememberIsForeground()` (sharedUi `ForegroundState.kt`) is true, so coming back to the app claims again and reloads.

#### State the UI uses

`musicVideoState: StateFlow<MusicVideoState>` is either `Loading`, `Showing` or `None`. It is `None` when:

*   no H.264 stream exists;
*   the aspect ratio is below 1.2;
*   the stream failed;
*   the route is AirPlay (`AVAudioSessionPortAirPlay` in `currentRoute.outputs`).

Video mode, the spinner and landscape all key on this state, so a video with no picture behaves like a song: artwork and no rotation.

#### Quality and requests

*   **Auto:** 1080p on Wi-Fi; 480p when `IosNetwork.metered` is true or null.
*   **A chosen height:** stored in `IosSettings.musicVideoHeight`, where 0 means Auto. Changing it is debounced by 500 ms, then reloads at the current position.
*   **Stream cache:** resolved streams are kept 1 hour, at most 8. This covers coming back after the grace period, a rotation, or switching back to a height that was used before.
*   **Request cost:** one `player` request per video play, plus at most 2 codec retries. Quality changes are debounced. Error retries are capped at 3 clients per video.

#### Errors

All error rules are copied from upstream ([context](context/upstream_MusicVideoPlayer.kt), lines 372-420 and 533-545).

*   **HTTP 403, 404 or 410.** The status is read from `AVPlayerItem.errorLog().events.last.errorStatusCode`. That client goes into the engine's own `refused` map for this video, which is passed as `skipClients`, and `onVideoRefused()` refreshes the cipher. The cap is `MAX_REFUSED_CLIENTS = 3`, shared with the codec retries.
*   **`AGE_RESTRICTED` or `NO_PLAYABLE_STREAM`** (`ESCALATED_REASONS`). Retry once as age-restricted, and remember the id in `ageGated`.
*   **Other resolve errors.** Except network errors, these mark the video `None` until `sessionGeneration` changes. Network errors and unexpected exceptions are not remembered.
*   **The audio is never affected.** The engine never calls `InnerTubeXResolver.exclude()` or `onRefused()`, so a video refusal can't block that client for the song's audio, which has the same id.

#### Transport

*   The asset is built the way `IosAudioEngine.kt` builds the audio's: an `AVURLAsset` with `AVURLAssetHTTPHeaderFieldsKey`.
*   No chunking is planned. Phase 0 checks whether googlevideo throttles 1080p over a single connection. If it does, the fallback is an `AVAssetResourceLoaderDelegate` fetching 1 MiB ranges, as upstream's `ChunkedDataSource` does.

### 3. Rotation

*   **iPhone.**
    *   `iosApp/project.yml` adds `UIInterfaceOrientationLandscapeLeft` and `UIInterfaceOrientationLandscapeRight`.
    *   A Swift `AppDelegate`, attached with `@UIApplicationDelegateAdaptor`, answers `supportedInterfaceOrientationsFor` from one Kotlin export, `MainViewControllerKt.supportedInterfaceOrientationsMask()`.
    *   That export returns portrait, unless `IosOrientation.allowLandscape` is set. The flag is set only while Now Playing is open and `musicVideoState == Showing`.
    *   While the flag is set, turning the phone rotates the window. Shared Now Playing treats a window wider than it is tall as landscape (`landscapePlayerAvailable`), and in video mode it shows `FullscreenVideoPlayer`.
    *   The Fullscreen button asks for landscape with `requestGeometryUpdate` (iOS 16), which works even with rotation lock on. Exit asks for portrait.
    *   When the flag clears, the app calls `setNeedsUpdateOfSupportedInterfaceOrientations` on the key window's `rootViewController` (the SwiftUI host) and asks for portrait.
*   **iPad** (owner-accepted upstream tablet behaviour).
    *   The export returns all orientations.
    *   There are no geometry requests, and the Fullscreen and Exit buttons are hidden.
    *   An iPad window that is wider than it is tall, including Split View and Stage Manager windows, shows the full-screen video for video rows, as Android tablets do. The two-column landscape layout stays for songs.
    *   iPad is detected with `UIDevice.currentDevice.userInterfaceIdiom`.
*   **Shared code.**
    *   `ScreenOrientation` / `rememberScreenOrientation()` is copied from upstream. Its iOS actual calls an `OrientationController`, an interface in the same file that iosKit installs.
    *   Hiding the status bar uses the existing `expect fun HideStatusBar(hidden)` (`StatusBarIcons.kt`). Its iOS actual is a no-op today. It becomes a stack of requests that sets `statusBarHidden` on `UIApplication` through key-value coding, the same way `applyStatusBarStyle` sets the style, because `UIViewControllerBasedStatusBarAppearance` is false.
    *   **Side effect:** the existing "Hide status bar" player setting starts working on iOS.

### 4. Now Playing UI (sharedUi, copied from upstream)

*   **`PlayerHost` hooks**, each with a default, so other platforms are unaffected:
    *   `val musicVideoSupported: Boolean` (default false);
    *   `@Composable fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier, priority: Int = 1)` (default does nothing). The `priority` parameter is new on top of upstream's signature.
    *   `val musicVideoAspect: StateFlow<Float>` (default 16/9);
    *   `val musicVideoQuality: StateFlow<MusicVideoQualityUi>` (default empty);
    *   `fun setMusicVideoQuality(height: Int?)` (default does nothing);
    *   `val musicVideoState: StateFlow<MusicVideoState>` (default `None`). This hook is new on top of upstream's set.

    `showsMusicVideo(song)` becomes `song.isVideo && musicVideoSupported && musicVideoState != None`.
*   **`NowPlayingScreen` video mode.** Copied from the upstream diff:
    *   no animated cover, no hero, and a blurred-artwork backdrop;
    *   a card with rounded corners where the sleeve would be, with no shrink when paused;
    *   the artwork with a spinner until the first frame;
    *   quality and Fullscreen buttons in the corner;
    *   a 16:9 collapsed thumbnail;
    *   the card hidden while collapsing or docking.
*   **`MusicVideoLayout.kt`:** copied.
*   **Queue rows:** 16:9 artwork for videos, through a new `trackArtSize(base, isVideo)` that returns `base` tall and `base × 16/9` wide.
*   **Mini player** (shared `ui/components/MiniPlayer.kt`, which iOS uses): its artwork slot animates to 16:9 for videos and shows `MusicVideoSurface(fit = false, priority = 0)`.
*   **Already in the port** (not new): the stats line "Automix not supported on video songs" (`PlayerControls.kt`, `automix_not_supported_video`).

### 5. Switching between song and video, and the playback rules

*   **`IosAudioEngine.swapStream` gains `mapPosition: ((Long) -> Long)?`.** When it is set, the incoming deck's position comes from `mapPosition(active position)`. This is worked out where `inStep()` runs, so it uses the live position at the moment of the swap and ignores `offsetMs`. The fallback `engine.load` in `IosPlayer.switchVersion` uses `mapPosition(position)` the same way.
*   **`switchVersion` order:**
    1.  the segment map, `YtMusicRepository.versionTimeMap(from, to)`, passed as `mapPosition`;
    2.  otherwise `IosVersionAligner`'s fixed offset, when Smart alignment is on;
    3.  otherwise 0.

    The `versionAligning` indicator only shows for case 2.
*   **The picture during a switch.** The video side resolves only after `IosPlayer.song` changes. It is never resolved ahead of the swap, which keeps the dependency one-way. See CUJ 4 for what the user sees.
*   **History:** already correct. `IosListeningRecorder.switched(fromId, toId)` carries the listen across a switch, and `switchVersion` already calls it.
*   **Menu** (`IosMenus.kt`): the row is greyed out, not hidden, once a lookup has found nothing (the `NotFound` marker). The message becomes "No video version found for this song".
*   **Rules:**
    *   add-on sources and quality upgrade already skip `isVideo` rows;
    *   **new:** `IosPlayer.automixTransition` falls back to a plain crossfade when either track's `isVideoOrigin` is true;
    *   `IosAutomixAnalysis.request` skips video rows.
*   **`preferMusicOnly`** (owner's decision: keep it as it is). It defaults to off. When the user turns it on, video rows are converted to the song before they play, and no picture shows unless they tap "Convert to video".

## Alternatives Considered

### Alternative A: One AVPlayer plays both sound and picture (a muxed stream)

Play YouTube's combined audio+video format (itag 18, 360p) or the HLS variant through one AVPlayer, and skip the audio engine for video rows.

*   **Pros:** One clock, so no drift and no sync loop.
*   **Cons:**
    *   Combined progressive streams stop at 360p.
    *   Video rows would lose crossfade, the FFmpeg-path EQ and loudness, skip-silence and the 550 ms version blend.
    *   They would also need their own copy of the B0 call and route handling.
*   **Reason for rejection:** It splits the audio path in two and caps the picture at 360p. A muted follower player costs one sync function, and upstream's Android and web players already work this way.

### Alternative B: Only the Fullscreen button rotates

*   **Pros:** No app delegate, and nothing rotates by accident.
*   **Cons:** It drops "turn sideways to go full screen" (Goal 2), so iPhone behaves differently from Android.
*   **Reason for rejection:** Matching Android is worth one small Swift delegate and one mask function.

### Alternative C: Apple's full-screen player (AVPlayerViewController)

*   **Pros:** iOS handles rotation and gestures, and picture-in-picture and AirPlay video would need little extra work later.
*   **Cons:**
    *   Its controls would drive the muted video player, not the music. They would have to be hidden and BitChord's controls drawn on top.
    *   The look would differ from Android's.
    *   Sync would have to cover a second surface.
*   **Reason for rejection:** Hiding and replacing its controls costs about as much as the shared `FullscreenVideoPlayer`, but gives up matching Android.

### Alternative D: YouTube's HLS manifest for the picture

AVPlayer plays HLS natively, adapts to the network, and YouTube's HLS variants are H.264.

*   **Pros:** No codec check, no retries, and quality adapts automatically.
*   **Cons:**
    *   `InnerTubeXResolver` turns HLS off.
    *   Only some clients get HLS, and they need a PoToken (YouTube's proof-of-origin token).
    *   The variants include audio, so more data is used.
    *   Seeking to an exact frame is slower than with a progressive file. This is not measured.
    *   No BitChord platform uses HLS for video.
*   **Reason for rejection:** It is the second fallback if Phase 0 fails (see Open Questions). It is not the first choice, because of the extra data and the unmeasured seek cost.

### Alternative E: Keep waveform alignment only, and skip the segment map

*   **Pros:** No new parsing.
*   **Cons:**
    *   It takes up to 20 s and needs both streams.
    *   It gives one offset for the whole song, which can't represent a skit in the middle.
*   **Reason for rejection:** The segment map arrives in a `next` response that is already being fetched. Alignment stays as the fallback.

### Alternative F: Slave the video to a shared host-time clock

Use `AVPlayer.setRate(_:time:atHostTime:)`, `sourceClock`, or `AVSampleBufferRenderSynchronizer`, so that iOS keeps the picture and the sound in step without a polling loop.

*   **Pros:** Sync to the frame, with no 250 ms loop.
*   **Cons:**
    *   It only works if the audio runs on an AVFoundation timebase that both players share. The FFmpeg/Opus deck plays through `AVAudioEngine` and has none.
    *   Adopting it would mean rewriting the audio engine.
*   **Reason for rejection:** It conflicts with "the audio path doesn't change". The rate-nudging loop already holds within 25 ms.

## Risks & Mitigations

| Risk | Failure mode | Mitigation |
| --- | --- | --- |
| H.264 missing | Some videos come only as VP9 or AV1, which AVPlayer can't play | Check the codec and try other clients; Phase 0 go/no-go (at least 18 of 20); ordered fallbacks in Open Questions; `None` state shows the artwork |
| Output delay | On AirPods the picture runs ahead by 150-250 ms | Subtract `heardLatencyMs` from the target; hide the picture on AirPlay; on-device AirPods check |
| Skip-silence | The audio jumps or speeds through gaps while the video lags | `inGap` makes the video hold; `seeks` is bumped at the end of each gap |
| Audio harmed by the video | A video refusal blocks a client for the audio, or the video player touches the audio session | Separate `refused` map, never `exclude()`; the player is muted, has no audio track and no now-playing session |
| Data use | 1080p is about 3-5 Mbit/s, roughly 25-40 times the audio | Stream only while claimed; 4 s grace; immediate unload in the background; 480p when metered or unknown |
| Battery and screen | A second decoder runs, and the screen stays awake while browsing (live mini player) | Unload in the background; `preventsDisplaySleepDuringVideoPlayback` only while rendered and playing; cost stated in NFRs |
| googlevideo throttling | 1080p stalls on a single connection | Phase 0 stall check; fallback is a 1 MiB range loader |
| YouTube request volume and bot checks | Extra `player` requests per play | One `next` cache shared with the menu and lyrics; at most 2 codec retries; debounced quality changes |
| Orientation stuck | The app is left sideways after a video | The flag clears when Now Playing closes or the state leaves `Showing`, followed by `setNeedsUpdate…` and a portrait request |
| Privacy and security | — | Same clients and headers as audio; no new endpoints or tracking |

## Non-Functional Requirements

*   **Data.** See Goal 4. With the live mini player (owner's decision), a video row keeps streaming the whole time the app is open in the foreground, even outside Now Playing. Auto caps this at 1080p on Wi-Fi and 480p otherwise.
*   **Battery and screen.** One extra hardware H.264 decoder runs while a picture is claimed. `preventsDisplaySleepDuringVideoPlayback` follows `rendered && playing`, so the screen stays awake while a video plays in the mini player too (owner-accepted).
*   **Audio safety (critical).** The video player:
    *   never starts, pauses, ducks or interrupts the audio;
    *   is muted;
    *   plays a stream with no audio track;
    *   is never given a now-playing session.

    A video failure never reaches `IosPlayer` or the B0 interruption handling. During a call the video pauses only because the audio clock stopped.
*   **Rollout and rollback.** Everything sits behind `IosPlayerHost.musicVideoSupported`. Setting it to `false` and shipping a build restores today's behaviour, landscape included. That is accepted, because the IPA is rebuilt by CI in about 25 minutes.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

Work is on branch `ios-port`. Each phase ends with every command below passing, plus that phase's tests:

*   `./gradlew :shared:compileKotlinIosArm64 :sharedUi:compileKotlinIosArm64 :iosKit:compileKotlinIosArm64 --offline`
*   `./gradlew :shared:compileKotlinJvm :sharedUi:compileKotlinJvm --offline` (desktop)
*   `./gradlew :app:compileDebugKotlin --offline` (Android, because the shared `expect` declarations and `InnerTubeXResolver` change)

Each phase is one commit.

### Phase 0: H.264 and throughput check (go/no-go)

#### File 4 (InnerTubeXResolver)

As in Phase 1 below.

#### `iosKit/.../ios/Autoplay.kt` (Modify; temporary block, removed after Phase 0)

*   **Sample.** A `MUSIC_VIDEO_SAMPLE` list of 20 video ids:
    *   10 official music videos (OMV) and 10 user uploads (UGC);
    *   in each group, 5 uploaded before 2015 and 5 after 2020.
    *   The ids are picked at the start of Phase 0 with `YtMusicRepository` VIDEOS searches and written into the list. They are also recorded in the appendix below.
*   **Codec check.** For each id, call `extractVideo(id, 1080, requireCodecPrefix = "avc1")` and log `video-probe: id client codec height`.
*   **Stall check.** For 3 of the ids, play the 1080p stream muted for 60 s and log every stall longer than 1 s.

#### `.github/workflows/ios.yml` (Modify, temporary)

Grep the `video-probe` lines into the job summary.

#### Decision

*   **Go:** at least 18 of 20 ids return `avc1` from one of the first 3 clients tried, **and** no 1080p stall is longer than 1 s.
*   **Codec check fails:** stop, and take the Open Questions fallbacks to the owner.
*   **Only the stall check fails:** go, and add the range loader to Phase 4.

### Phase 1: Shared model, parser, repository, resolver

#### 1. `shared/src/commonMain/kotlin/com/music/bitchord/data/model/VersionTimeMap.kt` (Create)

Copy [upstream_VersionTimeMap.kt](context/upstream_VersionTimeMap.kt) unchanged.

#### 2. `shared/src/commonMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt` (Modify)

*   Port `parseVersionPair` and `timeMapOf` from [the copy](context/upstream_InnertubeParser.kt) (lines 1380-1420).
*   Port the ATV check and `fits()` from `parseWatchQueue` (lines 1285-1300).
*   Use the JSON paths given in §1.

#### 3. `shared/src/commonMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt` (Modify)

*   `nextCached(videoId)`: a `LockedMap<String, Pair<Long, JsonObject>>`, kept 10 minutes, 32 entries. `songMenu` and `officialVersion` use it.
*   `officialVersion` and `versionTimeMap` as in §1. The time-map `LockedMap` is keyed `"$from>$to"`.
*   `resolveAudio` and `resolveVideo` call `officialVersion` first.
*   `videoVersionCache` and `audioVersionCache` store `VersionLookup.Found(song)` or `VersionLookup.NotFound`.

#### 3b. `shared/src/commonMain/kotlin/com/music/bitchord/data/lyrics/YouTubeLyrics.kt` (Modify)

Read `next` through `YtMusicRepository.nextCached`. If the shared module layering doesn't allow that, keep the direct call and note it here.

#### 4. `shared/src/commonMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt` (Modify)

*   Port `VideoStream`, `extractVideo`, `onVideoRefused` and `sessionGeneration` from [the copy](context/upstream_InnerTubeXResolver.kt).
*   Add the `requireCodecPrefix: String? = null` and `budget: Int = 3` parameters, with the codec loop described in §1.
*   **Pseudocode:**
    *   `skip = skipClients.toMutableSet()`
    *   repeat up to `budget` times:
        *   `s = extract(…, excludedClients = excludedFor(id) + skip)`
        *   if `s.videoUrl == null`, return null
        *   if `requireCodecPrefix == null` or `s.videoCodecs` starts with it, return `VideoStream(s)`
        *   otherwise log, then `skip += s.profileId`
    *   return null
*   `excludedFor(id)` is read but never written here.
*   Bump `sessionGeneration` in `onSessionChanged()`.

#### 5. `shared/src/commonTest/kotlin/com/music/bitchord/data/innertube/VersionPairTest.kt` (Create)

*   Use the fixtures from [the copy](context/upstream_VersionPairTest.kt), moved to commonTest.
*   It covers:
    *   the video-first pair;
    *   the counterpart-first pair, giving an inverted map;
    *   a 12 s intro.

#### 5b. `shared/src/commonTest/kotlin/com/music/bitchord/data/model/VersionTimeMapTest.kt` (Create)

It covers:

*   a skit in the middle;
*   a position past the end;
*   that inverting twice gives back the original map.

#### 5c. `shared/src/commonTest/kotlin/com/music/bitchord/data/innertube/ExtractVideoCodecTest.kt` (Create)

*   It drives the codec loop with a fake extractor (the loop is factored as an internal function that takes `suspend (Set<String>) -> ExtractedStream?`).
*   Cases:
    *   avc1 first time;
    *   VP9 then avc1;
    *   three VP9 results, returning null;
    *   `exclude()` is never called.

**Tests for this phase:** `./gradlew :shared:jvmTest --tests '*VersionPair*' --tests '*VersionTimeMap*' --tests '*ExtractVideoCodec*'`.

### Phase 2: PlayerHost hooks and the layout (sharedUi)

#### 6. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt` (Modify)

*   Add the hooks, with the signatures and defaults listed in §4.
*   Add `enum class MusicVideoState { Loading, Showing, None }`.
*   Copy `MusicVideoQualityUi`, `videoQualityLabel`, `showsMusicVideo` (as changed in §4) and `MusicVideoSurface` (with `priority`) from [the copy](context/upstream_PlayerHost.kt).

#### 7. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/MusicVideoLayout.kt` (Create)

*   Copy [the copy](context/upstream_MusicVideoLayout.kt).
*   Haze is already common, so it needs no change.
*   Replace the call to `HideSystemBars(true)` with the existing `HideStatusBar(true)`.
*   When `isPad` is true, hide Fullscreen and Exit. `isPad` comes from a new `PlayerSettingsSource.tabletLayout`.

#### 8. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` (Modify)

*   Apply the video-mode hunks from [upstream_NowPlayingScreen.diff](context/upstream_NowPlayingScreen.diff), using the carry-over procedure from the Overview.
*   While `musicVideoState == Loading`, the card shows the artwork with a spinner.

#### 9. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt` and `ui/components/MiniPlayer.kt` (Modify)

*   **Queue:** add `trackArtSize` and use it at the artwork `.size(44.dp)`.
*   **Mini player:**
    *   the artwork slot at `.size(40.dp)` animates to 71×40 dp for videos;
    *   it shows `MusicVideoSurface(fit = false, priority = 0)`.

#### 12. `sharedUi/src/commonMain/composeResources/values/strings.xml` (Modify)

*   Add `video_quality`, `video_quality_auto` and the fullscreen/exit keys under upstream's names, as found in the copied layout.
*   `automix_not_supported_video` already exists and is not added again.

### Phase 3: Orientation and status bar

#### 10. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/theme/ScreenOrientation.kt` (Create)

*   Copy [the copy](context/upstream_ScreenOrientation.kt).
*   Add `interface OrientationController { fun landscape(); fun portrait() }` and `var orientationController: OrientationController?`.
*   Add actuals for `androidMain` (copied from [.android.kt](context/upstream_ScreenOrientation.android.kt)) and `jvmMain` (does nothing).

#### 11. `sharedUi/src/iosMain/kotlin/com/music/bitchord/ui/theme/ScreenOrientation.ios.kt` (Create) and `StatusBarIcons.ios.kt` (Modify)

*   **`ScreenOrientation.ios.kt`:** the actual forwards to `orientationController`.
*   **`StatusBarIcons.ios.kt`:** `HideStatusBar` becomes a stack of requests. It sets `UIApplication.sharedApplication.setValue(hidden, forKey = "statusBarHidden")`, using the same key-value pattern as `applyStatusBarStyle`.

#### 15. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosOrientation.kt` (Create)

*   `object IosOrientation : OrientationController` with `allowLandscape`.
*   It requests geometry with `windowScene.requestGeometryUpdate(UIWindowSceneGeometryPreferencesIOS(mask))`, on iPhone only.
*   When the flag clears, it calls `setNeedsUpdateOfSupportedInterfaceOrientations` on the key window's root view controller.
*   `fun mask(): ULong`: on iPad, all orientations; on iPhone, portrait plus landscape when the flag is set, otherwise portrait.

#### 16. `iosKit/.../ios/IosNowPlaying.kt` and `MainViewController.kt` (Modify)

*   **`IosNowPlaying`:** a `DisposableEffect`, keyed on the player being open and `musicVideoState == Showing`, sets and clears `IosOrientation.allowLandscape`.
*   **`MainViewController.kt`:**
    *   installs `orientationController = IosOrientation`;
    *   exports `fun supportedInterfaceOrientationsMask(): ULong = IosOrientation.mask()`.

#### 17. `iosApp/iosApp/BitChordApp.swift` and `iosApp/project.yml` (Modify)

*   **`BitChordApp.swift`:** an `AppDelegate` attached with `@UIApplicationDelegateAdaptor`. Its `supportedInterfaceOrientationsFor` returns `UIInterfaceOrientationMask(rawValue: UInt(MainViewControllerKt.supportedInterfaceOrientationsMask()))`.
*   **`project.yml`:** the iPhone `UISupportedInterfaceOrientations` gains `UIInterfaceOrientationLandscapeLeft` and `UIInterfaceOrientationLandscapeRight`.

#### Tests

`IosOrientationMaskTest` in `iosKit/src/iosTest` checks the mask for each combination of idiom and flag. The `mask()` logic takes `isPad` as a parameter so it can be tested.

### Phase 4: The video engine

#### 18a. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosAudioEngine.kt` (Modify)

*   Add `heardLatencyMs`, `heardRate` and `inGap`, as in §2.
*   `endGap` and the FFmpeg skip accounting bump `position.seeks`.
*   Add `swapStream(…, mapPosition)`, as in §5.
*   **Device check during this phase:** with AirPods, decide whether the AVPlayer deck's `currentTime` already includes `outputLatency`, using the clap test. Record the answer in a code comment.

#### 13. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosMusicVideo.kt` (Create)

*   It implements §2: the player, claims, hand-off, the 4 s grace period, unloading in the background, `musicVideoState` including the AirPlay rule, quality and the stream cache, errors, and the transport.
*   `IosCanvasVideo.kt`: `CanvasPlayerView` becomes `internal`.
*   The Compose wrapper is `IosMusicVideoSurface(videoId, fit, onRenderedChanged, modifier, priority)`. It uses `UIKitView` with no interaction, and claims only while `rememberIsForeground()` is true.

#### 13b. `sharedUi/src/commonMain/kotlin/com/music/bitchord/playback/MusicVideoSync.kt` (Create)

*   The pure `syncStep` and its constants, as in §2.
*   A test in `sharedUi/src/commonTest/.../MusicVideoSyncTest.kt`, one case per branch:
    *   paused under and over 40 ms;
    *   in a gap;
    *   drift under 25 ms;
    *   drift of 100 ms ahead and 100 ms behind (rate clamped);
    *   drift over 450 ms, inside and outside the cooldown;
    *   the seek lead adapting and staying within 0-1500 ms.

#### 14. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosPlayerHost.kt` (Modify)

The hooks are wired to `IosMusicVideo`, with `musicVideoSupported = true`.

#### 20. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosSettings.kt` (Modify)

*   Add `IntPref("${PREFIX}music_video_height", 0)`, with no settings row.
*   `preferMusicOnly` is unchanged.

### Phase 5: Switching, Automix, menus

#### 18. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosPlayer.kt` (Modify)

*   `switchVersion` follows the order in §5, using `mapPosition`, including in the fallback `load`.
*   `automixTransition` returns false when either track's `isVideoOrigin` is true.
*   `IosAutomixAnalysis.request` is not called for `isVideo` rows.
*   The "No video version found for this song" message.

#### 19. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosMenus.kt` (Modify)

*   The Convert row is greyed out (disabled `MenuRow`) when the lookup is `NotFound`.
*   It stays hidden in Listen Together, for source tracks and for files on the phone, as it is today.

### Phase 6: Smoke test and docs

#### 21. `iosKit/.../ios/Autoplay.kt` and `.github/workflows/ios.yml` (Modify)

*   **Video check (Autoplay.kt).** It runs after the existing interruption check, which finishes about 32 s in. It plays a `Song(isVideo = true)` using the first id from the Phase 0 sample. The smoke run is signed out, so `extractVideo` is used directly and no version pair is involved.
*   **Logging.**
    *   Every 2 s for 20 s it logs `video: state=… rendered=… drift=…ms`, where drift is measured against the sync target.
    *   It then logs `video: background-ready`.
*   **Backgrounding (ios.yml).**
    *   After `background-ready`, the job runs `xcrun simctl launch booted com.apple.Preferences`.
    *   The app logs `video: unloaded` when `unload()` runs.
*   **Job assertions (ios.yml).**
    *   at least one `rendered=true`;
    *   the median absolute drift after the first 5 s is ≤ 60 ms, computed with `awk` over the `drift=` values;
    *   `unloaded` appears within 1 s of the launch timestamp.
*   **Screenshots.**
    *   one of the video card;
    *   one of full screen, forced with `requestGeometryUpdate`.

#### 22. `docs/designs/music-videos.md` (Modify) and `docs/IOS_PORT.md` (Modify)

*   Mark this design Implemented, and record the Phase 0 results.
*   Add a note to IOS_PORT.md on the video engine and the orientation flag.

### Verification Plan

*   **Build:** the three compile commands at the top of this plan, for every phase.
*   **Unit tests:**
    *   `./gradlew :shared:jvmTest :sharedUi:jvmTest --offline` covers `VersionPair`, `VersionTimeMap`, `ExtractVideoCodec` and `MusicVideoSync`.
    *   `IosOrientationMaskTest` runs in the iOS simulator test task in CI.
*   **Static analysis:** no linter is configured. Compiler warnings are reviewed and don't fail the build.
*   **End to end:** dispatch `ios.yml` with `{"app":true,"smoke":true,"release":true}`. The Phase 6 assertions must pass, and the screenshots are published to the `ios-latest` release.
*   **On device (owner), with pass marks:**
    *   CUJs 1-7.
    *   **Sync:** a clap or drum-hit cut lines up within 80 ms by eye, on the speaker and on AirPods.
    *   **Rotation:** works with the rotation lock on (button only) and off (button and turning the phone).
    *   **Call:** during a call the audio pauses and the picture holds; both resume after the call.
    *   **Heat and battery:** after 15 minutes of video the phone is not hot to the touch, and the battery drain is no more than 10% over 15 minutes at full brightness on Wi-Fi.

### Appendix: Phase 0 sample

These are the rules for choosing the sample. The ids are filled in when Phase 0 runs.

| Group | Count | Rule |
| --- | --- | --- |
| OMV, before 2015 | 5 | Official music videos from a VIDEOS search for 5 well-known artists |
| OMV, after 2020 | 5 | The same search, taking newer uploads |
| UGC, before 2015 | 5 | Fan uploads and lyric videos, `MUSIC_VIDEO_TYPE_UGC` |
| UGC, after 2020 | 5 | The same, taking newer uploads |

--------------------------------------------------------------------------------

*Drafted with EGM*
