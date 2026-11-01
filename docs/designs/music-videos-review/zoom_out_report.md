# Stage 5: Initial Zoom Out Review

**Target:** `docs/designs/music-videos.md` (lines 1-618)

## Reviewer Status

| Reviewer | Status |
| --- | --- |
| `egm-zoom-out` | completed |

**Verdict:** The design is sound and appropriately minimal for a port. There are no blockers.

## Findings

### 1. The listening-history hook already exists. Severity: Major

* **Scope:** lines 281, 568, 571-574 (File 19).
* **What's wrong:** `IosListeningRecorder.switched(fromId, toId)` (IosListeningRecorder.kt:59-62) already carries a listen across a version switch. `switchVersion()` already calls it (IosPlayer.kt:1073).
* **Fix:** Delete File 19 and the bullet at line 568. Change line 281 to say this is already done.

### 2. The codec retry has no single owner, and the "existing limit of 3" doesn't exist. Severity: Major

* **Scope:** lines 185-189, 221, 425, 510, 523.
* **What's wrong:**
  * Line 187 says iOS does the retry.
  * Line 425 puts the retry in the shared resolver.
  * Lines 221 and 523 add a second retry for HTTP 403/404/410.
  * The iOS resolver has no 3-client limit. The 3 is upstream's `MAX_REFUSED_CLIENTS`, which lives on the engine side.
* **Fix:**
  * Make the resolver the one owner: `requireCodecPrefix` plus its own `MAX_REFUSED_CLIENTS`.
  * The engine only reports refusals, through `onVideoRefused`.
  * Drop the word "existing".

### 3. The iPad landscape player gets replaced. Severity: Major

* **Scope:** lines 233-234, 470, 542.
* **What's wrong:**
  * `landscapePlayerAvailable` is true on any landscape iPad. With `if (landscape && videoMode)`, an iPad held in landscape always shows the full-screen video.
  * The two-column landscape layout goes away for videos.
  * "Exit asks for portrait" fights the user's own iPad orientation.
* **Fix:** Either specify the iPad behaviour, for example fullscreen only when asked for on iPad with the card inside the landscape layout, or accept upstream's tablet behaviour explicitly.

### 4. The doc contradicts itself on `preferMusicOnly`. Severity: Major

* **Scope:** line 282 vs lines 376-378.
* **Fix:** Rewrite line 282 to match the owner's decision to keep the setting as it is.

### 5. The swap interface for a segment-mapped position is unclear. Severity: Nit

* **Scope:** lines 268-273, 566.
* **What's wrong:** `swapStream` takes one fixed offset, and it is computed before the target stream resolves. The playhead moves meanwhile, so near a segment edge the offset is wrong.
* **Fix:** Say whether `swapStream` takes an absolute start computed at swap time, or whether the offset is recomputed just before the swap.

### 6. Sync and quality details are written twice. Severity: Nit

* **Scope:** §2 (lines 199-223) vs File 13 (lines 507-525).
* **Fix:** Keep the behaviour in §2. In File 13, say "constants copied verbatim from upstream `MusicVideoEngine`".

### 7. The stream cache has no reason given for iOS. Severity: Nit

* **Scope:** lines 218, 521.
* **Fix:** Give a one-line reason (re-resolving after the grace period, rotation or a quality change), or match upstream exactly.

### 8. Auto quality doesn't say what happens when the network type is unknown. Severity: Nit

* **Scope:** lines 71-72, 216, 520.
* **What's wrong:** `IosNetwork.metered` can be null (unknown).
* **Fix:** Treat null as metered (480p).

### 9. File 22 is marked "Create" but the file already exists. Severity: Nit

* **Scope:** lines 596-601.
* **Fix:** Mark it Modify.

### 10. Placement check. Severity: Nit, no change needed

* The reuse points all exist in the code.
* The direction of the `OrientationController` dependency is correct.
* The Swift and plist changes are the minimum needed.
