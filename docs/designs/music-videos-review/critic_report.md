# Stage 7: Critic Review

**Target:** `docs/designs/music-videos.md` (FeatureRange, lines 1-381)

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-design` | completed |
| `egm-structural` | completed |
| `egm-style` | completed |

Findings that more than one reviewer raised are merged into one entry. The "Lens" line lists every reviewer that raised it.

## Blockers

### C1. Audio output latency is ignored, so the picture runs ahead of the sound on Bluetooth and AirPlay
* **Lens:** design
* **Scope:** lines 15-17, 61, 199-209, 85, 366-369
* **Finding:** The sync loop follows `IosPlayer.position.positionMs`. That is the position the player has reached, not the moment the sound comes out of the speaker. Nothing in iosKit uses `AVAudioSession.outputLatency` for timing; it only appears as a display row in `IosAudioPipeline.kt:101`.
  * AirPods add about 150-250 ms between the two.
  * AirPlay 2 adds about 2 s.
  * The FFmpeg route also queues buffers ahead of the output.

  So the 25 ms goal can't hold on these routes.
* **Fix:**
  * Make the sync target `positionMs + elapsed − outputLatency`, worked out per route.
  * Re-read it on every route change.
  * Turn the picture off on AirPlay, or state how much lag is accepted there.
  * Add a headphones CUJ.

## Major

### C2. The background release is described with two different timings
* **Lens:** style
* **Scope:** lines 69 and 115 vs 214 and 360
* **Finding:** One place says the video is released 4 s after going to the background. The other says it is unloaded at once.
* **Fix:** State one rule: a released claim gets 4 s of grace; going to the background or locking the screen unloads immediately.

### C3. `preferMusicOnly` is called both "open" and "decided"
* **Lens:** design, structural, style
* **Scope:** line 282 vs 376-378
* **Fix:** Rewrite line 282 to match the decision.

### C4. Status says "Approved" while the codec question is unproven and other questions are open
* **Lens:** design, structural
* **Scope:** line 4 and lines 89-95
* **Finding:** Two things are still open:
  * the signed-out question has no default answer;
  * H.264 coverage is unmeasured.

  Line 187 also cites an "existing limit of 3 refused clients" that doesn't exist on iOS.
* **Fix:**
  * Give each open question a default answer.
  * Mark the 3-client cap as new.
  * Either change the status, or state that Step 0 is a go/no-go gate (at least 18 of 20).

### C5. Two different fallbacks are named for missing H.264
* **Lens:** structural
* **Scope:** line 94 vs line 344
* **Finding:** One says to add a codec option to InnerTubeX; the other says to use Alternative D (HLS).
* **Fix:** Name one ordered fallback list, in one place.

### C6. A video refusal would also exclude that client for the song's audio
* **Lens:** design
* **Scope:** lines 187, 221-225, 366-369
* **Finding:** `InnerTubeXResolver.exclude(videoId, profileId)` writes to a shared `excluded` map (InnerTubeXResolver.kt:203-204, 271-275). Audio's `extract()` reads the same map (line 228). On a video row the audio and the picture have the same id, so a 403 on the video breaks the "audio is never affected" rule.
* **Fix:** Pass video exclusions only through `skipClients` or a separate set, and never call `exclude()`.

### C7. Extra YouTube player requests and bot-detection risk aren't analysed
* **Lens:** design
* **Scope:** lines 53, 178-190, 215-221, 371-372
* **Finding:** Each video row costs at least one more extraction. Codec retries can add up to 3 more, and quality changes add more again.
* **Fix:**
  * Estimate the number of requests per play.
  * Debounce quality changes.
  * Say whether the audio and video extractions can share one player response.

### C8. iOS's own clock tools aren't weighed as an alternative, and B and C are rejected only on the owner's say-so
* **Lens:** design, structural
* **Scope:** lines 284-353
* **Finding:**
  * The doc doesn't consider `AVPlayer.setRate(_:time:atHostTime:)`, `sourceClock` or `AVSampleBufferRenderSynchronizer`.
  * B and C need technical reasons for rejection, not just the owner's choice.
* **Fix:** Add a "host-time clock" alternative, and tie the rejections of B and C to the cons already listed.

### C9. iPad in landscape loses its two-column player and always goes full screen
* **Lens:** design (also Stage 5, finding 3)
* **Scope:** lines 229-240
* **Finding:**
  * Split View and Stage Manager windows are affected too.
  * "Exit asks for portrait" is wrong on iPad.
* **Fix:**
  * On iPad, go full screen only through the button, and keep the card inside the landscape layout.
  * Make geometry requests on iPhone only.

### C10. The aspect-ratio rules contradict each other, and portrait videos aren't covered
* **Lens:** design, style
* **Scope:** lines 60 and 219
* **Finding:**
  * The card allows 1:1, but anything below 1.2:1 is thrown away as a still cover.
  * 9:16 (portrait) videos aren't mentioned anywhere.
* **Fix:** Make the two ranges agree, and state how portrait videos are handled.

### C11. Version switching and the picture hand-off aren't specified
* **Lens:** design (also Stage 5, finding 5)
* **Scope:** lines 66-68, 117-121, 268-273
* **Finding:** The doc doesn't say:
  * when the mapped offset is computed, which should be just before `swapStream`;
  * what the user sees while the picture loads in the middle of a song (CUJ 4);
  * how the picture follows the audio during a crossfade between tracks.
* **Fix:** Specify all three, and pre-resolve the video before the audio swap.

### C12. There is no Risks & Mitigations section
* **Lens:** structural
* **Scope:** lines 45-55 and 357-378
* **Fix:** Add a section that pairs each risk with its failure mode and its mitigation.

### C13. Undefined terms
* **Lens:** structural, style (also Stage 6)
* **Finding:** "B0" (lines 302 and 369) and "upstream" are never defined.
* **Fix:** Define them, and link `docs/designs/context/`.

### C14. Line 239 doesn't parse, and line 209 hides the property it means
* **Lens:** style
* **Fix:**
  * Line 239: "using the same mechanism as `StatusBarIcons.ios.kt`".
  * Line 209: name `automaticallyWaitsToMinimizeStalling = false`.

## Nits

* **No cellular off switch for video.** There is no "no video on mobile data" option (design).
* **Rollback needs a new build.** A user setting could gate `musicVideoSupported` instead (design).
* **Video request headers aren't specified.** The doc should say the video asset reuses the audio engine's `AVURLAssetHTTPHeaderFieldsKey` asset construction (design).
* **CUJ 5's message doesn't match the code.** The code says "No video version found". Remembering failed lookups is new work, because `otherCut` only stores successes (design).
* **The Automix check names the wrong flag.** It should use `song.isVideo`. `isVideoOrigin` is set on the converted audio cut, so checking it would turn Automix off for songs (design). The doc also doesn't explain the difference between `isVideo` and `isVideoOrigin` (Stage 6).
* **Section titles don't match the standard.** "The Problem" and "Technical Plan" should use the standard's titles (structural).
* **Alternatives are laid out inconsistently** (structural).
* **The Non-Functional Requirements section sits after Alternatives, behind a horizontal rule** (structural).
* **There are no links to the files named** (structural).
* **Some claims are stated too strongly:**
  * "exact and available at once";
  * "H.264 is guaranteed";
  * "already tested".

  (style)
* **Acceptance criteria are vague:**
  * "about 2 s";
  * "in sync";
  * "25–40 times" (the arithmetic gives 23–39).

  (style)
* **Terms are used inconsistently:**
  * Now Playing, "the player" and "the full player";
  * Fullscreen and "full screen".

  (style)
* **Smaller wording fixes:**
  * "fate" is too loose;
  * "actually" is filler;
  * lines 231-232 are vague and repeat each other;
  * lines 235 and 240 repeat each other;
  * the codec limitation is stated three times;
  * "the owner" is never identified;
  * "nerd-stats line";
  * the heading "Not a video after all";
  * the process note at line 286;
  * line 36 states a judgement as if it were a fact;
  * "url" should be "URL".

  (style)
