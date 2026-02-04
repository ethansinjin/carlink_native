# GM CINEMO vs carlink_native Video Processing Analysis

## Document Created: 2026-01-29
## Purpose: Compare GM native CarPlay video processing to carlink_native to identify root cause of instability
## Key Finding: carlink_native buffers frames that should be dropped; GM processes frames immediately

---

## Executive Summary

**GM CINEMO (native CarPlay) approach:**
- **Direct processing** - Frames go straight from reception to decoder
- **No large buffer** - Minimal or zero buffering between reception and decode
- **Late frame detection** - Checks if CURRENT frame is late vs clock, not lag counters
- **Reference-only mode** - When behind, decode reference frames but may skip display
- **Immediate discard** - Frames with invalid PTS discarded immediately

**carlink_native approach:**
- **Ring buffer** - 4MB buffer stores frames between reception and decode
- **Queue-based feeding** - Frames wait in buffer for callback to process
- **Counter-based lag** - Uses `totalFramesReceived - totalFramesDecoded` (resetting counters)
- **Aggressive P-frame dropping** - Drops at 5-frame lag (200ms), but from BUFFER not display
- **Callback starvation** - 3,461 writes but only ~5 codec inputs

**The fundamental problem:**
carlink_native treats real-time video like a streaming video file, buffering frames for "smooth playback." But CarPlay is interactive - old frames are WORTHLESS. The buffer creates a backlog that grows unbounded because the codec can't keep up.

---

## GM CINEMO Processing Model

### Direct Frame Path (No Buffer)

From `carplay_video_pipeline.md`:

```
iPhone → AirPlay → libNmeCarPlay.so → libNmeVideoSW.so → Display
           │              │                   │
           │              │                   └── H264DeliverAnnexB()
           │              │                       (direct to decoder)
           │              │
           │              └── OnFrame()
           │                  (validate, forward immediately)
           │
           └── NAL units arrive
```

**Key observation:** There's no ring buffer between `OnFrame()` and `H264DeliverAnnexB()`. Frames go directly from reception to decoder.

### OnFrame() Processing (libNmeCarPlay.so)

```cpp
OnFrame() {
    // Validate NAL unit
    if (nalu_size invalid) {
        log("OnFrame() invalid nalu_size");
        return;  // DISCARD immediately
    }

    if (data corrupt) {
        log("OnFrame() corrupt video data");
        return;  // DISCARD immediately
    }

    // Log and forward
    log("OnFrame() received video frame: %d");

    // Forward IMMEDIATELY to decoder
    if (append_data_failed) {
        log("OnFrame() append of data failed");
        ForceKeyframe();  // Request recovery
    }
}
```

**No buffering.** Frame arrives → validate → decode → display. If anything fails, discard and request keyframe.

### Quality Control (libNmeVideo.so)

```cpp
QualityControl(timestamp, flags) {
    if (clock_running && frame_late) {
        // Frame is late relative to PRESENTATION CLOCK
        log("QualityControl -> %T late -> reference frames only");

        // Drop B-frames, decode reference frames (IDR/P)
        // May not DISPLAY, but still DECODES reference frames
    }

    if (catching_up_finished) {
        log("QualityControl -> catching up finished");
        // Resume normal operation
    }
}
```

**Critical difference:** GM checks if the CURRENT frame is late vs the PRESENTATION CLOCK, not a counter of total frames. This is real-time timing.

### PTS Validation (libNmeVideo.so)

```cpp
OnSurface() {
    if (no_pts) {
        log("OnSurface() - discard surface with no PTS!");
        return;  // DISCARD frame with bad PTS
    }

    if (non_monotonic_timestamps) {
        log("non-monotonic video timestamps");
        // Handle discontinuity
    }
}
```

**Frames without valid PTS are discarded immediately.** No attempt to buffer and "figure it out later."

---

## carlink_native Processing Model

### Buffered Frame Path

```
CPC200 Adapter → USB Read → Ring Buffer → Callback → MediaCodec → Display
                    │            │             │
                    │            │             └── onInputBufferAvailable()
                    │            │                 (when codec ready)
                    │            │
                    │            └── 4MB buffer
                    │                Packet format: [len][skip][data]
                    │                Can hold 400+ frames at 10KB avg
                    │
                    └── processDataDirectWithPts()
                        (writes to buffer, then tries to feed)
```

### The Buffer Problem

```java
// H264Renderer.java - processDataDirectWithPts()
public void processDataDirectWithPts(int length, int skipBytes, int sourcePtsMs,
                                     PacketRingByteBuffer.DirectWriteCallback callback) {
    // Always write to buffer
    ringBuffer.directWriteToBuffer(length, skipBytes, callback);

    // Try to feed codec
    feedCodec();  // May or may not succeed
}
```

**Problem:** Frames always go to buffer first. If codec can't consume fast enough, buffer grows.

### feedCodec() Limitations

```java
private void feedCodec() {
    // Skip if no saved buffer indices
    if (codecAvailableBufferIndexes.isEmpty()) {
        return;  // EARLY EXIT - no codec buffers available
    }

    executors.mediaCodec1().execute(() -> {
        fillAllAvailableCodecBuffers(mCodec);  // Process what we can
    });
}
```

**Problem:** If MediaCodec hasn't provided buffer indices via callback, this does nothing. Frames accumulate in ring buffer.

### Lag Calculation Flaw

```java
// H264Renderer.java - onInputBufferAvailable()
long frameLag = totalFramesReceived.get() - totalFramesDecoded.get();

if (!qualityControlActive && frameLag >= QUALITY_CONTROL_LAG_THRESHOLD) {
    qualityControlActive = true;
}
```

**Problem:** These counters RESET every 30 seconds in `logPerformanceStats()`:
```java
totalFramesReceived.set(0);
totalFramesDecoded.set(0);
```

So `frameLag` is only meaningful within a 30-second window, and resets to 0 even if ring buffer has 500 queued frames.

### Where Quality Control Fails

```java
if (qualityControlActive && nalType == 1) {
    // Drop P-frame
    codecAvailableBufferIndexes.offer(index);  // Return buffer
    sourcePtsQueue.poll();  // Consume PTS
    return;  // Frame dropped
}
```

**Problem:** This drops frames from the RING BUFFER, not based on whether the frame is late for DISPLAY. If codec callbacks aren't firing, dropping from the buffer doesn't help - the frames being dropped are NEWER than the frames being decoded.

---

## Side-by-Side Comparison

| Aspect | GM CINEMO | carlink_native |
|--------|-----------|----------------|
| **Buffer between receive and decode** | None/minimal | 4MB ring buffer |
| **Frame path** | Direct: receive → decode | Buffered: receive → buffer → callback → decode |
| **Late detection** | Frame PTS vs presentation clock | Counter: received - decoded |
| **Dropping trigger** | Frame is late for DISPLAY | Frame count lag (5+ frames) |
| **What gets dropped** | Current late frame | Frames from buffer (may be newer) |
| **IDR handling** | Always decode (reference) | May be skipped by QC if threshold met |
| **PTS validation** | Discard if invalid | Queue even if invalid, fallback to synthetic |
| **Callback dependency** | Direct function call | Depends on MediaCodec callback timing |

---

## Root Cause Analysis

### Why carlink_native Fails

1. **Buffer creates artificial lag**
   - 4MB buffer can hold 400+ frames
   - Even small decode slowdowns cause accumulation
   - Buffer never drains because codec can't keep up

2. **Counter-based lag is wrong metric**
   - `frameLag = received - decoded` doesn't reflect real-time
   - Counters reset every 30s, losing true backlog
   - Ring buffer depth is the real metric

3. **Quality Control drops wrong frames**
   - Drops from buffer (newest data)
   - Codec may be decoding OLD data from buffer head
   - Dropping new frames while displaying old = worse

4. **Callback starvation not addressed**
   - 3,461 writes, ~5 codec inputs
   - QC can't fix this - it's not a "too many frames" problem
   - It's a "codec not consuming" problem

### Why GM CINEMO Succeeds

1. **No buffer means no backlog**
   - Frame arrives → decode immediately
   - Can't fall behind because there's nothing to fall behind

2. **Real-time clock comparison**
   - "Is THIS frame late for display?" not "How many frames queued?"
   - Makes sense for interactive video

3. **Reference-only mode is smart**
   - When behind: decode IDR/P (maintain references), skip display
   - Decoder stays current, display catches up
   - Never loses reference chain

4. **Direct processing = predictable timing**
   - No callback dependency for feeding
   - Decoder gets data as fast as it arrives

---

## Recommended Architecture Change

### Option A: Eliminate Ring Buffer Entirely

```java
// Pseudo-code - direct processing like GM
public void processVideoFrame(byte[] data, int pts) {
    // No buffer - process immediately
    if (!isCodecReady()) {
        return;  // Discard if codec not ready
    }

    // Get codec buffer directly
    int index = mCodec.dequeueInputBuffer(0);  // Non-blocking
    if (index < 0) {
        // Codec busy - frame is now LATE
        // Could still decode if it's IDR (reference)
        int nalType = detectNalType(data);
        if (nalType != 5) {
            return;  // Discard non-IDR
        }
        // Wait briefly for IDR
        index = mCodec.dequeueInputBuffer(10);
        if (index < 0) return;
    }

    // Feed directly
    ByteBuffer buffer = mCodec.getInputBuffer(index);
    buffer.put(data);
    mCodec.queueInputBuffer(index, 0, data.length, pts, 0);
}
```

**Note:** This requires SYNC mode, not async callbacks. Different architecture.

### Option B: Minimal Buffer with Real-Time Drop

```java
// Keep small buffer (0.5-1 second max)
// But measure REAL lag, not counter lag

public void processDataDirectWithPts(int length, int skipBytes, int sourcePtsMs, ...) {
    // Check REAL-TIME lag
    long currentTimeMs = System.currentTimeMillis();
    long frameAgeMs = currentTimeMs - sourcePtsMs;  // How old is this frame?

    if (frameAgeMs > MAX_ACCEPTABLE_LAG_MS) {  // e.g., 200ms
        // Frame is already stale - discard before buffering
        if (detectNalType(data) != 5) {  // Keep IDR
            return;  // Don't even buffer it
        }
    }

    // Buffer only fresh frames
    ringBuffer.directWriteToBuffer(length, skipBytes, callback);
}
```

### Option C: Fix Callback Starvation First

Before any QC changes, we need to understand WHY only 5 codec inputs for 3,461 writes.

Diagnostic additions needed:
```java
// Log every callback
public void onInputBufferAvailable(MediaCodec codec, int index) {
    callbackCount.incrementAndGet();

    // Log why we might not process
    if (codec != mCodec) { log("SKIP: codec mismatch"); return; }
    if (!running) { log("SKIP: not running"); return; }
    if (isPaused) { log("SKIP: paused"); return; }
    if (ringBuffer.isEmpty()) {
        log("SKIP: buffer empty (but USB wrote " + writeCount + " frames!)");
        return;
    }

    // Actually process...
}
```

---

## Immediate Action Items

1. **Add diagnostic logging** to understand callback starvation
2. **Disable Quality Control** - it can't help the current problem
3. **Measure actual ring buffer depth** not counter lag
4. **Consider frame age** (currentTime - PTS) for drop decisions
5. **Never drop IDR** - always decode reference frames

---

## References

- `/Users/zeno/Downloads/carlink_native/documents/reference/gminfo/video/carplay_video_pipeline.md`
- `/Users/zeno/Downloads/carlink_native/documents/reference/gminfo/video/cinemo_nme_framework.md`
- `/Users/zeno/Downloads/carlink_native/documents/reference/gminfo/video/h264_nal_processing.md`
- `/Users/zeno/Downloads/carlink_native/documents/reference/gminfo/video/pts_timing_strategies.md`
- `/Volumes/POTATO/logcat/logcat_20260130_024549.log` - 20-minute unstable session
