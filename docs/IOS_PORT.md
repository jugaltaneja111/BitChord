# BitChord for iOS — port plan

Status: M2 done, M3 in progress. On a booted iOS simulator, InnerTubeX resolves a public track to an AAC
stream (`VISIONOS` client, no PoToken) and googlevideo serves it (`LiveStreamResolveTest`).
`sharedUi` — every shared page and the player — now lives in `commonMain` and compiles for iOS,
with iOS `actual`s for its platform hooks. The iOS app is `iosKit` (Kotlin: AVPlayer engine,
queue, search wiring, entry point) plus `iosApp` (a SwiftUI shell, generated with XcodeGen). It
has the phone's four tabs in its floating bar, with its mini player (both now in `sharedUi`;
shared Home, Explore with its category art, and Search; Library signed out), Explore's
category pages, album / playlist and artist pages (written for iOS, as each platform writes its
own), a mini player and the shared full player with its queue, shuffle, repeat and autoplay.
Back is a swipe from the left edge. YouTube Music sign-in works as on the phone: Google's own page in a WKWebView (with Safari's
user agent, which Google requires), the session read off music.youtube.com's `ytcfg`, checked
against the API, then kept in the Keychain. Signed in, Home is the personal feed with Recently
Played, Library is the account's, and the player can like. Several Google logins can be kept,
each with its personal and brand channels, switched from the avatar as on the phone; History is
the account's own. Holding a song, or its ⋮ in the player, opens the phone's menu (like, add to a
playlist, library, radio, play next, queue, album, artist, share); holding a card, or a page's ⋯,
the release's (play, queue, save, share, rename and delete an own playlist). Settings follows the
phone's page group for group: what the iOS app can apply works (including the Liquid Glass app
icons), the rest is listed as coming. Lyrics come from the phone's providers, now in
commonMain on Ktor (TTML through a small common XML reader, Genius pages through a tag scanner on
iOS and Jsoup on the JVM), with the sources' choice and order in Settings. Downloads keep tracks (and whole albums or
playlists) in the app's Documents, out of backup, fetched in ranges as the phone does and played
from the file with no connection; Library lists them. Replay counts listening on the phone in the Android app's
monthly files and format (iosKit's own copy, as the desktop has its own), and draws the phone's
cards, charts and stories; genres wait for Last.fm, and the share picture is text for now. Sound: two
AVPlayers, the next track readied 30 s ahead in the second, so a change of track is instant or a
crossfade (the phone's 0–12 s slider); each item carries an MTAudioProcessingTap running the phone's
equaliser sections (tone pad, seven bands, balance), YouTube's loudness gain and the peak limiter.
A track's length is googlevideo's `dur` or the catalogue's, never AVPlayer's estimate (YouTube's
fragmented MP4 has no total, and the guess can double). Account & integrations: Last.fm (the
build's key from the repository's `LASTFM_API_KEY`/`LASTFM_SECRET` secrets, or the listener's own)
and ListenBrainz scrobble on the phone's rules, and Last.fm's tags give Replay its genres; Spotify
signs in on its own page, its token caught in a hidden web view, with `SpotifyLibrary` /
`SpotifyImporter` now in commonMain on Ktor; Discord presence runs the shared Kizzy gateway (now
commonMain too). Credentials live in the Keychain. Not yet: Automix and skip silence (both need
the track analysed ahead), quality choices (iOS only plays YouTube's AAC until Sources), lyrics
translation and romanisation, local playlists for imports when signed out. The manual `iOS` workflow builds the unsigned IPA, then on the simulator plays a
track and walks every page with no taps (`BITCHORD_AUTOPLAY`, `BITCHORD_TOUR`), screenshotting
each, as a smoke test.

Building on Linux: Kotlin/Native compiles iOS klibs there (`:shared:compileKotlinIosArm64`),
but linking frameworks and running tests needs macOS with Xcode. CI compiles on Linux for
every push and runs macOS only when started by hand (macOS minutes are limited).

Known iOS issues:

- Simulator tests must run on a booted simulator (`-Pbitchord.ios.simulator=<udid>`):
  standalone runs have no trustd, so every HTTPS certificate fails validation.
- InnerTubeX's QuickJS solver reported `InternalError: stack overflow` while parsing the
  player script during warm-up on iOS. Tracks whose URLs need deciphering will depend on it;
  the direct `VISIONOS` stream did not. Likely QuickJS's stack check meeting GCD's 512 KB
  worker stacks; to investigate with InnerTubeX.
- `TrackLog` cannot tag lines with a track on iOS (no `ThreadContextElement` in
  Kotlin/Native), so its per-track export shows everything held.
- Kotlin 2.4.10 is required (InnerTubeX's iOS klibs are built with it), and with it R8 9.5.26.
- Status bar style and hiding (`StatusBarIcons`, `HideStatusBar`) are no-ops on iOS; the shell
  fixes the bar light-on-dark in Info.plist.

Building the IPA: the `iOS` workflow's `app` job (started by hand, or by pushing an `ios-v*` tag,
which also attaches the IPA to a release). Locally on a Mac: `cd iosApp && xcodegen generate`,
then open `BitChord.xcodeproj`; its build phase runs `:iosKit:embedAndSignAppleFrameworkForXcode`.

## Goal and approach

Ship BitChord on iPhone (iOS 16+) from the existing Kotlin Multiplatform
codebase, reusing `shared` (logic) and `sharedUi` (Compose Multiplatform UI),
with a thin Swift app shell for what iOS only exposes natively.

The desktop app is the template. It is already a non-Android client built on
`shared` + `sharedUi` with its own audio engine (`DesktopAudioEngine`),
player host (`DesktopPlayerHost`) and wiring (`DesktopModules`). The iOS app
mirrors that structure: `iosApp/` (Swift shell) + an `iosMain` platform layer.

Android, TV, Wear OS and desktop must keep building and behaving the same at
every step. iOS targets are added alongside, never in place of, JVM code.

## Where the code stands today (measured at upstream `edc1e6f`)

| Area | Size | iOS blocker |
|---|---|---|
| `shared/commonMain` | 17 files | None — already portable |
| `shared/jvmSharedMain` | 196 files | 51 have no JVM-only imports (some still use `String.format`, `synchronized`, `@Volatile`, `System.currentTimeMillis`). The rest import `java.util` (51), `okhttp3` (37), NewPipe (20), `java.io`/`java.nio` (18), ONNX Runtime (6), `jsoup` (2), `javax.crypto`/`javax.xml`/`java.security` (6) |
| YouTube client | `Innertube.kt`, `InnertubeParser.kt`, `StreamResolver.kt`, `InnerTubeXResolver.kt` (~5k lines) + InnerTubeX and NewPipe libraries | Both libraries are JVM-only (JitPack) |
| PoToken | `PoTokenMinter` interface + a JS page | Needs a WebView/JS runtime per platform — WKWebView on iOS |
| `sharedUi` | 70 files, all in `jvmSharedMain` | Mostly pure Compose. 5 `expect` declarations need iOS `actual`s (haptics, context click, status bar icons, player platform UI, artwork pixels) |
| Playback | Android: Media3/ExoPlayer + FFmpeg JNI. Desktop: own engine (~2k lines) | Full rewrite on AVFoundation |
| Automix | C++ in `native/analyzer` + ONNX models | Recompile for iOS; ONNX Runtime has an iOS build |
| `backend/` (Go), `web/` | — | Unaffected |

## Phases

Each phase ends with Android/desktop still green and an iOS check that proves
the phase (listed under **Done when**).

### Phase 0 — Build environment

- Allow build hosts: `dl.google.com` (Google Maven), `jitpack.io`,
  `download.jetbrains.com` (Kotlin/Native toolchain dependencies).
- Fast loop on Linux: Kotlin 2.3 can compile iOS klibs on Linux (cross-
  compilation of klibs). Linking a framework and running the app need macOS.
- CI: a GitHub Actions `macos-15` job that builds the framework, runs
  `iosSimulatorArm64Test`, and builds `iosApp` for the simulator.

**Done when:** Linux can run `:shared:compileKotlinIosArm64`; macOS CI is green.

### Phase 1 — iOS targets in `shared`

- Add `iosArm64` and `iosSimulatorArm64` targets (`iosX64` only if Intel Macs
  matter for development). Default hierarchy gives `iosMain`.
- Ktor engine as `expect`/`actual`: OkHttp on JVM, Darwin on iOS.
- Add KMP replacements as needed: `kotlinx-datetime` (time, `Locale`-free
  formatting), `kotlinx-io` (byte buffers, files), `atomicfu` (atomics,
  `@Volatile`, locks).

**Done when:** `shared` compiles for iOS with `commonMain` only (empty `iosMain`).

### Phase 2 — Move portable logic to `commonMain`

Move files from `jvmSharedMain` to `commonMain` one package at a time, most
portable first: models, queue (`QueueWindow`, `QueueTimeline`,
`QueueBuilder`, `ContextRegistry`), transition planning, lyrics parsing
(`LyricLine`, `EnhancedLrc`, `TtmlLyrics`, `SyllableRuns`, …).

- `java.util` collections → Kotlin stdlib; `String.format` → explicit
  formatting; `synchronized` → `atomicfu` locks.
- `javax.xml` (TTML) → `xmlutil`; `jsoup` → `ksoup`; `javax.crypto` /
  `java.security` → `cryptography-kotlin` or a small `expect` per use.
- Existing JVM tests move to `commonTest` with the code they cover, so the same
  tests run on JVM and iOS simulator.

**Done when:** each moved package's tests pass on JVM and `iosSimulatorArm64`.

### Phase 3 — Network sources on Ktor

- Lyrics providers (16 files) and Spotify/Discord HTTP from `okhttp3` to
  Ktor (already a dependency). *Lyrics done; `LyricsTranslation` (on-device
  model, file cache) stays JVM-only for now.*
- Listen-together client: Ktor WebSockets work on Darwin unchanged.

**Done when:** lyrics fetch for a known track succeeds from an iOS test.

### Phase 4 — YouTube stream resolution (go / no-go)

This is the riskiest phase: everything else is useless without it.

1. Spike: check whether InnerTubeX can be built for Kotlin/Native, or which
   parts the app uses (client catalog, cipher, extraction).
2. Port the repo-owned `Innertube` / `InnertubeParser` / `StreamResolver` to
   `commonMain` on Ktor.
3. Signature/n-param cipher: run the player JS via JavaScriptCore on iOS behind
   an `expect` interface (the JVM keeps its current path).
4. PoToken: implement `PoTokenMinter` with `WKWebView` on iOS.
5. Stream choice on iOS: AAC/M4A formats (AVFoundation cannot play
   Opus/WebM). Lossless module sources (FLAC/ALAC) play natively.

**Done when:** a simulator test resolves a playable stream URL for a public
track, and AVPlayer plays it. If this cannot be done reliably, stop and
re-plan before Phase 5.

### Phase 5 — iOS audio engine

- Common `PlayerEngine` contract extracted from what `DesktopAudioEngine` and
  the Android service already share (play, pause, seek, queue, position,
  state flow).
- Step 1: `AVQueuePlayer` — playback, gapless queue, background audio
  (`AVAudioSession` `.playback`), interruptions, route changes, AirPlay,
  lock screen (`MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`).
- Step 2: `AVAudioEngine` graph for crossfade (two player nodes), equalizer
  (`AVAudioUnitEQ`), speed (`AVAudioUnitTimePitch`), peak limiter
  (`PeakLimiter` logic from `commonMain`).
- Step 3: Automix — build `native/analyzer` as an xcframework, bind with
  cinterop; ONNX Runtime for iOS.

**Done when:** a queue plays in the background with lock-screen controls
(step 1), then crossfade and EQ (step 2), then Automix (step 3).

### Phase 6 — UI on iOS

- Move `sharedUi` from `jvmSharedMain` to `commonMain`; add `iosMain`
  actuals for the 5 `expect` declarations (haptics via
  `UIImpactFeedbackGenerator`, status bar style, artwork pixels via Skia
  bitmaps, context click → long-press, player platform UI).
- `iosApp/`: SwiftUI `App` hosting `ComposeUIViewController`, safe-area and
  keyboard handling, iOS back-swipe navigation.
- Port the desktop app's screens/wiring (`DesktopApp`, `DesktopModules`) that
  are not yet in `sharedUi` into shared code where both apps can use them.

**Done when:** home, search, library, now playing and lyrics screens run on the
simulator against real data.

### Phase 7 — Accounts, storage and integrations

- Settings and library storage: a KMP store (DataStore KMP or
  multiplatform-settings; SQLDelight/Room KMP if a database is needed).
- Google sign-in: `ASWebAuthenticationSession`; credentials in Keychain.
- Downloads: background `URLSession`, files in the app container.
- Spotify, Last.fm/ListenBrainz scrobbling: shared code on Ktor.
- Discord Rich Presence: foreground only (iOS suspends background sockets).
- Local library: `MPMediaLibrary` (non-DRM tracks only).
- Out of scope on iOS: Android Cast, Wear OS, TV, in-app APK updater.

**Done when:** a signed-in account shows personal home, downloads play offline.

### Phase 8 — Distribution

- App Store and TestFlight are not realistic (unofficial YouTube client;
  GPL-3.0 vs App Store terms). Plan for sideloading (AltStore / SideStore,
  or an unsigned IPA) and EU alternative marketplaces.
- Release job on macOS CI producing an IPA per tag.

## Milestones

| Milestone | Phases | Proves |
|---|---|---|
| M1 — compiles | 0–1 | Toolchain works; iOS targets don't disturb other apps |
| M2 — first sound | 2–4, 5 step 1 | YouTube playback is feasible on iOS (go / no-go) |
| M3 — usable app | 5 step 2, 6 | Core player with the real UI |
| M4 — parity | 5 step 3, 7 | Accounts, downloads, Automix, integrations |
| M5 — release | 8 | Installable builds |

## Open questions

- Is InnerTubeX (or a fork) willing to support Kotlin/Native, or will BitChord
  own its YouTube client on all platforms?
- Minimum iOS version (16 assumed for `ComposeUIViewController` and
  AVFoundation APIs used).
- Would upstream accept iOS work, or does this stay a fork?
