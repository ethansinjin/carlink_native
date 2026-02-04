# Synthesis and Recommendations

## Document Created: 2026-01-29
## Purpose: Consolidated findings and actionable recommendations
## Target Revision: [56]

---

## Executive Summary

The video pipeline in revision [55] suffers from **codec input starvation** caused by a combination of:

1. **Quality Control triggering too early** (lag threshold of 5 frames)
2. **Quality Control not solving the root problem** (callback frequency, not frame content)
3. **Increased code complexity** creating multiple failure points
4. **Reduced buffer sizes** providing less cushion for timing variations

**Critical Discovery:** Rev [13] was NOT truly stable - it had two latent bugs:
1. Wrong decoder name (worked by accident via fallback)
2. Race condition in ByteBuffer.wrap() (fixed in [31])

The issues in [22], [28], [30] were symptoms of the race condition, not new bugs.

---

## Root Cause Analysis

### Primary Root Cause: Callback-to-Write Rate Mismatch

**Evidence:**
- 3,461 ring buffer writes over session
- Only ~5-16 codec inputs logged
- Ratio: <0.5%

**Hypothesis:**
The `onInputBufferAvailable` callback doesn't fire at 60 Hz consistently. When USB writes (60 Hz) outpace callbacks, lag builds instantly.

**Contributing Factors:**
1. Intel VPU may provide input buffers at a different rate than expected
2. CINEMO service may affect MediaCodec scheduling
3. Multiple threads competing for resources

### Secondary Root Cause: Quality Control Counterproductive

**Evidence:**
- 14,000+ frames dropped by QC
- Situation never improved despite dropping
- Dropping can't help if problem is callback frequency

**Mechanism:**
```
USB: 60 frames/sec write
Callback: 30 frames/sec (example)
Result: 30 frames/sec accumulation

QC Response: Drop P-frames
But: Still only 30 callback/sec
Result: Still 30 frames/sec accumulation (just losing data)
```

### Tertiary Root Cause: Complexity Overload

**Rev [13] callback: ~50 lines, 2 checks**
**Rev [55] callback: ~150 lines, 10+ checks**

More code = more failure paths = more chances to not process frames

---

## Findings Summary

| Finding | Severity | Evidence |
|---------|----------|----------|
| Codec input starvation | CRITICAL | 3461 writes vs 5 inputs |
| QC dropping 14K+ frames | HIGH | Logcat QC stats |
| QC activated immediately | HIGH | Lag 29 at first frame |
| Surface recreation helps | MEDIUM | 25.6 FPS during stable |
| Recovery lasts ~30 sec | HIGH | Degradation resumes |
| IDR count always 0 | MEDIUM | NAL stats |
| Callback IS firing | MEDIUM | SPS/PPS injection works |

---

## Recommended Changes for [56]

### Priority 1: Disable Quality Control (Temporary Diagnostic)

**Rationale:** Determine if QC is causing more harm than good

**Change:**
```java
// In H264Renderer.java
private static final int QUALITY_CONTROL_LAG_THRESHOLD = 999999;  // Effectively disabled
// OR
private volatile boolean qualityControlEnabled = false;  // Config option
```

**Expected Outcome:**
- If video becomes stable → QC was the problem
- If video still unstable → root cause is elsewhere

### Priority 2: Simplify Callback Path

**Rationale:** Reduce failure points in critical path

**Change:** In `onInputBufferAvailable()`, reduce to essential logic:
```java
public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
    synchronized (codecLock) {
        if (codec != mCodec || mCodec == null || !running) return;

        // SPS/PPS injection (keep)
        if (codecConfigPending && injectCodecConfigToBuffer(codec, index)) return;

        // Simplified frame processing
        if (!ringBuffer.isEmpty()) {
            ByteBuffer buf = codec.getInputBuffer(index);
            int size = ringBuffer.readPacketInto(buf);
            if (size > 0) {
                codec.queueInputBuffer(index, 0, size, 0, 0);  // Simple PTS
                return;
            }
        }
        codecAvailableBufferIndexes.offer(index);
    }
}
```

### Priority 3: Increase Buffer Sizes

**Rationale:** Provide more cushion for timing variations

**Change:**
```java
// Revert to original sizes
if (pixels <= 2400 * 960) return 8 * 1024 * 1024;   // 8MB (was 4MB)
```

### Priority 4: Remove Source PTS Queue (Temporary)

**Rationale:** Eliminate synchronization complexity

**Change:**
```java
private volatile boolean useSourcePts = false;  // Disable by default
```

Use synthetic timestamps (0 or monotonic) until stability is achieved.

### Priority 5: Add Diagnostic Logging

**Rationale:** Understand exactly what's happening

**Add to callback:**
```java
private final AtomicLong callbackCount = new AtomicLong(0);

public void onInputBufferAvailable(...) {
    long count = callbackCount.incrementAndGet();
    if (count % 100 == 0) {
        log("[CALLBACK_DIAG] Callback #" + count +
            ", ringBuffer.size=" + ringBuffer.availablePacketsToRead() +
            ", indices.size=" + codecAvailableBufferIndexes.size());
    }
    // ... rest of method
}
```

---

## Testing Protocol for [56]

### Test 1: Baseline Without QC

1. Disable Quality Control (set threshold to 999999)
2. Use synthetic timestamps (disable Source PTS)
3. Increase buffer to 8MB
4. Run for 5 minutes
5. Monitor: FPS, frame lag, decode latency

**Pass Criteria:**
- FPS > 20 sustained
- Frame lag < 100
- No 45+ second decode latency

### Test 2: QC with Higher Threshold

If Test 1 passes:
1. Enable QC with threshold = 60 (1 second at 60fps)
2. Run for 5 minutes
3. Monitor same metrics

**Pass Criteria:**
- Same as Test 1
- QC activations < 5

### Test 3: Source PTS Enabled

If Test 2 passes:
1. Enable Source PTS queue
2. Add PTS queue depth logging
3. Run for 5 minutes
4. Monitor: PTS queue depth stability

**Pass Criteria:**
- PTS queue depth < 10 throughout
- No timestamp anomalies

---

## Configuration Matrix

| Config | QC | Source PTS | Buffer | Risk |
|--------|-----|------------|--------|------|
| A (Safe) | OFF | OFF | 8MB | Lowest |
| B | lag≥60 | OFF | 8MB | Low |
| C | lag≥30 | OFF | 8MB | Medium |
| D | lag≥60 | ON | 8MB | Medium |
| E (Current) | lag≥5 | ON | 4MB | **HIGH** |

Recommend starting with Config A and progressively enabling features.

---

## Long-Term Recommendations

### 1. Consider Rev [31] Architecture for Stability

**Important:** Rev [13] was NOT stable - it had a race condition bug. Rev [31] is the correct baseline.

Rev [31]'s approach after fixing the race condition:
- Direct feed in callback
- No frame dropping
- Safe copy via System.arraycopy() (race condition fix)
- Synchronized buffer handling
- Simple state management

### 2. Investigate Intel VPU Callback Behavior

The Intel decoder may not provide input buffers at the expected rate. Research:
- Intel MediaSDK documentation
- GM AAOS-specific VPU behavior
- Callback frequency under load

### 3. Profile CINEMO Interference

The GM native CarPlay stack runs continuously. Investigate:
- Does CINEMO hold MediaCodec resources?
- Can coexistence be improved?
- Is there a way to request exclusive access?

### 4. Add Telemetry for Field Debugging

In release builds, collect anonymized metrics:
- Callback frequency histogram
- QC activation frequency
- Recovery success rate
- Stable period duration

---

## Summary Table: What Changed Since [31] (First Truly Stable)

**Note:** Rev [13] was NOT stable - it had a race condition bug. Rev [31] is the first truly stable version.

| Feature | Rev [31] | Rev [55] | Recommendation for [56] |
|---------|----------|----------|------------------------|
| Quality Control | None | lag≥5 | Disable or lag≥60 |
| Source PTS | None | Queue-based | Disable initially |
| Buffer Size | 16MB | 4MB | 8MB |
| Callback Logic | Simple | ~150 lines | Simplify |
| State Checks | Minimal | 10+ | Reduce |
| PTS Queue | None | Synchronized | Remove |
| Intel Workaround | None | Full recreation | Keep |
| Race Condition | **FIXED** | Fixed | N/A |

---

## Document References

- `00_RESOURCE_LOCATIONS.md` - Where to find everything
- `01_VIDEO_PIPELINE_ARCHITECTURE.md` - How video flows through system
- `02_VIDEO_CODE_EVOLUTION.md` - What changed over time
- `03_LOGCAT_ANALYSIS.md` - Evidence from captured session
- `04_CURRENT_STATE_ASSESSMENT.md` - Current code analysis
- `05_SYNTHESIS_AND_RECOMMENDATIONS.md` - This document

---

## Revision History

| Date | Revision | Author | Changes |
|------|----------|--------|---------|
| 2026-01-29 | Initial | Claude | Created based on analysis of [55] and logcat |

---

## Next Steps

1. Review this document with stakeholders
2. Decide on [56] configuration (recommend Config A)
3. Implement changes
4. Execute test protocol
5. Document results
6. Iterate based on findings
