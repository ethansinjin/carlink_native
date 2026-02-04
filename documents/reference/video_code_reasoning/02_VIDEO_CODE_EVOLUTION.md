# Video Code Evolution History

## Document Created: 2026-01-29
## Purpose: Track all video code changes, rationale, and outcomes
## Next Revision: [56]

---

## Revision Timeline Summary

```
ORIGINAL CARLINK (Flutter - carlink_90 to carlink_102):
- Had ByteBuffer.wrap() race condition (NEVER identified)
- Fixed: setCallback order, ringBuffer.reset(), softReset
- Extensive troubleshooting docs but missed fundamental bug

CARLINK_NATIVE:
[1-4]   Initial development, basic video output
[9]     Video code reverted to Flutter v1.9.0 (inherited race condition)
[11-13] Video rewrite → claimed "STABLE" (race condition still present)
[16]    Timer → Coroutine change
[18]    PlatformDetector hardware decoder fix
[22]    "Return to App Blank Video" - race condition symptom
[28]    "Video black screen" - race condition symptom
[30]    Aggressive recovery logic → UNSTABLE (treating symptoms)
[31]    Race condition FINALLY FOUND AND FIXED (System.arraycopy)
[32]    readPacketInto() optimization
[40-41] SPS/PPS caching, USB capture analysis
[45-46] SPS/PPS fixes, Google H264 guidelines
[51-52] Intel VPU workaround
[54]    GM AAOS optimization, Source PTS, Quality Control
[55]    Current - Audio types, video changes
```

---

## CRITICAL: Bug Inherited from Original Carlink

### The ByteBuffer.wrap() Race Condition

**Original carlink (Flutter) had this bug in PacketRingByteBuffer.java:**
```java
ByteBuffer readPacket() {
    synchronized (this) {
        // ...
        ByteBuffer result = ByteBuffer.wrap(buffer, startPos, actualLength);  // BUG!
        return result;
    }
}
```

**The Problem:**
- `ByteBuffer.wrap()` creates a view into the ring buffer's memory
- Once returned, the lock is released
- USB thread can overwrite the same memory region
- Result: H.264 reference frame corruption, progressive degradation

**What Original Carlink Team Fixed (Dec 2025):**
1. setCallback order (must be before configure)
2. ringBuffer.reset() on codec reset
3. softReset() with flush()
4. Async vs sync mode for Intel

**What They MISSED:**
- The ByteBuffer.wrap() race condition was never identified
- Their troubleshooting docs (`video_instability_analysis.md`, `video_investigation_dec5.md`) don't mention it
- The bug was carried into carlink_native

**When Finally Found:**
- carlink_native [31] (Dec 28, 2025): "Root cause: PacketRingByteBuffer.readPacket() used zero-copy ByteBuffer.wrap() sharing memory with ring buffer."

---

## Detailed Revision Analysis

### [13] - FALSELY LABELED "STABLE" (Dec 8, 2025)

**Change:** "Video Frame message not being recovered properly. Corrected for stable Video."

**⚠️ CRITICAL FINDING: Rev [13] was NOT truly stable. It had TWO latent bugs:**

#### Bug 1: Wrong Decoder Name (Worked by Accident)

```java
// Rev [13] initCodec() - WRONG decoder name hardcoded
try {
    // Try Intel Quick Sync decoder first (known to work on GM gminfo3.7)
    codec = MediaCodec.createByCodecName("OMX.Intel.VideoDecoder.AVC");  // WRONG!
    codecName = "OMX.Intel.VideoDecoder.AVC (Intel Quick Sync)";
} catch (Exception e) {
    // Fallback to generic hardware decoder
    codec = MediaCodec.createDecoderByType("video/avc");  // This selected correct decoder
}
```

**The Problem:**
- Hardcoded decoder name: `"OMX.Intel.VideoDecoder.AVC"` (does not exist)
- Actual Intel decoder on GM AAOS: `"OMX.Intel.hw_vd.h264"`
- PlatformDetector.kt correctly identifies `"OMX.Intel.hw_vd.h264"` but H264Renderer didn't use it
- The fallback `createDecoderByType("video/avc")` selected the correct decoder
- **Video worked by accident, not by design**

**Evidence:**
- [18] explicitly states: "PlatformDetector was not used correctly, Fixed. Should now detected the hardware decoder and use it."

#### Bug 2: Race Condition in Ring Buffer (Caused Progressive Degradation)

```java
// Rev [13] PacketRingByteBuffer.readPacket() line 320
// Zero-copy wrap - shares memory with ring buffer for maximum performance
// This matches the verified working implementation in Carlink_goodVideo
// The ring buffer design ensures data is not overwritten before consumption  ← WRONG!
ByteBuffer result = ByteBuffer.wrap(buffer, startPos, actualLength);  // BUG!
return result;
```

**The Problem:**
- `ByteBuffer.wrap()` shares memory with ring buffer
- USB write thread could overwrite data before MediaCodec consumed it
- Caused progressive H.264 reference frame corruption
- Degradation appeared gradual, not immediate
- **Fixed in [31] with System.arraycopy()**

#### Why [13] Appeared Stable Initially:

1. Video started working (fallback decoder selection succeeded)
2. Race condition degradation was gradual, not immediate
3. Simple code reduced OTHER failure modes
4. Testing may not have run long enough to observe degradation

#### Timeline: Symptoms of Latent Bugs Emerging

| Rev | Issue | Likely Cause |
|-----|-------|--------------|
| [13] | "Stable" | Both bugs present but not yet manifested |
| [22] | "Return to App Blank Video" | Race condition degradation? |
| [28] | "Video black screen" | Race condition degradation? |
| [30] | "Unstable, aggressive recovery" | Attempts to fix symptoms |
| [31] | Race condition identified and fixed | Root cause found |

**Code Characteristics (1060 lines):**
- Simple callback-based feeding (fewer failure modes)
- ArrayList for buffer indices (synchronized)
- No Quality Control (all frames processed)
- No Source PTS (uses 0 for all timestamps)

**Key Lesson:**
> "Stable" was premature. The issues that emerged in [22], [28], and [30] were likely symptoms of the race condition bug in [13]. The aggressive recovery logic in [30] was treating symptoms, not the disease. [31] finally found the actual root cause.

---

### [18] - PLATFORMDETECTOR FIX (Dec 2025)

**Change:** "PlatformDetector was not used correctly, Fixed. Should now detected the hardware decoder and use it."

**What This Fixed:**
- Rev [13] hardcoded wrong decoder name: `"OMX.Intel.VideoDecoder.AVC"`
- Now properly uses PlatformDetector to identify: `"OMX.Intel.hw_vd.h264"`
- Decoder selection no longer relies on fallback path

**However:** The race condition bug in PacketRingByteBuffer was still present.

---

### [22] - VIDEO ISSUES EMERGE (Dec 2025)

**Change:** "Auto reconnect attempt on adapter disconnect. Return to App Blank Video fixed?"

**Analysis:**
The "Return to App Blank Video" issue is consistent with race condition degradation:
- H.264 reference frames corrupted by USB thread overwriting ring buffer
- Decoder outputs black/garbage because P-frames reference corrupted data
- Only full reset would temporarily fix (clears buffer)

---

### [28] - MORE VIDEO ISSUES (Dec 2025)

**Change:** "App resume testing for Video black screen."

**Analysis:**
Same pattern as [22] - video degradation requiring investigation. The attempts to fix via "app resume testing" were treating symptoms of the race condition.

---

### [30] - UNSTABLE AGGRESSIVE RECOVERY (Dec 27, 2025)

**Change:** "Video Code change TESTING. Seems unstable or long to recover, too aggressive and compounding recovery logic."

**Code Characteristics (1454 lines, +37% from [13]):**

**New Features Added:**
1. ConcurrentLinkedQueue instead of ArrayList
2. Presentation timestamp tracking
3. Enhanced reset throttling
4. Pause/resume lifecycle handling
5. Surface change handling via setOutputSurface()

**Critical Issues Identified:**
- "too aggressive and compounding recovery logic"
- Recovery mechanisms triggering each other
- Long time to recover once degraded

**Lesson Learned:**
> Complex recovery logic can make problems worse. Each recovery mechanism must be carefully designed to not trigger other recovery mechanisms.

---

### [31] - RACE CONDITION FIX (Dec 28, 2025)

**Change:** "FIX: Progressive video quality degradation. Root cause: PacketRingByteBuffer.readPacket() used zero-copy ByteBuffer.wrap() sharing memory with ring buffer. Race condition allowed USB writes to overwrite data before MediaCodec consumed it, corrupting H.264 reference frames."

**Root Cause Analysis:**
```java
// BEFORE (Bug): Zero-copy shared memory
public ByteBuffer readPacket() {
    return ByteBuffer.wrap(buffer, readPosition, length);  // Shares memory!
}

// USB thread writes here while codec still reading → CORRUPTION
```

**Fix:**
```java
// AFTER: Safe copy
public ByteBuffer readPacket() {
    byte[] copy = new byte[length];
    System.arraycopy(buffer, readPosition, copy, 0, length);
    return ByteBuffer.wrap(copy);  // Independent copy
}
```

**Why Periodic Keyframes Didn't Help:**
> "Periodic keyframes didn't help because they were also corrupted; only reset() worked because it cleared the buffer first."

**Lesson Learned:**
> When sharing memory between threads, data corruption may not be immediately obvious. H.264 reference frame corruption causes progressive degradation that looks like encoder issues.

---

### [32] - OPTIMIZATION (Dec 29, 2025)

**Change:** "OPTIMIZATION: Eliminated double-copy in video pipeline. Added readPacketInto(ByteBuffer) method that copies directly from ring buffer to MediaCodec input buffer, eliminating intermediate byte[] allocation."

**New Method:**
```java
public int readPacketInto(ByteBuffer dest) {
    // Copy directly from ring buffer to MediaCodec buffer
    // No intermediate allocation
}
```

**Performance Improvement:**
- Reduced GC pressure by ~3600 allocations/minute at 60fps
- Reduced CPU overhead by ~2-3%

---

### [40] - SPS/PPS CACHING (Jan 11, 2026)

**Change:** "Testing Video Code caching of keyframe used as reference for P-Frames. Shorting IDR request from 5 to 2 seconds."

**Rationale:**
- Cache SPS/PPS from stream for injection after codec reset
- Reduce IDR request interval for faster recovery

---

### [45] - SPS/PPS CACHE FIX (Jan 12, 2026)

**Change:** "SPS/PPS Cache fix"

**Issue Fixed:** SPS/PPS caching had bugs that prevented proper injection

---

### [51] - SKIP PREPEND WHEN SPS PRESENT (Jan 21, 2026)

**Change:** "Video; Skip Prepending When SPS Already Present, Add Diagnostic Logging for Corruption Analysis, Updated Incorrect Comment about once per session SPS+PPS. Its every frame, Increased Search Limit on IDR Frame check."

**Key Discovery:**
> The adapter sends SPS+PPS with EVERY IDR frame, not just once at session start. This is important for understanding recovery behavior.

---

### [52] - INTEL VPU WORKAROUND (Jan 22, 2026)

**Change:** "Some more Video tweaks. If Intel platform will attempt FULL flush, destroy, recreate of codec. Video issues might be faulty General Motor + Intel Software Design on gminfo3.7 - 3.8 hardware."

**Problem Identified:**
```java
// Intel VPU issue: flush() doesn't properly reset reference frames
// P-frames continue to use stale reference data → corruption
mCodec.flush();
mCodec.start();  // Still has corrupted reference frame state
```

**Workaround:**
```java
if (requiresIntelVpuWorkaround) {
    // Full recreation instead of flush
    mCodec.stop();
    mCodec.release();
    mCodec = MediaCodec.createByCodecName(decoderName);
    mCodec.configure(format, surface, null, 0);
    mCodec.setCallback(callback, handler);
    mCodec.start();
}
```

**Code Growth:** 1558 lines (+7% from [30])

---

### [54] - GM AAOS OPTIMIZATION (Jan 29, 2026)

**Change:** "GM AAOS (gminfo3.7) optimization testing. Use Video PTS instead of synthetic timing, lower buffer sizes. Airplay.conf changes (dam icon label). Audio Thread priority change"

**Major Changes:**

**1. Source PTS Instead of Synthetic:**
```java
// NEW: Queue to track source timestamps
private final ConcurrentLinkedQueue<Long> sourcePtsQueue = new ConcurrentLinkedQueue<>();

// Write path: enqueue PTS
sourcePtsQueue.offer(ptsMs * 1000L);  // Convert to microseconds

// Read path: dequeue PTS
Long sourcePts = sourcePtsQueue.poll();
long pts = (sourcePts != null) ? sourcePts : syntheticPts;
```

**Rationale:**
- Better A/V synchronization
- Frame drop detection via PTS gaps
- Match GM CINEMO behavior

**2. Reduced Buffer Sizes:**
```java
// BEFORE (rev 52)
if (pixels <= 1920 * 1080) return 8 * 1024 * 1024;   // 8MB
if (pixels <= 2400 * 960) return 16 * 1024 * 1024;   // 16MB

// AFTER (rev 54)
if (pixels <= 1920 * 1080) return 2 * 1024 * 1024;   // 2MB
if (pixels <= 2400 * 960) return 4 * 1024 * 1024;    // 4MB
```

**Rationale:**
- USB capture analysis showed 98.5% of frames arrive within 100ms
- Large gaps are source-side issues, not fixable by buffering
- Match GM native stack philosophy
- Reduce memory pressure and latency

**3. Adaptive Quality Control:**
```java
// Drop P-frames when decoder is behind
private static final int QUALITY_CONTROL_LAG_THRESHOLD = 5;
private static final int QUALITY_CONTROL_CRITICAL_LAG = 15;

if (qualityControlActive && nalType == 1) {
    // Drop P-frame
    codecAvailableBufferIndexes.offer(index);
    sourcePtsQueue.poll();  // Must stay in sync!
    return true;
}
```

**Rationale:**
- Match GM AAOS CINEMO's QualityControl() behavior
- Help decoder catch up when behind
- Prevent unbounded lag growth

---

### [55] - CURRENT (Jan 29, 2026)

**Change:** "Audio Types based on recvd protocol command grouping for GM AAOS independent volume control via their own track. Video Code changes"

**Code Size:** 1888 lines (+21% from [52], +78% from [13])

**Video Changes in [55]:**
- Additional state checks in feedCodec()
- Enhanced pause/resume handling
- More early returns for state validation

---

## Code Size Evolution

| Revision | Lines | Change | Stability |
|----------|-------|--------|-----------|
| [13] | 1060 | Baseline | STABLE |
| [30] | 1454 | +37% | UNSTABLE |
| [52] | 1558 | +47% | Unknown |
| [55] | 1888 | +78% | UNSTABLE |

**Observation:** Code complexity has nearly doubled since the stable baseline, with each addition introducing potential failure modes.

---

## Key Architectural Changes Summary

| Feature | Rev 13 | Rev 55 | Risk |
|---------|--------|--------|------|
| Buffer indices | ArrayList (sync) | ConcurrentLinkedQueue | Race conditions |
| Ring buffer read | readPacket() (copy) | readPacketInto() (direct) | None (improvement) |
| Timestamps | Synthetic (0) | Source PTS queue | Queue desync |
| Quality Control | None | Adaptive P-drop | Over-dropping |
| Buffer sizes | 8-64 MB | 2-8 MB | Less headroom |
| Intel workaround | None | Full recreation | Slower reset |
| State checks | Minimal | Extensive | Early returns |

---

## Patterns Identified

### Pattern 1: Complexity Breeds Instability
Each feature addition has introduced new state to manage and new potential failure modes. The stable [13] was simple; the unstable [55] has multiple interacting systems.

### Pattern 2: Recovery Logic Can Compound
[30] explicitly noted "compounding recovery logic" as a problem. When multiple recovery mechanisms exist, they can trigger each other in a loop.

### Pattern 3: Optimization Can Reduce Resilience
- Smaller buffers = less cushion for timing variations
- Quality control = frames lost that might have been decodable
- Source PTS = additional synchronization requirement

### Pattern 4: Platform-Specific Issues
The Intel VPU workaround was necessary but adds complexity. The GM AAOS environment has unique characteristics (CINEMO, video focus) that may interact with the app.

---

## Questions for Investigation

1. **Why does surface recreation fix the issue temporarily?**
   - Full codec recreation with fresh state
   - Clears any accumulated bad state
   - But why does it degrade again within 30 seconds?

2. **Is the Source PTS queue staying synchronized?**
   - Every ring buffer write must have matching PTS enqueue
   - Every ring buffer read must have matching PTS dequeue
   - Quality control drops must also dequeue PTS

3. **Is Quality Control helping or hurting?**
   - If the issue is callback frequency, dropping frames doesn't help
   - It just loses data while not addressing root cause

4. **Why are codec input callbacks so infrequent?**
   - logcat shows 3,461 ring buffer writes but only 5 codec inputs
   - Callback IS firing (SPS+PPS injection works)
   - Something is preventing frame data processing

---

## Recommendations for [56]

Based on this analysis, consider testing a simplified approach:

1. **Remove Quality Control** (or disable by default)
2. **Remove Source PTS queue** (use synthetic timestamps)
3. **Increase buffer sizes** back to original values
4. **Simplify callback** to direct feed without multiple state checks
5. **Add detailed logging** for callback execution path

This would help isolate whether the complexity added since [13] is causing the instability.
