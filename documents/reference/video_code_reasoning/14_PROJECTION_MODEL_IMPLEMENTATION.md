# Projection Model Implementation

## Document Created: 2026-01-29
## Purpose: Document the restructuring of H264Renderer for projection video model
## Revision: [56] - Major architectural change

---

## Executive Summary

H264Renderer was restructured from a **video playback model** to a **projection video model** based on the understanding that CarPlay/Android Auto is live interactive UI, not cinema playback.

Key changes:
1. Reduced buffer from 2-4MB to 192KB (jitter buffer only)
2. Added staleness check - discard frames >150ms old at entry
3. Skip to newest IDR on overflow (not oldest-first processing)
4. Implemented decoder discipline (IDR gate, stall timeout)
5. Removed Quality Control system entirely
6. Simplified to monotonic synthetic PTS

---

## Philosophy: Projection vs Playback

### The Fundamental Mistake (Before)

The code treated projection video like a streaming video file:
- Deep buffering (2-4MB, 120 packets = ~2 seconds)
- Sequential backlog processing (oldest first)
- Counter-based lag detection
- Quality Control dropping frames from buffer

### The Correct Approach (After)

Projection video is live interactive UI:
- Shallow jitter buffer (192KB, 12 packets = ~200ms)
- Discard stale frames at entry
- Skip to newest IDR when overwhelmed
- Time-based staleness, not counter-based lag

### Design Principles

```
[H264 stream] → [Staleness Check] → [Jitter Buffer] → [Decode ASAP] → [Render ASAP]
                      │                   │                │
                      │                   │                └── Callback processes ALL
                      │                   │                    frames in loop
                      │                   │
                      │                   └── 192KB, ~12 packets max
                      │                       Skip to newest IDR if full
                      │
                      └── Discard if >150ms old (unless buffer empty)
```

---

## Code Changes Summary

### Constants Changed

| Before | After | Reason |
|--------|-------|--------|
| `MAX_BUFFER_PACKETS = 120` | `MAX_BUFFER_PACKETS = 12` | ~200ms jitter buffer, not 2s playback buffer |
| Buffer size 2-4MB | Buffer size 192KB | Fixed jitter buffer, not resolution-scaled |
| `QUALITY_CONTROL_LAG_THRESHOLD = 5` | Removed | QC was counterproductive |
| Source PTS queue | Removed | Monotonic synthetic PTS only |

### New Constants Added

```java
// Staleness threshold - frames older than this are discarded at entry
private static final int FRAME_STALE_THRESHOLD_MS = 150;

// IDR Gate - wait for IDR before decoding P-frames
private volatile boolean waitingForIdr = true;

// Stall Detection
private static final long STALL_TIMEOUT_MS = 500;
```

### Methods Changed

| Method | Change |
|--------|--------|
| `calculateOptimalBufferSize()` | Returns fixed 192KB instead of 2-8MB |
| `processDataDirectWithPts()` | Added staleness check, skip-to-newest-IDR |
| `fillFirstAvailableCodecBuffer()` | Added IDR gate, stall tracking |
| `onInputBufferAvailable()` | Added IDR gate, stall tracking |
| `onOutputBufferAvailable()` | Added `lastOutputTimeMs` tracking |
| `start()` | Reset decoder discipline state |
| `reset()` | Reset `waitingForIdr`, stall tracking |

### Methods Added

| Method | Purpose |
|--------|---------|
| `scheduleStallCheck()` | Detect decoder stall (input but no output) |

### Code Removed

- Quality Control system (`qualityControlActive`, `aggressiveDropActive`, etc.)
- Source PTS queue (`sourcePtsQueue`, `useSourcePts`)
- Frame drop detection via PTS gaps (`detectFrameDrops()`)
- `getDroppedFrameCount()` public method

---

## Decoder Discipline Implementation

### Rule 1: IDR Gate

Don't decode P-frames until first IDR received.

```java
if (waitingForIdr) {
    if (isIdrFrame) {
        waitingForIdr = false;
        log("[IDR_GATE] First IDR received - decoder ready");
    } else if (nalType != 7 && nalType != 8) {
        // P-frame before IDR - discard
        framesDiscardedBeforeIdr.incrementAndGet();
        codecAvailableBufferIndexes.offer(index);
        return true;  // Continue to next packet
    }
    // SPS/PPS (7,8) fall through to be queued
}
```

### Rule 2: Stall Timeout

Reset decoder if no output despite input for 500ms.

```java
private void scheduleStallCheck() {
    // ... scheduled check ...
    if (timeSinceInput < 200 && timeSinceOutput > STALL_TIMEOUT_MS) {
        log("[STALL_DETECT] Decoder stalled");
        waitingForIdr = true;
        keyframeCallback.onKeyframeNeeded();
        reset();
    }
}
```

### State Transitions

```
Session Start:
  waitingForIdr = true
  → Discard P-frames
  → First SPS+PPS+IDR arrives
  → waitingForIdr = false
  → Normal decoding

Stall Detected:
  → waitingForIdr = true
  → Request keyframe
  → Reset decoder
  → Wait for new IDR
  → Resume

Codec Reset:
  → waitingForIdr = true
  → Clear buffers
  → Request keyframe
```

---

## USB Capture Analysis

### Pi-carplay Capture (for reference)

| Metric | Value |
|--------|-------|
| Session duration | 128.5s |
| IDR frames | 1 (at start only) |
| SPS/PPS | 1 each |

Pi-carplay doesn't request keyframes, so only initial IDR is received.

### carlink_native Capture (actual app behavior)

| Metric | Value |
|--------|-------|
| Session duration | 169.0s |
| IDR frames | 88 |
| SPS/PPS | 88 each (with every IDR) |
| IDR interval | avg 1.4s (min 75ms, max 2.1s) |

**Key findings:**
1. Session starts with `SPS+PPS+IDR` - correct initialization
2. Adapter sends periodic IDRs when app requests them (~1.4s interval)
3. SPS+PPS always accompanies IDR (bundled together)
4. Keyframe request mechanism works correctly

### NAL Unit Distribution (carlink_native 169s session)

| NAL Type | Count | Description |
|----------|-------|-------------|
| 1 (P-slice) | 2856 | Inter-predicted frames |
| 5 (IDR) | 88 | Instantaneous Decoder Refresh |
| 7 (SPS) | 88 | Sequence Parameter Set |
| 8 (PPS) | 88 | Picture Parameter Set |

### Stream Structure Confirmed

```
[SPS+PPS+IDR] → [P] → [P] → ... → [SPS+PPS+IDR] → [P] → ...
     │                                  │
     └── Session start                  └── ~1.4s interval (on request)
```

---

## Validation Against Philosophy

### Decode Rules

| Rule | Implementation |
|------|----------------|
| Feed complete NAL units | ✓ Ring buffer handles packet boundaries |
| Start only on IDR | ✓ IDR gate implemented |
| Reset decoder on corruption | ✓ Error callback triggers reset |
| Allow frame drops | ✓ Staleness check discards late frames |
| Never block decode on timing | ✓ No PTS ordering dependency |

### PTS Rules

| Rule | Implementation |
|------|----------------|
| Use monotonic synthetic PTS | ✓ `presentationTimeUs.getAndAdd(frameDurationUs)` |
| Never wait for late frame | ✓ Staleness check at entry |
| Prefer newest frame | ✓ Skip-to-newest-IDR on overflow |

### Quality Diagnosis

| Symptom | Cause | Fix |
|---------|-------|-----|
| Visual corruption | Bitstream/reference problem | IDR gate, keyframe request |
| Jitter/stuttering | Timing problem | Jitter buffer handles |

---

## Files Modified

### H264Renderer.java

Lines changed: ~200+ (restructured)

Key sections:
- Lines 131-170: Projection model constants and decoder discipline variables
- Lines 180-200: Buffer size calculation (now fixed 192KB)
- Lines 392-437: `fillFirstAvailableCodecBuffer()` with IDR gate
- Lines 448-510: `scheduleStallCheck()` method (new)
- Lines 960-1030: `processDataDirectWithPts()` with staleness check
- Lines 1237-1260: `onInputBufferAvailable()` with IDR gate
- Lines 1305-1312: `onOutputBufferAvailable()` with stall tracking

### PacketRingByteBuffer.java

Lines added: ~130

New methods:
- `peekNalType()` - Peek NAL type without consuming (lines 448-488)
- `skipToNewestIdr()` - Skip to newest IDR on overflow (lines 497-553)
- `scanNalTypeAt()` - Helper for NAL scanning (lines 558-573)

---

## Testing Notes

### Build Verification

```
BUILD SUCCESSFUL in 1s
37 actionable tasks: 5 executed, 32 up-to-date
```

### Expected Behavior

1. **Session start**: First packet should be SPS+PPS+IDR, then P-frames decode normally
2. **Stale frames**: Frames >150ms old discarded at entry (logged as `[PROJECTION]`)
3. **Buffer overflow**: Skip to newest IDR (logged as `[PROJECTION] Skipped N stale packets`)
4. **Decoder stall**: Reset and request keyframe after 500ms without output
5. **IDR gate**: P-frames before first IDR discarded (logged as `[IDR_GATE]`)

### Monitoring

Performance logs now show:
- `[STALE_DISCARDED: N]` - Frames discarded due to staleness
- `[IDR_GATE]` - IDR gate state changes
- `[STALL_DETECT]` - Stall detection and recovery
- `[PROJECTION]` - Buffer overflow handling

---

## References

- Document 12: GM CINEMO vs carlink_native Analysis
- Document 13: Projection Video Philosophy
- USB captures: `/Volumes/KING/carlink_native/recording/`
- Pi-carplay captures: `/Users/zeno/.pi-carplay/usb-capture/`

---

## Revision History

| Rev | Date | Changes |
|-----|------|---------|
| [56] | 2026-01-29 | Projection model implementation, decoder discipline |
