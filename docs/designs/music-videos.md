# Music videos in Now Playing (iOS)

*   **Author:** jugaltaneja111
*   **Status:** Approved
*   **Date:** 2026-11-01

--------------------------------------------------------------------------------

## Overview

BitChord v1.9 on Android shows a music video's picture in Now Playing.

*   **The sound.** The audio player plays the video's own soundtrack, the same way it plays any track.
*   **The picture.** A second player, muted and with its audio track turned off, draws the picture.
*   **Keeping them together.** The picture follows the audio player's clock:
    *   When it drifts by more than 25 ms, its playback speed is raised or lowered by up to 10% until it catches up.
    *   When it drifts by more than 450 ms, it jumps straight to the audio's position.
*   **Switching between the song and its video.** YouTube Music gives a "segment map" that says which part of the video matches which part of the song. Upstream's `VersionTimeMap` reads it, so the other version picks up at the same moment in the music.

The iOS port has none of this. A video row plays as audio over its 16:9 thumbnail.

This design does two things:

*   It copies the shared parts from upstream:
    *   the time map;
    *   the parsing of song/video pairs;
    *   `extractVideo`, which fetches a video stream;
    *   the fullscreen and quality screens.
*   It rebuilds the Android-only parts in iosKit:
    *   the second, muted player kept in sync with the audio;
    *   screen rotation;
    *   hiding the status bar.

## The Problem

*   **Video rows look and sound like songs.** OMV (official music video) and UGC (user upload) rows play exactly like songs, so the reason to pick a video is lost.
*   **"Convert to video" only changes the audio.** The iOS menu row already exists. To line the two versions up, it compares the first 45 s of their waveforms (`IosVersionAligner`). That comparison:
    *   needs both streams to be downloaded;
    *   gives up after 20 s;
    *   fails on remixes and on long spoken intros.

    YouTube's own segment map would be exact and available at once whenever the user is signed in.
*   **No full screen on iPhone.** The iPhone build is locked to portrait (`iosApp/project.yml`), so there is no way to watch a video full screen.

Risks this design has to handle:

*   **Codecs.**
    *   iOS's built-in player (AVPlayer) cannot play VP9 in WebM files.
    *   AV1 is decoded only on A17 Pro / M3 chips and later.
    *   InnerTubeX, the library that picks the stream, ranks VP9 the same as H.264.

    So iOS has to require the H.264/MP4 (`avc1`) streams, which YouTube offers up to 1080p.
*   **Data.** A 1080p video stream runs at about 3–5 Mbit/s. The audio alone is about 128 kbit/s, so video can use 25–40 times the data.
*   **Battery and heat.** A second decoder runs whenever the picture is on screen.
*   **Two clocks.** The audio player and the video player are separate, so they drift apart. Skip-silence on the audio path also makes the audio jump, and the picture must follow each jump.

## Goals

1.  **Picture in Now Playing.** When a video row plays and Now Playing is open, the picture shows in a card.
    *   The card uses the video's own shape, between 1:1 and 2.4:1 wide. Most videos are 16:9.
    *   After about 1 s of settling, the picture stays within 25 ms of the audio.
2.  **Full screen.** Turning the phone sideways, or tapping the Fullscreen button, shows the video full screen in landscape.
    *   The status bar is hidden.
    *   Tapping shows the controls, and they hide again after 3.5 s of playing.
    *   Turning the phone upright goes back to the normal player.
3.  **Switching between song and video.** Song↔Video switching uses YouTube's segment map when there is one, and the existing waveform alignment when there isn't. Either way:
    *   playback continues at the same moment in the music;
    *   the switch uses the existing 550 ms crossfade.
4.  **Data only while the picture is visible.** The video stream is open only while a picture is on screen. When the app goes to the background, the screen locks or the player is closed, the video is released after 4 s. The audio keeps playing, and data use falls back to the audio alone.
5.  **Quality.** The user can choose Auto or a fixed height up to 1080p.
    *   On Wi-Fi, Auto means 1080p.
    *   On a metered connection, Auto means 480p.
    *   A height the user picks is remembered for later videos.
6.  **Video shape elsewhere.** A video's row in the queue, and its picture in the mini player, use the 16:9 shape.
7.  **Same rules as Android.**
    *   Video rows always stream from YouTube. They are not offered to add-on sources or to the quality upgrade.
    *   Automix is off for them.
    *   During Listen Together, the version can't be switched.

## Non-Goals

*   Picture-in-picture, or video on the lock screen or in CarPlay.
*   VP9, AV1, or anything above 1080p.
*   Saving videos for offline use, or caching them on disk. Downloads stay audio-only.
*   Sending video to an AirPlay screen. Audio-only AirPlay keeps working as it does now.
*   Video stations, where a queue started from a video continues with more videos. Upstream has these; they can follow later.
*   The desktop and web players.

## Open Questions

*   **H.264 coverage.** `ContentHints` in InnerTubeX v0.7.0 can't choose a codec. Its fields are `wantVideo` and `maxVideoHeight`, and nothing about codecs. `selectBestVideoFormat` treats H.264 and VP9 as equal. The plan therefore checks the codec of each result and asks the next client when it isn't H.264.
    *   Unknown: how often no client offers H.264.
    *   Step 0 of the implementation measures this on 20 sample videos in the simulator.
    *   If it happens often, the fallback is a codec option added to InnerTubeX, upstream or in a fork. That would change the library dependency, so it goes back to the owner.
*   **Signed-out users.** When signed out, the `next` response has no song/video pairs. Switching then falls back to search plus waveform alignment, as it does today. Is that acceptable?

## Critical User Journeys (CUJs)

### CUJ 1: Watch a music video

*   **Given**: A signed-in user on Wi-Fi with a video row in the queue.
*   **When**: The video row starts playing and Now Playing is open.
*   **Then**: A spinner on black shows first. Within about 2 s the video appears, in sync with the sound, and the audio plays without a gap.

### CUJ 2: Full screen

*   **Given**: A video is playing in Now Playing.
*   **When**: The user turns the phone sideways or taps Fullscreen.
*   **Then**: The video fills the screen in landscape with the status bar and controls hidden. A tap shows the controls. Turning the phone upright returns to the player.

### CUJ 3: Lock the phone

*   **Given**: A video is playing.
*   **When**: The user locks the screen.
*   **Then**: The audio keeps playing and the video stream is released after 4 s. When the user unlocks and opens Now Playing, the video resumes in sync.

### CUJ 4: Switch between song and video

*   **Given**: The catalogue song is playing.
*   **When**: The user taps "Convert to video" in the track's menu.
*   **Then**: The video version continues from the same moment in the music, using YouTube's segment map, or waveform alignment when there is no map. "Convert to audio" switches back the same way.

### CUJ 5: No video version exists

*   **Given**: A song with no video counterpart.
*   **When**: The user taps "Convert to video".
*   **Then**: The message "No video version found for this song" appears, playback continues, and the menu row is greyed out for that song.

### CUJ 6: On cellular

*   **Given**: A metered connection.
*   **When**: A video shows.
*   **Then**: It plays at 480p unless the user has picked a height.

## Technical Plan (Architecture)

### Shape of the solution

Nothing changes in how the sound is produced. A video row keeps playing through the current audio path:

*   `IosPlayer`, with YouTube as the only source (no add-ons and no quality upgrade);
*   the YouTube stream is either AAC through AVPlayer or Opus through FFmpeg.

The picture comes from a second, muted `AVPlayer` that plays YouTube's **video-only** stream. Video-only streams have no sound in them, so the muting is only a safeguard. This player follows `IosPlayer`'s clock and never drives it. The design copies upstream's Android `MusicVideoEngine` and the web `videoDeck`, which both work this way.

```text
           ┌──────────── shared (commonMain) ────────────┐
 next  ──▶ │ InnertubeParser.parseVersionPair             │
           │   └▶ VersionPair(song, VersionTimeMap?)      │
           │ YtMusicRepository.officialVersion /          │
           │   versionTimeMap(from,to)                    │
 player──▶ │ InnerTubeXResolver.extractVideo(id, maxH)    │──▶ VideoStream(url, w, h, codec, heights)
           └──────────────────────────────────────────────┘
                                 │
           ┌──────────── iosKit ─▼────────────────────────┐
           │ IosMusicVideo (object)                       │
           │   muted AVPlayer ◀── sync loop (250 ms) ──── │◀── IosPlayer.position / isPlaying / seeks
           │   surfaces: claim(view, priority) / release  │
           │   4 s grace → unload; background → unload    │
           │ IosOrientation (mask flag) ◀──── video mode  │──▶ Swift AppDelegate supportedInterfaceOrientationsFor
           └──────────────────────────────────────────────┘
                                 │ PlayerHost hooks
           ┌──────────── sharedUi ▼───────────────────────┐
           │ showsMusicVideo(song) → NowPlaying video card │
           │ FullscreenVideoPlayer, VideoCornerControls,   │
           │ VideoQualitySheet, 16:9 queue/mini art        │
           └──────────────────────────────────────────────┘
```

### 1. Shared code (copied from upstream, already common Kotlin)

*   **`VersionTimeMap` and `VersionPair`**: a new model file, copied as-is. It translates a position in one version into the matching position in the other:
    *   inside a matched segment the position maps exactly;
    *   a position before a segment (a spoken intro or a skit) maps to where the music starts;
    *   a position after the last segment maps to its end.
*   **`InnertubeParser.parseVersionPair` and `timeMapOf`** read the counterpart and its `segmentMap` from the `next` response. The numbers can arrive either as strings or as numbers.
*   **`YtMusicRepository`**: `officialVersion` asks `next` for the official pair. It returns nothing at once when the user is signed out, because signed-out responses carry no pairs. It stores the time map in both directions, and `versionTimeMap(from, to)` reads it back. `resolveAudio` and `resolveVideo` try `officialVersion` first and fall back to the search they use today.
*   **`InnerTubeXResolver.extractVideo`**: asks InnerTubeX for a video-only stream no taller than a maximum height, with these settings:
    *   `ContentHints(wantVideo = true, maxVideoHeight)`;
    *   no HLS and no SABR;
    *   bounded byte ranges.

    It returns a `VideoStream` holding the url, width, height, mime type and codec, headers, the available heights and the client profile. `onVideoRefused` and `sessionGeneration` are copied too.

    InnerTubeX v0.7.0 has no setting to choose the codec. `selectBestVideoFormat` ranks H.264 and VP9 equally and breaks the tie on bitrate. iOS therefore checks the codec of every result:
    *   `avc1` is accepted;
    *   for anything else, it asks again with that client profile excluded, up to the existing limit of 3 refused clients;
    *   if no client offers H.264, the video is marked "no picture" and the song plays over its artwork as it does today.

    Step 0 of the implementation measures how often this fallback happens (see Open Questions).

### 2. The video player and sync (iosKit, new `IosMusicVideo`)

One object owns one muted `AVPlayer`. Its job is to keep the picture on the same moment as the music.

*   **Which video it shows.** It watches `IosPlayer.current`:
    *   when the current track is a video row and at least one screen wants a picture, it resolves a stream and loads it;
    *   when the track changes to something that is not a video, it unloads.
*   **What it follows.** The sync loop runs every 250 ms, and also straight away whenever play or pause changes or a seek happens. It reads three things from `IosPlayer.position`:
    *   `positionMs` and `sampledAtNanos`, so it can work out exactly where the audio is at this moment;
    *   `seeks`, which goes up every time the audio jumps, including skip-silence jumps;
    *   whether the audio is playing.
*   **How it keeps the two together.** The rules and numbers are the same as Android's:
    *   **Audio paused:** the video pauses. If it is more than 40 ms out, it jumps to the exact frame, so dragging the scrubber shows the frame where it lands.
    *   **Small drift, 25 to 450 ms:** the video's speed changes in proportion to the drift, by at most 10%, so it catches up in about one second.
    *   **Large drift, over 450 ms:** at most once every 1.5 s, the video jumps to the audio position plus a short lead. The lead adapts to how long the last jump took to settle.
    *   The drift checks run only while the video player is actually playing.

    The video player is told not to wait for buffering on its own, so its speed follows the sync loop alone.
*   **Where the picture is drawn.** `IosMusicVideo` hands out the picture through a `UIView` whose layer is an `AVPlayerLayer`. The view is the same kind used by `IosCanvasVideo`.
    *   Each place that wants the picture claims it with a priority. The full player has priority 1 and the mini player 0. The layer moves to the claim with the highest priority.
    *   When the last claim is released, a 4 s timer starts. It covers a rotation or a sheet reopening.
    *   When the timer runs out, the player stops and empties its queue, so no more data is used.
    *   When the app goes to the background, the player unloads at once and doesn't wait for the 4 s.
*   **Quality.**
    *   Auto means 1080p on Wi-Fi, or 480p when `IosNetwork.metered` is true.
    *   A height the user chooses is kept in a new `IosSettings` value, where 0 means Auto. Choosing one reloads the stream at the current position.
    *   Video streams that have been resolved are kept in memory for 1 hour, at most 8 of them.
*   **Not a video after all.** A stream narrower than 1.2:1 is a catalogue song's still cover shown as a "video". It gets the "no picture" mark and is not shown.
*   **Errors.**
    *   HTTP 403, 404 or 410 from googlevideo marks that client as refused for this video, and the next client is tried.
    *   If a video is age-gated, the request is retried as age-restricted.
    *   Any other failure marks the video as "no picture" until the user signs in or out.

    The audio is never affected by any of these.

### 3. Rotation on iPhone ("Rotate with the phone", the owner's decision)

*   **App settings.** The iPhone build in `iosApp/project.yml` adds Landscape Left and Landscape Right to its allowed orientations. The iPad build already allows all four.
*   **Who decides.** iOS asks the app which orientations are allowed. A Swift `AppDelegate` answers that question with a mask read from a Kotlin flag, `IosOrientation.allowLandscape`. The delegate is attached to the existing SwiftUI app with `@UIApplicationDelegateAdaptor`.
    *   On iPhone the flag is false almost all the time, so every screen stays upright.
    *   It becomes true only while Now Playing is open and showing a video.
    *   iPad ignores the flag and keeps rotating freely.
*   **Turning the phone.** While the flag is true, turning the phone sideways rotates the window. Shared Now Playing already treats a window wider than it is tall as landscape (`landscapePlayerAvailable`), so with a video it shows `FullscreenVideoPlayer`.
*   **The button.** Fullscreen calls the iOS 16 `requestGeometryUpdate` for landscape. This works even with rotation lock on. Exit asks for portrait the same way.
*   **When the flag changes.** It is cleared when Now Playing closes or the track stops being a video. When that happens, the app calls `setNeedsUpdateOfSupportedInterfaceOrientations` and asks for portrait, so the rest of the app is never left sideways.
*   **Shared code.** `rememberScreenOrientation()` (the upstream interface) and `HideSystemBars(hidden)` get iOS `actual` implementations:
    *   `rememberScreenOrientation()` drives the flag and the geometry requests;
    *   `HideSystemBars(hidden)` sets a status-bar-hidden flag, which the same mechanism uses as `StatusBarIcons.ios.kt`.
*   **Rotation lock.** If rotation lock is on, iOS doesn't rotate when the phone turns. Only the button works.

### 4. Now Playing UI (sharedUi, copied from upstream)

*   **`PlayerHost` hooks:**
    *   `musicVideoSupported`, `false` by default;
    *   `MusicVideo(videoId, fit, onRenderedChanged, modifier)`;
    *   `musicVideoAspect`;
    *   `musicVideoQuality`;
    *   `setMusicVideoQuality(height)`.

    The helper `showsMusicVideo(song)` uses them.
*   **`IosPlayerHost`.** On iOS `musicVideoSupported` is true and the other hooks go to `IosMusicVideo`. Any platform without the hooks keeps behaving as it does today.
*   **`NowPlayingScreen` in video mode.** Copied from upstream:
    *   the animated cover is held back and the backdrop is the blurred artwork;
    *   the card has rounded corners and sits where the square sleeve would be;
    *   a black backdrop with a spinner shows until the first frame;
    *   the corner holds the quality box and the Fullscreen button;
    *   the collapsed header thumbnail is 16:9;
    *   the card is hidden while it collapses into lyrics, the queue or the mini player.
*   **New file.** `MusicVideoLayout.kt` holds `FullscreenVideoPlayer`, `VideoCornerControls`, `VideoQualitySheet` and `VideoLoadingBackdrop`.
*   **Elsewhere:**
    *   queue rows use 16:9 art for videos;
    *   the nerd-stats line says Automix is not supported for video songs.
*   **Mini player.** iOS draws its own mini player. Its artwork slot becomes 16:9 for videos and claims the picture at priority 0.

### 5. Switching between song and video, and the playback rules

*   **`IosPlayer.switchVersion`** looks for the offset in this order:
    1.  `YtMusicRepository.versionTimeMap(from, to)`, mapping the live position;
    2.  `IosVersionAligner`, when Smart alignment is on;
    3.  zero.

    A segment map gives a separate shift for each stretch of the song, so it is applied as "the target's start position is `map(position)`". It is not used as one fixed offset across the whole song. The existing 550 ms crossfade stays.
*   **The menu row.**
    *   It already exists and is already hidden in Listen Together.
    *   It is greyed out once a lookup has found nothing.
    *   Pairs and failed lookups are remembered for the session.
*   **Rules for video rows.**
    *   Add-on sources and the quality upgrade already skip them in `IosPlayer`.
    *   **New:** Automix also skips them. `IosAutomix` has no `isVideoOrigin` check today; it gets one, so the track change is a plain crossfade instead.
*   **History.** A switch partway through a track still counts as one listen. This matches upstream's `onVersionSwapped`.
*   **`preferMusicOnly`.** Its fate is an open question. If it stays, `musicOnly()` keeps changing video rows into songs before they play, and then this feature only shows up after the user taps "Convert to video".

## Alternatives Considered

There was no brainstorm brief. Alternatives B and C were the options turned down at the rotation decision. The others were weighed while drafting from the code.

### Alternative A: One AVPlayer plays both sound and picture (a muxed stream)

Play YouTube's combined audio+video format, itag 18 at 360p, or the HLS variant, through one AVPlayer. The audio engine is skipped for video rows.

*   **Pros**:
    *   Sound and picture can't drift apart, because there is only one clock.
    *   No sync loop is needed.
*   **Cons**:
    *   Combined progressive streams stop at 360p.
    *   Video rows would lose the features built on the audio engine:
        *   crossfade;
        *   EQ and loudness (the `IosAudioTap` tap works on AVPlayer, but the FFmpeg path does not);
        *   skip-silence;
        *   the 550 ms version blend.
    *   Pausing and calls would need a second set of handling, which is the B0 work done again.
    *   The audio path would differ from Android, and every audio bug would have to be fixed in two places.
*   **Reason for Rejection**: It splits the audio path in two and caps the picture at 360p. A synced muted player costs one sync loop, about 150 lines, which Android and the web have already tested.

### Alternative B: Only the Fullscreen button rotates (the owner rejected this)

The phone's sensor never rotates the app. Only the button asks for landscape, through `requestGeometryUpdate`.

*   **Pros**:
    *   It needs the least code, because no app delegate is involved.
    *   Nothing can rotate by accident.
*   **Cons**:
    *   It drops "turn sideways to go full screen" from Goal 2.
    *   The phone would behave differently from Android.
*   **Reason for Rejection**: The owner chose rotate-with-the-phone.

### Alternative C: Apple's full-screen player (AVPlayerViewController) (the owner rejected this)

*   **Pros**:
    *   iOS handles rotation, gestures and the system look by itself.
    *   Picture-in-picture and AirPlay video would be close to free later.
*   **Cons**:
    *   Its controls would drive the muted video player, not the music.
    *   So its controls would have to be hidden and BitChord's own drawn on top.
    *   The look would differ from Android's full-screen layout.
    *   Sync would have to cover two places where the picture is shown.
*   **Reason for Rejection**: The owner chose rotate-with-the-phone with BitChord's own full-screen layout.

### Alternative D: YouTube's HLS manifest for the picture

Use the `hlsManifestUrl` from the player response. AVPlayer handles HLS natively and adapts quality to the network. YouTube's HLS is H.264, which would also answer the codec question.

*   **Pros**:
    *   H.264 is guaranteed.
    *   Quality changes automatically with the network.
    *   No codec check and no retries.
*   **Cons**:
    *   `InnerTubeXResolver` turns HLS off (`allowHls = false`).
    *   YouTube only gives HLS to some clients, and it needs a PoToken.
    *   HLS variants include audio, so more data is downloaded than the picture needs.
    *   Seeking HLS to an exact frame for sync is slower than seeking a progressive stream.
    *   It is untested in BitChord on every platform.
*   **Reason for Rejection**: Kept as the fallback if Step 0 finds H.264 progressive streams missing too often. It is not the first choice, because no other platform uses it and it adds data use and seek delay.

### Alternative E: Keep waveform alignment only and skip the segment map

*   **Pros**: No new parsing.
*   **Cons**:
    *   Alignment takes up to 20 s and needs both streams downloaded.
    *   It fails on long intros.
    *   It gives one fixed offset for the whole song, which can't represent a video that inserts a skit partway through.
*   **Reason for Rejection**: The segment map is free with the `next` call already made when signed in. Waveform alignment stays as the fallback.

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Data use.**
    *   The video stream exists only while a picture is claimed, plus a 4 s grace period. In the background it is unloaded at once.
    *   Auto is capped at 480p on metered connections.
    *   Nothing about video is cached on disk.
*   **Battery and heat.**
    *   One extra hardware H.264 decoder runs only while the picture is on screen.
    *   The screen is kept awake only while frames are showing and the audio is playing. This uses AVPlayer's `preventsDisplaySleepDuringVideoPlayback`, set to follow `rendered && playing`.
*   **Audio safety (critical).**
    *   The video player must never start, pause, duck or interrupt the audio.
    *   It is muted, plays a stream with no audio track, and is never given a now-playing session.
    *   A failure to load or play video must not reach `IosPlayer` or the B0 interruption handling. During a call, the video pauses only because the audio clock has stopped.
*   **Privacy.**
    *   The same YouTube clients and headers as audio are used.
    *   No new endpoints or tracking.
*   **Rollout and rollback.**
    *   Everything turns on through `PlayerHost.musicVideoSupported`. Setting it to `false` in `IosPlayerHost` returns iOS to today's behaviour.
    *   The rotation flag defaults to portrait, so turning video off also turns off iPhone landscape.
*   **The "Prefer music only" setting is kept as it is (owner's decision).**
    *   It stays default off, as today (`IosSettings.preferMusicOnly`, key `prefer_music_only`).
    *   When it is on, video rows are converted to the song before they play, so no picture is shown unless the user taps "Convert to video".

--------------------------------------------------------------------------------

## Detailed Implementation Plan

Work is on branch `ios-port`. Each step ends with `:shared`, `:sharedUi` and `:iosKit` `compileKotlinIosArm64` passing.

### Step 0: Spike: H.264 coverage (no production files)

*   Add a temporary `Autoplay.kt` check, removed after the spike. It runs `extractVideo` for 20 video ids at heights 480, 720 and 1080, and logs the codec and the client for each.
*   Run it in the simulator through the existing `ios.yml` smoke job.
*   **Decision rule:**
    *   If at least 18 of 20 give `avc1` within 3 clients, carry on with the codec check.
    *   Otherwise, stop and bring Alternative D (HLS) or a fork of InnerTubeX to the owner.

### Target Files to Create/Modify

#### 1. `shared/src/commonMain/kotlin/com/music/bitchord/data/model/VersionTimeMap.kt` (Create)

*   **Type / Scope**: shared model.
*   **Proposed Changes**: Copy upstream's file unchanged. It contains:
    *   `class VersionTimeMap(segments)`, with `data class Segment(fromStartMs, toStartMs, durationMs)`, `map(positionMs): Long` and `inverted()`;
    *   `data class VersionPair(song: Song, timeMap: VersionTimeMap?)`.

#### 2. `shared/src/commonMain/kotlin/com/music/bitchord/data/innertube/InnertubeParser.kt` (Modify)

*   **Type / Scope**: shared parser.
*   **Proposed Changes**:
    *   Port `parseVersionPair(root, videoId, wantAudio): VersionPair?` and `timeMapOf(...)`, including the ATV check and `fits()`.
    *   `timeMapOf` accepts numbers sent as strings as well as plain numbers.

#### 3. `shared/src/commonMain/kotlin/com/music/bitchord/data/YtMusicRepository.kt` (Modify)

*   **Type / Scope**: shared repository.
*   **Proposed Changes**:
    *   Add `officialVersion(song, wantAudio)`, which returns at once when signed out.
    *   Add a time-map cache keyed by the pair, storing both directions. Guard it with the existing `SynchronizedObject` pattern, not `ConcurrentHashMap`.
    *   Add `versionTimeMap(fromId, toId)`.
    *   `resolveAudio` and `resolveVideo` call `officialVersion` before their search fallback.

#### 4. `shared/src/commonMain/kotlin/com/music/bitchord/data/innertube/InnerTubeXResolver.kt` (Modify)

*   **Type / Scope**: shared stream resolver.
*   **Proposed Changes**:
    *   Port `class VideoStream(videoId, url, width, height, mimeType, codecs, headers, heights, profileId)` with `aspect`.
    *   Port `extractVideo(videoId, maxHeight, skipClients, ageRestricted)`, `onVideoRefused()` and `sessionGeneration`.
    *   Add a parameter `requireCodecPrefix: String? = null`. When it is set and the result's `videoCodecs` doesn't start with it, retry with that client profile in `skipClients`, up to 3 clients, then return null. iOS passes `"avc1"`.
    *   Add a log line with the codec and client for each attempt.

#### 5. `shared/src/jvmTest/.../innertube/VersionPairTest.kt` (Create, copy)

*   **Type / Scope**: unit test.
*   **Proposed Changes**: Copy upstream's fixtures. They cover:
    *   the video-first pair;
    *   the counterpart-first pair, which gives the inverted map;
    *   string and number values;
    *   positions in a 12 s intro, which land at the music start.

    Add a `VersionTimeMap` test for a skit gap in the middle and for positions past the end.

#### 6. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt` (Modify)

*   **Type / Scope**: shared UI hooks.
*   **Proposed Changes**: Add these to `PlayerHost`, each with a default:
    *   `musicVideoSupported` (false);
    *   `MusicVideo(...)` (Unit);
    *   `musicVideoAspect` (16/9);
    *   `musicVideoQuality`;
    *   `setMusicVideoQuality`.

    Add `MusicVideoQualityUi`, `videoQualityLabel`, `showsMusicVideo` and `MusicVideoSurface`, copied from upstream.

#### 7. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/MusicVideoLayout.kt` (Create)

*   **Type / Scope**: shared UI.
*   **Proposed Changes**:
    *   Copy `FullscreenVideoPlayer`, `VideoLoadingBackdrop`, `VideoCornerControls`, `VideoQualityBox`, `VideoQualitySheet` and `QualityRow` from upstream.
    *   Replace Android-only imports with the port's equivalents, the same way as in B3/B4. For example, Haze is already common.
    *   Controls hide after 3,500 ms.

#### 8. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` (Modify)

*   **Type / Scope**: shared UI.
*   **Proposed Changes**: Carry over upstream's video-mode branches:
    *   `videoMode = showsMusicVideo(song)`;
    *   the animated cover is held back and there is no hero;
    *   the backdrop is the blurred artwork;
    *   the 16:9 card with clamped aspect, the corner controls and the black spinner backdrop;
    *   no paused shrink;
    *   a 16:9 collapsed thumbnail and the matching card morph;
    *   the card is hidden while collapsing or docking;
    *   `if (landscape && videoMode)` shows `FullscreenVideoPlayer`;
    *   the quality sheet.

    Each hunk is applied with the `git format-patch` remap used for B3/B4, then fixed up by hand.

#### 9. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt`, `PlayerControls.kt`, `ui/components/MiniPlayer.kt` (Modify)

*   **Type / Scope**: shared UI.
*   **Proposed Changes**:
    *   **Queue:** video rows use 16:9 art (`trackArtSize(44.dp, song.isVideo)`).
    *   **Controls:** the nerd-stats line reads "Automix not supported on video songs".
    *   **Mini player:** the art slot animates to video width × 40 dp for videos and shows `MusicVideoSurface(fit = false)` at priority 0.

#### 10. `sharedUi/src/commonMain/kotlin/com/music/bitchord/ui/theme/ScreenOrientation.kt` and `StatusBarIcons.kt` (Create / Modify)

*   **Type / Scope**: shared expect declarations.
*   **Proposed Changes**:
    *   Add `interface ScreenOrientation { landscape(); portrait() }` and `expect fun rememberScreenOrientation()`.
    *   Add `expect fun HideSystemBars(hidden: Boolean)`.
    *   Add `actual`s for `androidMain` (copied) and `jvmMain` (no-op).

#### 11. `sharedUi/src/iosMain/kotlin/com/music/bitchord/ui/theme/ScreenOrientation.ios.kt` (Create) and `StatusBarIcons.ios.kt` (Modify)

*   **Type / Scope**: iOS actuals.
*   **Proposed Changes**:
    *   `landscape()` and `portrait()` call a registered `OrientationController`. The interface lives in sharedUi; iosKit installs it, so sharedUi doesn't depend on iosKit.
    *   The controller sets the allow-landscape flag and calls `windowScene.requestGeometryUpdate(UIWindowSceneGeometryPreferencesIOS(mask))`.
    *   `HideSystemBars` uses the same status-bar mechanism as `StatusBarIcons.ios.kt`, with a hidden flag.

#### 12. `sharedUi/src/commonMain/composeResources/values/strings.xml` (Modify)

*   **Type / Scope**: strings.
*   **Proposed Changes**: Add `video_quality`, `video_quality_auto`, `automix_not_supported_video` and the Fullscreen and Exit labels. The key names are copied from upstream as they are in its file. English only, as in B3.

#### 13. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosMusicVideo.kt` (Create)

*   **Type / Scope**: iOS video engine.
*   **Proposed Changes**: `internal object IosMusicVideo` with:
    *   **Player:** one muted `AVPlayer`, with `automaticallyWaitsToMinimizeStalling = false` and `preventsDisplaySleepDuringVideoPlayback` set to follow `rendered && playing`.
    *   **Claims:** `claim(view: MusicVideoView, priority: Int): () -> Unit`. The layer moves to the claim with the highest priority. The last release starts a 4 s timer that leads to `unload()`. `UIApplicationDidEnterBackground` calls `unload()` at once.
    *   **Loading:** it watches `IosPlayer.current`. While a claim exists and `song.isVideo`, it resolves the stream through `extractVideo(id, cap, requireCodecPrefix = "avc1")` and loads it at the audio position plus `seekLeadMs`.
    *   **Sync loop:** a 250 ms main-thread timer, also kicked on play, pause and `position.seeks` changes. The constants are copied from upstream:
        *   `SEEK_DRIFT_MS = 450`;
        *   `SEEK_COOLDOWN_MS = 1500`;
        *   `IGNORE_DRIFT_MS = 25`;
        *   a speed nudge of at most ±10%;
        *   a 40 ms snap while paused;
        *   an adaptive seek lead, starting at 250 ms and kept between 0 and 1500 ms;
        *   seeks use zero tolerance.
    *   **Exposed state:** `aspect: StateFlow<Float>`, `quality: StateFlow<MusicVideoQualityUi>` and `rendered: StateFlow<Boolean>`, the last from `AVPlayerLayer.readyForDisplay`.
    *   **Quality:** `setQuality(height: Int?)`, stored in `IosSettings.musicVideoHeight`. Auto is 1080, or 480 when metered.
    *   **Cache:** streams for 1 hour, at most 8 entries. `noPicture` and `refused` sets are cleared on `sessionGeneration`.
    *   **Not a video:** an aspect below 1.2 goes into `noPicture`.
    *   **Errors:** HTTP 403, 404 or 410 call `onVideoRefused()` and the next client is tried. Age-gated videos are retried once.
    *   `MusicVideoView` is a `UIView` subclass whose `layerClass` is `AVPlayerLayer`, the same pattern as `CanvasPlayerView`.
    *   A Compose wrapper, `IosMusicVideoSurface(videoId, fit, onRenderedChanged, modifier, priority)`, uses `UIKitView` with interaction turned off.

#### 14. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosPlayerHost.kt` (Modify)

*   **Type / Scope**: iOS host.
*   **Proposed Changes**: Override the hooks:
    *   `musicVideoSupported = true`;
    *   `MusicVideo` calls `IosMusicVideoSurface`;
    *   `musicVideoAspect` and `musicVideoQuality` come from `IosMusicVideo`;
    *   `setMusicVideoQuality` calls `IosMusicVideo.setQuality`.

#### 15. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosOrientation.kt` (Create)

*   **Type / Scope**: iOS orientation.
*   **Proposed Changes**:
    *   `object IosOrientation` holds `allowLandscape` (false by default).
    *   An exported `supportedMask(isPad: Boolean): ULong` gives:
        *   on iPad, all orientations;
        *   on iPhone with the flag set, portrait or landscape;
        *   otherwise portrait.
    *   It implements sharedUi's `OrientationController`. Clearing the flag calls `setNeedsUpdateOfSupportedInterfaceOrientations` on the root view controller and asks for portrait.
    *   The flag is set while `IosNowPlaying` is shown and `showsMusicVideo(current)` is true.

#### 16. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosNowPlaying.kt` and `MainViewController.kt` (Modify)

*   **Type / Scope**: iOS wiring.
*   **Proposed Changes**:
    *   `IosNowPlaying` sets and clears `IosOrientation.allowLandscape` with a `DisposableEffect`, keyed on the player being open and video mode.
    *   `MainViewController.kt` installs the `OrientationController` and exports `supportedInterfaceOrientationsMask()` for Swift.

#### 17. `iosApp/iosApp/BitChordApp.swift` (Modify) and `iosApp/project.yml` (Modify)

*   **Type / Scope**: Swift shell and app settings.
*   **Proposed Changes**:
    *   Add a small `AppDelegate`, attached with `@UIApplicationDelegateAdaptor`. Its `application(_:supportedInterfaceOrientationsFor:)` returns the Kotlin mask.
    *   In `project.yml`, the iPhone's `UISupportedInterfaceOrientations` gains `LandscapeLeft` and `LandscapeRight`.

#### 18. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosPlayer.kt` (Modify)

*   **Type / Scope**: iOS playback.
*   **Proposed Changes**:
    *   `switchVersion()` first uses `YtMusicRepository.versionTimeMap(from, to)?.map(livePosition)` as the target's start, then `IosVersionAligner`, then 0.
    *   `automixTransition()` returns false when `song.isVideoOrigin` or `next.isVideoOrigin`, which falls back to a plain crossfade.
    *   The listening recorder is told about a mid-track switch, so the track counts as one listen. See 19.
    *   `musicOnly()` and `preferMusicOnly` are unchanged.

#### 19. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosListeningRecorder.kt` (Modify)

*   **Type / Scope**: history.
*   **Proposed Changes**: Add `onVersionSwapped(fromId, toId)`, which carries the current listen over to the new id instead of starting a new one. This matches upstream's `PlaybackTracker.onVersionSwapped`.

#### 20. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/IosSettings.kt` (Modify)

*   **Type / Scope**: settings.
*   **Proposed Changes**:
    *   Add `IntPref("${PREFIX}music_video_height", 0)`, where 0 means Auto. It has no settings row; the quality sheet sets it.
    *   `preferMusicOnly` is unchanged.

#### 21. `iosKit/src/iosMain/kotlin/com/music/bitchord/ios/Autoplay.kt` and `.github/workflows/ios.yml` (Modify)

*   **Type / Scope**: simulator smoke test.
*   **Proposed Changes**:
    *   Add a check that plays a known OMV row with Now Playing open.
    *   It logs `video: rendered=…, drift=…ms` every 2 s for 20 s.
    *   It then backgrounds the app and logs `video: unloaded` when that happens.
    *   `ios.yml` fails the job when:
        *   no `rendered=true` line appears;
        *   or the median absolute drift after 5 s is above 60 ms;
        *   or `unloaded` doesn't appear within 1 s of going to the background.
    *   It saves a screenshot of the video card and one of the full-screen player (forced through `requestGeometryUpdate`).

#### 22. `docs/designs/music-videos.md` (Create) and `docs/IOS_PORT.md` (Modify)

*   **Type / Scope**: documentation.
*   **Proposed Changes**:
    *   The final copy of this design.
    *   A short IOS_PORT note on the video engine and the orientation flag.

### Verification Plan

*   **Compilation/Build**: `./gradlew :shared:compileKotlinIosArm64 :sharedUi:compileKotlinIosArm64 :iosKit:compileKotlinIosArm64 --offline`.
*   **Unit Tests**: `./gradlew :shared:jvmTest --tests '*VersionPair*'` (the parser and the map).
*   **Static Analysis & Linting**: none is set up in the repo. The compile with warnings is the check.
*   **E2E/Integration**: dispatch `ios.yml` with `{"app":true,"smoke":true,"release":true}`. The smoke assertions from file 21 must pass, and the screenshots are published to `ios-latest`.
*   **On device (owner)**:
    *   CUJs 1 to 6, on Wi-Fi and on cellular.
    *   Lip-sync judged by eye.
    *   Rotation with the rotation lock on and off.
    *   A call during a video: the audio pauses and the picture holds.
    *   The phone's battery temperature over 15 minutes of video.

--------------------------------------------------------------------------------

*Drafted with EGM*
