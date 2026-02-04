# Rev [57] - Decoder Poisoning Analysis and Fixes

## Document Created: 2026-01-30
## Purpose: Document root cause analysis of video ghosting/tailing/pixelation and planned fixes
## Revision: [57] - Critical video stability fix

---

## Executive Summary

Rev [56] implemented a projection video model with staleness checking and IDR gating, but users continue to experience **progressive video degradation** (ghosting, tailing, pixelation) that eventually results in frozen frames. The degradation only clears when the user manually triggers a codec reset.

**Root Cause**: Multi-factor decoder poisoning cascade caused by:
1. **🚨 CRITICAL**: Staleness check uses incompatible time bases (BROKEN)
2. Staleness check discards frames without checking NAL type (including IDR)
3. Intel VPU doesn't auto-reset reference surfaces on IDR
4. No detection of "corruption survived IDR" condition

---

## 🚨 CRITICAL FINDING: Staleness Check Time Base Bug

**Discovered via USB capture analysis (2026-01-30)**

### The Bug

The staleness check at `H264Renderer.java:1058` compares **incompatible time bases**:

```java
long currentTime = System.currentTimeMillis();  // Unix epoch: ~1,738,200,000,000 ms
long frameAgeMs = currentTime - sourcePtsMs;    // sourcePtsMs: ~32,720 ms (stream-relative)
// Result: frameAgeMs ≈ 1,738,199,967,280 ms (~1.7 TRILLION ms!)
```

### Why Frames Still Pass Through

The condition is:
```java
if (frameAgeMs > FRAME_STALE_THRESHOLD_MS && !ringBuffer.isEmpty())
```

Since `frameAgeMs` is always ~1.7 trillion (exceeds any threshold), frames **only pass when `ringBuffer.isEmpty()`**.

### Observed Behavior

| Scenario | Buffer State | Frame Outcome |
|----------|--------------|---------------|
| First frame | Empty | ✅ Passes (buffer empty exception) |
| Decoder keeps up | Empties quickly | ✅ Next frame passes |
| Decoder stalls | Has ≥1 packet | ❌ ALL frames discarded (including IDR!) |
| After stall | Eventually empties | ✅ Frames pass again |

### Log Evidence

The logs show only **220-231 stale discards** over minutes - not millions. This confirms:
- Frames pass when buffer is empty (race condition with decoder speed)
- The threshold value (150ms) is **irrelevant** - the check produces 1.7 trillion regardless

### USB Capture Verification

From `/Volumes/KING/carlink_native/recording/JAN28/` analysis:

| Metric | Value |
|--------|-------|
| Source PTS range | 32,720 - 200,000+ ms (stream-relative) |
| Arrival timestamp | 44,877+ ms (capture-relative) |
| Arrival - PTS offset | ~12,157 ms (constant clock offset) |

The **source PTS is stream-relative**, starting near 0 when CarPlay session begins on the adapter.
`System.currentTimeMillis()` is Unix epoch time. **These cannot be compared.**

### Adapter Log Confirmation

From adapter internal log (`adapter_tty_010843_30JAN26.log`):

| Finding | Value | Significance |
|---------|-------|--------------|
| Video Latency | 75 ms | Expected latency |
| Average delay | 7.85 ms | Actual frame delay on adapter |
| Clock sync | via `DeviceTimeUpdateMsgID` | iPhone syncs adapter clock |

The adapter's video stream uses **media-relative timestamps**:
- PTS starts near 0 when CarPlay video stream begins
- PTS increments with frame timing (~16ms at 60fps)
- USB capture shows PTS ~32,720ms (32s into session)

The app's `System.currentTimeMillis()` is Unix epoch (~1.7 trillion ms) - **completely unrelated time base**.

### Required Fix

The staleness check must track **arrival time** using a consistent time base:

```java
// Option A: Establish PTS-to-epoch offset on first frame
private long ptsToEpochOffsetMs = 0;
private boolean ptsBaseEstablished = false;

// On first frame:
if (!ptsBaseEstablished) {
    ptsToEpochOffsetMs = System.currentTimeMillis() - sourcePtsMs;
    ptsBaseEstablished = true;
}
// Calculate expected arrival:
long expectedArrivalMs = sourcePtsMs + ptsToEpochOffsetMs;
long frameAgeMs = System.currentTimeMillis() - expectedArrivalMs;

// Option B: Track time-in-buffer, not absolute staleness
// Measure how long frame waited in ring buffer before decode

// Option C: Disable staleness check entirely (RECOMMENDED for Rev [57])
// Rely on IDR gating + stall detection (already implemented)
// The staleness check provides no benefit with broken time base
```

---

## USB Capture Analysis Results

**Capture Session**: `carlink_capture-2026-01-28T18-29-26-974Z` (169 seconds)

### NAL Unit Distribution

| NAL Type | Count | Description |
|----------|-------|-------------|
| SPS (7) | 88 | Sequence Parameter Set |
| PPS (8) | 88 | Picture Parameter Set |
| IDR (5) | 88 | Instantaneous Decoder Refresh (keyframe) |
| P-slice (1) | 2,856 | Predicted frames |
| B-frames | 0 | ✅ No B-frames (good for projection) |

### IDR Cadence

| Metric | Value |
|--------|-------|
| Total IDRs | 88 |
| Average interval | 1,385 ms (1.4s) |
| Min interval | 75 ms |
| Max interval | 2,120 ms |
| IDRs bundled with SPS+PPS | 100% |

### Adapter Output Assessment

✅ **Adapter output is CORRECT for projection video:**
- Regular IDRs (~1.4s) provide recovery points
- All IDRs bundled with SPS+PPS (decoder can init from any IDR)
- No B-frames (no temporal reordering needed)
- Clean P-frame only stream between IDRs

The video degradation is **not caused by adapter output** - the adapter provides exactly what the app needs for projection video.

---

## Observed Symptoms

User-reported behavior during Rev [56] testing:

| Symptom | Phase | Description |
|---------|-------|-------------|
| Clean video | Initial | First IDR establishes valid reference |
| Ghosting/tailing | Degradation | UI elements leave visual trails during motion |
| Pixelation | Advanced | Blocky artifacts accumulate |
| Frozen frame | Terminal | Decoder completely loses reference |
| Clean after reset | Recovery | Manual reset button or homescreen navigation fixes |

**Critical observation**: Touch and audio continue working during video corruption, confirming the issue is isolated to the video decode pipeline.

---

## Root Cause Analysis

### The Decoder Poisoning Cascade

Based on analysis of concepts document and code review:

```
Phase 0: Healthy State
├── First IDR decoded
├── References clean
├── Video crisp
│
Phase 1: Inciting Event (SILENT)
├── Frames arrive 40-150ms late
├── Pass staleness check (threshold = 150ms)
├── Get decoded anyway
├── Become stale references
│
Phase 2: Silent Contamination
├── P-frames reference stale data
├── Decoder doesn't know this is wrong
├── Maybe one block flickers
│
Phase 3: Motion Amplification (VISIBLE)
├── Motion vectors reference contaminated data
├── Each frame compounds error
├── Ghosting/trails appear
├── Edges smear
│
Phase 4: Visual Decay Plateau
├── Image consistently wrong
├── Errors reference errors
├── IDRs arrive but don't fix it (Intel VPU)
│
Phase 5: Terminal
├── Decoder completely poisoned
├── Frozen frame
├── Only manual reset recovers
```

### Factor 1: Staleness Threshold Too Lenient

**Current code** (`H264Renderer.java:154`):
```java
private static final int FRAME_STALE_THRESHOLD_MS = 150;
```

**Recommended by concepts document**:
> "It waited in your app longer than ~30–40 ms" → DROP

**USB Capture Evidence (215,191 frames analyzed):**
| Metric | Value | Source |
|--------|-------|--------|
| Jitter std dev | 25.6ms | 10 sessions |
| Frames within ±20ms | 55% | Measured |
| Frames within ±40ms | **85%** | Measured |
| Frames within ±150ms | ~99% | Estimated |

A 40ms threshold drops ~15% of frames (acceptable for projection video).
A 150ms threshold drops ~1% but allows 14% of frames that are "too late" to enter decode.

Frames arriving 40-150ms late pass the check, get decoded, and poison references.

### Factor 2: Staleness Check Ignores NAL Type

**Current code** (`H264Renderer.java:1059-1064`):
```java
if (frameAgeMs > FRAME_STALE_THRESHOLD_MS && !ringBuffer.isEmpty()) {
    // Frame is stale and we have other data - discard
    staleFramesDiscarded.incrementAndGet();
    totalFramesDropped.incrementAndGet();
    // Note: We could check NAL type and keep IDR, but skipToNewestIdr handles that
    return;  // <-- DISCARDS WITHOUT CHECKING NAL TYPE
}
```

**Problem**:
- Staleness check happens BEFORE payload examination
- IDR frames (NAL type 5) can be discarded
- Comment claims `skipToNewestIdr` handles this, but that only runs when buffer is FULL (≥12 packets)
- If IDR arrives "late" and buffer is NOT full, IDR is discarded

### Factor 3: Intel VPU Doesn't Auto-Reset on IDR

From concepts document:
> "MediaCodec: Assumes client enforces timing discipline, Reuses surfaces aggressively, Does not auto-reset on reference errors"

Even when IDR reaches the decoder:
- Intel VPU may reuse corrupted internal surfaces
- Reference frame reset is not automatic
- Corruption can persist through IDR

### Factor 4: No "Corruption Survived IDR" Detection

From concepts document:
> "If corruption survives one IDR, your decoder state is poisoned. Reset immediately."

**Current code implements**:
- ✅ IDR gate (wait for first IDR before P-frames)
- ✅ Stall detection (reset if no output for 500ms)
- ❌ **Missing**: Detection of corruption persisting through IDR

---

## Evidence from Logs

### PERF Log Analysis (Rev [56])

```
01:03:40  [STALE_DISCARDED: 220]   ← 220 frames discarded before this point
01:04:10  [STALE_DISCARDED: 221]   ← Only 1 more in 30 seconds
01:04:59  [STALE_DISCARDED: 231]   ← 10 more after background/foreground cycle
```

User reported video degradation during these periods, correlating with:
- Frames being discarded (possibly including IDRs)
- Or near-stale frames (40-150ms) being decoded and poisoning references

### Timeline Correlation

```
01:03:40  FPS: 37.2, Frame lag: 27     ← Looked OK in metrics
01:04:10  FPS: 59.8, Frame lag: 0      ← Metrics looked excellent
01:04:59  FPS: 14.2, Frame lag: 447    ← After background return, lag spike
```

**Key insight**: FPS metrics looked healthy, but user saw corruption. This confirms decoder poisoning - frames ARE being decoded, just incorrectly.

---

## Implemented Fixes for Rev [57] (2026-01-30)

### Fix 0: 🚨 CRITICAL - Fix Staleness Time Base Bug ✅ IMPLEMENTED

**Problem**: Current code compares epoch time to stream-relative PTS (incompatible).

**Options**:

**Option A: Track Buffer Residence Time (Recommended)**
```java
// Add arrival timestamp to ring buffer packets
private long lastPacketArrivalNanos = 0;

// In processDataDirectWithPts:
long arrivalNanos = System.nanoTime();
// Store arrivalNanos with packet in ring buffer

// In feedCodec:
long bufferResidenceMs = (System.nanoTime() - packet.arrivalNanos) / 1_000_000;
if (bufferResidenceMs > FRAME_STALE_THRESHOLD_MS) {
    // Frame waited too long in buffer - discard
}
```

**Option B: Disable Staleness Check Entirely**
```java
// Comment out the broken staleness check
// Rely on: IDR gating + stall detection + buffer overflow handling
// These mechanisms already exist and work correctly
```

**Option C: Establish PTS Base at Session Start**
```java
// On first frame, establish offset between clocks
private long ptsToEpochOffsetMs = 0;
private boolean ptsBaseEstablished = false;

if (!ptsBaseEstablished) {
    ptsToEpochOffsetMs = System.currentTimeMillis() - sourcePtsMs;
    ptsBaseEstablished = true;
}
long expectedArrivalMs = sourcePtsMs + ptsToEpochOffsetMs;
long frameAgeMs = currentTime - expectedArrivalMs;
```

**Recommendation**: Option B (disable) is safest for Rev [57]. The existing IDR gating and stall detection already handle the critical cases. The broken staleness check provides no benefit and causes IDR drops.

**✅ IMPLEMENTED**: Option B - Staleness check disabled with TODO for proper implementation (Option C with 40ms threshold, IDR protection).

### Fix 1: Tighten Staleness Threshold (DEFERRED) 📝 TODO IN CODE

~~**Change**: Reduce `FRAME_STALE_THRESHOLD_MS` from 150ms to 40ms~~

**DEFERRED**: This change is meaningless until Fix 0 is implemented. The current staleness calculation produces ~1.7 trillion ms regardless of threshold.

### Fix 2: Protect IDR/SPS/PPS from Staleness Discard (SUPERSEDED by Fix 0)

**Change**: Check NAL type BEFORE discarding for staleness

**Note**: Not implemented separately - Fix 0 disables the staleness check entirely, making this unnecessary.

```java
// Before: Discard without checking NAL type
if (frameAgeMs > FRAME_STALE_THRESHOLD_MS && !ringBuffer.isEmpty()) {
    staleFramesDiscarded.incrementAndGet();
    return;
}

// After: Peek NAL type and protect critical frames
if (frameAgeMs > FRAME_STALE_THRESHOLD_MS && !ringBuffer.isEmpty()) {
    // Peek at NAL type before discarding
    int nalType = peekNalType(callback, length, skipBytes);

    // NEVER discard IDR (5), SPS (7), or PPS (8) - essential for recovery
    if (nalType == 5 || nalType == 7 || nalType == 8) {
        // IDR/SPS/PPS always accepted regardless of staleness
        log("[PROJECTION] Accepting stale IDR/config frame (age: " + frameAgeMs + "ms)");
    } else {
        // P-frame is stale - discard
        staleFramesDiscarded.incrementAndGet();
        return;
    }
}
```

**Rationale**: IDR frames are the ONLY way to recover from corruption. Discarding them guarantees corruption persists.

### Fix 3: Add Corruption Detection via IDR Tracking ✅ IMPLEMENTED

**Change**: Track IDR decode count and detect when corruption survives IDR

```java
// New fields
private volatile long lastIdrDecodeTime = 0;
private volatile int idrsSinceLastReset = 0;
private volatile boolean corruptionDetected = false;

// In output callback - track IDR effectiveness
if (isIdrFrame) {
    idrsSinceLastReset++;
    lastIdrDecodeTime = System.currentTimeMillis();
}

// New corruption detection
// If we've decoded multiple IDRs but still getting poor frame output ratio,
// the decoder is poisoned and needs hard reset
private void checkCorruptionPersistence() {
    if (idrsSinceLastReset >= 2) {
        long framesSinceReset = totalFramesDecoded.get() - framesAtLastReset;
        long expectedFrames = (System.currentTimeMillis() - resetTimestamp) / 16; // ~60fps

        if (framesSinceReset < expectedFrames * 0.5) {
            // Less than 50% frame output despite multiple IDRs = poisoned
            log("[CORRUPTION_DETECT] Decoder poisoned - resetting");
            reset();
        }
    }
}
```

**Rationale**: Implements the concepts document principle: "If corruption survives one IDR, your decoder state is poisoned. Reset immediately."

### Fix 4: More Aggressive Reset on Quality Degradation (NOT IMPLEMENTED)

**Change**: Add frame output quality monitoring

```java
// Track frame decode success over rolling window
private static final int QUALITY_WINDOW_FRAMES = 60;  // 1 second at 60fps
private static final float MIN_DECODE_RATIO = 0.8f;   // 80% success minimum

// In performance monitoring
float decodeRatio = (float) framesDecodedInWindow / framesReceivedInWindow;
if (decodeRatio < MIN_DECODE_RATIO && idrsSinceLastReset > 0) {
    // Poor decode ratio despite having IDR = poisoned state
    log("[QUALITY_RESET] Decode ratio " + decodeRatio + " below threshold, resetting");
    reset();
}
```

### Fix 5: Reduce Stall Timeout ✅ IMPLEMENTED

**Change**: Reduce `STALL_TIMEOUT_MS` from 500ms to 200ms

```java
// Before
private static final long STALL_TIMEOUT_MS = 500;

// After
private static final long STALL_TIMEOUT_MS = 200;
```

**Rationale**: Faster stall detection means faster recovery. "One reset is cheaper than ten corrupted frames."

---

## Implementation Order

1. **Fix 0** (Fix staleness time base) - 🚨 CRITICAL - Current check is fundamentally broken
2. **Fix 2** (Protect IDR from staleness) - Prevents recovery frame loss
3. **Fix 5** (Reduce stall timeout) - Faster recovery
4. **Fix 3** (Corruption detection) - Detect poisoning that survives IDR
5. **Fix 4** (Quality monitoring) - Additional safety net
6. ~~**Fix 1** (Tighten threshold)~~ - Deferred until time base is fixed

---

## Testing Protocol

### Test 1: Sustained Operation
- Run for 5+ minutes without user intervention
- Video should remain clean throughout
- No progressive degradation

### Test 2: Background/Foreground Cycles
- Switch to homescreen and back multiple times
- Video should recover within 200ms each time
- No corruption accumulation

### Test 3: Stress Test
- Rapid touch interactions during video
- Video should remain responsive
- Ghosting/tailing should not appear

### Success Criteria
- [ ] No ghosting/tailing during normal operation
- [ ] No progressive pixelation
- [ ] Recovery from background in <500ms
- [ ] No frozen frames requiring manual reset

---

## Files to Modify

| File | Changes |
|------|---------|
| `H264Renderer.java` | Staleness threshold, NAL type check, corruption detection |
| `PacketRingByteBuffer.java` | Add `peekNalTypeFromCallback()` if needed |
| `revisions.txt` | Document Rev [57] changes |

---

## References

- `/Users/zeno/Downloads/concepts.txt` - Decoder poisoning theory and rules
- `13_PROJECTION_VIDEO_PHILOSOPHY.md` - Core design philosophy
- `14_PROJECTION_MODEL_IMPLEMENTATION.md` - Rev [56] implementation
- `17_USB_CAPTURE_STREAM_ANALYSIS.md` - Quantitative analysis of 215K frames, 10 sessions
- USB captures: `/Volumes/KING/carlink_native/recording/`

---

## Key Principles (from concepts.txt)

Memorize these for implementation:

1. **"A frame that arrives too late to be useful must never be decoded."**
2. **"Decoding a late frame is worse than dropping it."**
3. **"If corruption survives one IDR, your decoder state is poisoned. Reset immediately."**
4. **"One reset is cheaper than ten corrupted frames."**
5. **"Completeness is the enemy of correctness in live UI video."**

---

## Document History

| Rev | Date | Author | Changes |
|-----|------|--------|---------|
| [57] | 2026-01-30 | Claude | Initial analysis and fix planning |
| [57] | 2026-01-30 | Claude | USB capture analysis, discovered critical time base bug |
| [57] | 2026-01-30 | Claude | Added quantified jitter evidence for 40ms threshold (from 215K frame analysis) |
| [57] | 2026-01-30 | Claude | Implemented: Fix 0 (disable staleness), Fix 3 (corruption detection), Fix 5 (stall timeout) |
| [57] | 2026-01-30 | Claude | Compiled app-debug.apk. Updated implementation status markers. |
