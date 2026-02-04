# Video Code Deep Analysis - Revision [55]

## Document Created: 2026-01-29
## Purpose: Comprehensive analysis of carlink_native video code
## Method: Direct source code review cross-referenced with protocol documentation

---

## 1. Video Code Architecture Overview

### Component Hierarchy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CarlinkManager.kt                                    │
│  - Orchestrates USB connection, video, audio                                │
│  - Creates H264Renderer with platform-specific settings                     │
│  - Creates videoProcessor for direct USB → ring buffer path                 │
│  - Handles lifecycle (pause/resume/surface changes)                         │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         H264Renderer.java (1888 lines)                       │
│  - MediaCodec async callback implementation                                  │
│  - PacketRingByteBuffer for video packet buffering                          │
│  - SPS/PPS caching and injection                                            │
│  - Quality Control (adaptive P-frame dropping)                              │
│  - Source PTS queue for frame timing                                        │
│  - Intel VPU workaround (full codec recreation)                             │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PacketRingByteBuffer.java (480 lines)                     │
│  - Thread-safe circular buffer                                              │
│  - Packet format: [4B length][4B skip][data]                               │
│  - Auto-resize (1MB → 64MB max)                                            │
│  - Emergency reset at 32MB threshold                                        │
│  - Backpressure drop mechanism                                              │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MediaCodec (Android)                                 │
│  - Async callback mode (setCallback before configure)                       │
│  - Hardware decoder: OMX.Intel.hw_vd.h264 on GM AAOS                       │
│  - Renders to SurfaceView via HWC overlay                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Data Flow Path

```
USB Bulk Read → VideoDataProcessor.processVideoDirect()
     │
     ├─► Extract sourcePtsMs from video header (offset 12)
     │
     └─► H264Renderer.processDataDirectWithPts()
              │
              ├─► detectFrameDrops() - check PTS gaps
              ├─► Buffer backpressure check (MAX_BUFFER_PACKETS=120)
              ├─► Enqueue sourcePtsUs to sourcePtsQueue
              └─► ringBuffer.directWriteToBuffer()
                       │
                       └─► feedCodec()
                                │
                                └─► fillAllAvailableCodecBuffers()
                                         │
                                         └─► MediaCodec.queueInputBuffer()

MediaCodec.Callback:
     │
     ├─► onInputBufferAvailable()
     │        ├─► Check codecConfigPending (inject SPS/PPS)
     │        ├─► ringBuffer.readPacketInto()
     │        ├─► detectNalUnitType()
     │        ├─► cacheCodecConfigData() (SPS/PPS)
     │        ├─► Quality Control (drop P-frames if behind)
     │        ├─► Get PTS from sourcePtsQueue
     │        └─► queueIdrWithSpsPps() or queueInputBuffer()
     │
     ├─► onOutputBufferAvailable()
     │        └─► releaseOutputBuffer(index, render=true)
     │
     └─► onError()
              └─► reset() if recoverable
```

---

## 2. What the Code Does Correctly

### 2.1 Protocol Compliance

**Video Header Parsing (Correct)**
- Correctly extracts 20-byte video header from USB packets
- Properly reads PTS from offset 12 (verified against protocol spec)
- Skips header bytes (20) when feeding data to codec

**NAL Unit Detection (Correct)**
- Properly detects 3-byte (00 00 01) and 4-byte (00 00 00 01) start codes
- Correctly extracts NAL type from lower 5 bits of first byte after start code
- Identifies SPS (7), PPS (8), IDR (5), P-frame (1) correctly

**SPS/PPS Caching (Correct)**
- Caches SPS/PPS NAL units when first received
- Uses synchronized block (spsLock) for thread safety
- Clears cache on reset to avoid stale config

### 2.2 MediaCodec Best Practices

**Async Callback Mode (Correct)**
- Uses `setCallback()` BEFORE `configure()` (Android requirement)
- Callback thread is dedicated HandlerThread with URGENT_AUDIO priority
- Uses `codecLock` to synchronize lifecycle with callbacks

**Intel VPU Workaround (Correct)**
- Correctly identifies Intel decoders via PlatformDetector
- Uses full stop/release/recreate instead of flush() for Intel VPU
- Documents the issue: Intel flush() doesn't clear reference frames

**Surface Management (Correct)**
- Uses `setOutputSurface()` for surface updates without codec recreation
- Falls back to full recreation if setOutputSurface fails
- Properly handles surface destruction via pause()

### 2.3 Thread Safety

**Ring Buffer (Correct)**
- All read/write operations are synchronized
- Uses System.arraycopy() for safe data copying (fixed in [31])
- Bounds checking on all array operations

**Codec Buffer Indices (Correct)**
- Uses ConcurrentLinkedQueue for lock-free buffer index storage
- Atomic operations for frame counters (AtomicLong)

### 2.4 Error Recovery

**Keyframe Request (Correct)**
- Requests keyframe after codec reset
- Implements cooldown (KEYFRAME_REQUEST_COOLDOWN_MS = 500ms)
- Requests keyframe after large PTS gaps (>500ms)

**Emergency Reset Callback (Correct)**
- Ring buffer notifies H264Renderer when emergency reset occurs
- H264Renderer requests keyframe to recover

---

## 3. What Can Be Improved

### 3.1 Quality Control Threshold Too Aggressive

**Current Implementation:**
```java
private static final int QUALITY_CONTROL_LAG_THRESHOLD = 5;      // Activate at 5 frames behind
private static final int QUALITY_CONTROL_CRITICAL_LAG = 15;      // Aggressive at 15 frames
private static final int QUALITY_CONTROL_RECOVERY_THRESHOLD = 2; // Deactivate at 2 frames ahead
```

**Problem:** 5 frames at 60fps is only 83ms. USB jitter and MediaCodec processing can easily cause 5-frame "lag" that isn't actual decoder falling behind. This triggers Quality Control prematurely, dropping P-frames unnecessarily.

**Evidence from Logcat (from 03_LOGCAT_ANALYSIS.md):**
- 14,535 frames dropped by QC
- Activated almost immediately after start
- Situation didn't improve despite aggressive dropping

**Recommendation:** Increase QUALITY_CONTROL_LAG_THRESHOLD to 30-60 frames (500ms-1s). This matches GM AAOS CINEMO behavior which is more conservative.

### 3.2 Buffer Size Reduction May Be Too Aggressive

**Current (Rev [54]):**
```java
if (pixels <= 2400 * 960) return 4 * 1024 * 1024;  // 4MB
```

**Previous (Rev [13]-[52]):**
```java
if (pixels <= 2400 * 960) return 16 * 1024 * 1024; // 16MB
```

**Problem:** 4MB buffer at 60fps with average 10KB frames = ~400 frames = ~6.6 seconds. Seems adequate, but during decoder stalls or lifecycle transitions, this may not provide enough headroom.

**Recommendation:** Consider 8MB as middle ground. Monitor ring buffer utilization in production.

### 3.3 Dual Processing Paths Create Complexity

**Issue:** There are two paths for feeding data to codec:
1. **onInputBufferAvailable callback** - reads from ring buffer directly
2. **feedCodec() → fillAllAvailableCodecBuffers()** - also reads from ring buffer

Both paths compete for ring buffer data and codec input buffers.

**Evidence:**
```java
// In onInputBufferAvailable:
if (!ringBuffer.isEmpty()) {
    // Process directly in callback
}

// In feedCodec:
executors.mediaCodec1().execute(() -> {
    fillAllAvailableCodecBuffers(mCodec);
});
```

**Risk:** If callback processes data, then feedCodec() is called, both may try to read from ring buffer simultaneously. The synchronization is at ring buffer level, but the codec buffer index handling may race.

**Recommendation:** Choose one path. Either:
- Use callback exclusively (save index if no data, process when data arrives)
- Use feedCodec() exclusively (callback only saves indices)

### 3.4 Source PTS Queue Synchronization Assumptions

**Current Implementation:**
```java
// In processDataDirectWithPts:
sourcePtsQueue.offer(sourcePtsUs);
ringBuffer.directWriteToBuffer(...);

// In fillFirstAvailableCodecBuffer:
int bytesWritten = ringBuffer.readPacketInto(byteBuffer);
Long sourcePts = sourcePtsQueue.poll();
```

**Assumption:** Ring buffer packets and PTS queue entries remain synchronized 1:1.

**Risk:** If any path drops packets without polling PTS (or polls PTS without consuming packets), synchronization is lost. Quality Control does poll PTS when dropping:
```java
sourcePtsQueue.poll();  // Consume PTS to maintain sync
```

But if ring buffer emergency resets while PTS queue isn't cleared, desync occurs.

**Evidence:** PTS queue is cleared on reset/pause/resume, but if dropOldestPackets() is called, it iterates the dropped count:
```java
for (int i = 0; i < dropped && !sourcePtsQueue.isEmpty(); i++) {
    sourcePtsQueue.poll();
}
```

This should maintain sync, but relies on dropped count being accurate.

**Recommendation:** Consider embedding PTS in ring buffer packet format instead of separate queue. This guarantees 1:1 correspondence.

---

## 4. What Should Be Corrected

### 4.1 containsIdrFrame() Scans Too Deep

**Current Implementation:**
```java
private boolean containsIdrFrame(ByteBuffer buffer, int dataSize) {
    int searchLimit = Math.min(dataSize, 512); // Searches first 512 bytes
    // ...scans for NAL type 5...
}
```

**Problem:** Per protocol documentation, CPC200-CCPA sends SPS+PPS WITH every IDR frame. The NAL order is:
- 00 00 00 01 67 [SPS]
- 00 00 00 01 68 [PPS]
- 00 00 00 01 65 [IDR]

So IDR typically appears within first 50-100 bytes after SPS+PPS. Scanning 512 bytes is excessive.

**Recommendation:** Reduce searchLimit to 128 bytes for efficiency.

### 4.2 queueIdrWithSpsPps() May Double-Prepend

**Current Logic:**
```java
if (frameSize > 5) {
    // Check if first NAL is SPS (type 7)
    if (firstNalType == 7) {
        // Packet already has SPS - queue as-is without prepending
        return false;
    }
}
```

**Problem:** This only checks if FIRST NAL is SPS. If packet has [IDR] without SPS/PPS (which shouldn't happen per protocol, but could after corruption), it would prepend cached SPS/PPS. If the cached SPS/PPS is from a different resolution/profile, this could cause decode errors.

**Also:** The check happens AFTER cacheCodecConfigData() is called:
```java
if (nalType == 7 || nalType == 8 || nalType == 5) {
    cacheCodecConfigData(byteBuffer, bytesWritten);
}
// ...later...
if (isIdrFrame && queueIdrWithSpsPps(...)) {
```

So if this packet has SPS+PPS+IDR, we cache new SPS/PPS, then skip prepending (correct). But if IDR-only packet arrives, we prepend potentially old cached SPS/PPS.

**Recommendation:** Clear SPS/PPS cache when new SPS is received, not just check for presence.

### 4.3 Keyframe Detection Logic Has Redundancy

**In onInputBufferAvailable:**
```java
boolean isIdrFrame = (nalType == 5) || containsIdrFrame(byteBuffer, dataSize);
```

**Problem:** If nalType == 5, we already know it's IDR. containsIdrFrame() is only needed if the FIRST NAL isn't IDR but a later NAL is. Per protocol, this shouldn't happen - IDR is always first (or after SPS/PPS).

**Recommendation:** Simplify to just check first NAL type. If packet doesn't start with SPS/PPS/IDR, it's a P-frame.

### 4.4 Frame Lag Calculation Uses Total Counters

**Current:**
```java
long frameLag = totalFramesReceived.get() - totalFramesDecoded.get();
```

**Problem:** These counters are reset every PERF_LOG_INTERVAL_MS (30 seconds):
```java
// In logPerformanceStats:
totalFramesReceived.set(0);
totalFramesDecoded.set(0);
```

So frameLag is only meaningful within the 30-second window. After reset, lag is calculated correctly for new window. But during the reset moment, if frameLag was 50 and counters reset, new frameLag is 0 even if decoder is still behind.

**Impact:** Quality Control may deactivate prematurely after counter reset.

**Recommendation:** Use separate counters for QC lag tracking that don't reset with perf stats.

---

## 5. Errors/Behaviors I Cannot Reconcile

### 5.1 Logcat Shows 3,461 Ring Writes but Only ~5 Codec Inputs

**From 03_LOGCAT_ANALYSIS.md:**
```
Total Ring Buffer Writes: 3,461
Total Codec Inputs: ~5 (severely starved)
Input Efficiency: 0.14%
```

**This makes no sense.** If ring buffer has data (3,461 writes) and codec is running (started successfully), onInputBufferAvailable should be called for every available input buffer.

**Possible Explanations:**
1. **Codec stalled** - But logcat shows codec outputs occurring
2. **codecLock contention** - Unlikely to block 99.86% of inputs
3. **isPaused flag incorrectly set** - Would block all processing
4. **Callback thread died** - Should show error in logs

**I cannot identify the root cause from code alone.** This requires live debugging with breakpoints to understand why callbacks aren't processing data.

### 5.2 30-Second Stable Period After Surface Recreation

**From logcat:**
```
18:00:25 | Surface recreation (homescreen return) | Recovery initiated
18:01:00-18:01:30 | **Stable period** | **Working (25.6 FPS)**
18:01:30+ | Degradation resumed | Broken again
```

**What happens at 18:01:30 that causes degradation?** The code doesn't have any 30-second timer that would cause state change.

**Possible Explanations:**
1. **Counter reset at 30s** - PERF_LOG_INTERVAL_MS = 30000ms, but this only affects logging
2. **External event** - Something from CarPlay/adapter
3. **Gradual buffer accumulation** - Takes 30s to fill up

**I cannot determine why stability lasts exactly ~30 seconds.** Correlation with PERF_LOG_INTERVAL_MS suggests the counter reset may affect Quality Control behavior, but the code path isn't clear.

### 5.3 Quality Control Drops 14,535 Frames But Situation Doesn't Improve

**Logic says:** If decoder is behind, drop P-frames → decoder catches up → QC deactivates.

**Reality:** Dropped 14,535 frames, still behind, QC stayed active.

**This suggests:** Either:
1. Decoder isn't processing ANY frames (so dropping P-frames doesn't help)
2. Frame lag calculation is wrong
3. Decoder is outputting, but slower than frames arrive (impossible at 5 inputs for 3,461 received)

**The codec input starvation (5 vs 3,461) is the root issue.** QC dropping frames can't help if codec isn't receiving frames to begin with.

### 5.4 Protocol Says SPS/PPS Every IDR, But Code Caches Only Once

**From video_protocol.md:**
```
CPC200-CCPA adapter sends SPS+PPS with EVERY IDR frame (every 2 seconds)
```

**From H264Renderer.java:**
```java
private void cacheCodecConfigData(...) {
    // Only cache if we don't have SPS/PPS yet
    if (cachedSps != null && cachedPps != null) return;
    // ...
}
```

**Question:** If adapter sends fresh SPS/PPS every IDR, why only cache once? If video parameters change mid-session (resolution change, profile change), the cached SPS/PPS would be stale.

**Possible Explanation:** The code assumes video parameters don't change during session. The protocol docs confirm this is typical behavior - resolution is negotiated at session start.

**But:** This means SPS/PPS prepending in queueIdrWithSpsPps() is mostly redundant since adapter already sends them. The prepending logic is defensive for pi-carplay captures which have SPS/PPS only at start.

---

## 6. Summary of Findings

### Correct
- Protocol parsing (header, NAL detection)
- MediaCodec async setup (setCallback before configure)
- Intel VPU workaround (full recreation)
- Thread safety (ring buffer, atomic counters)
- Surface management (setOutputSurface, lifecycle)

### Should Improve
- Quality Control threshold too aggressive (5 frames)
- Buffer size reduction may be too aggressive (4MB vs 16MB)
- Dual processing paths create complexity
- Source PTS queue synchronization relies on perfect 1:1 correspondence

### Should Correct
- containsIdrFrame() scans too deep (512 bytes)
- queueIdrWithSpsPps() may use stale SPS/PPS after corruption
- Frame lag uses counters that reset every 30s
- Keyframe detection has redundant containsIdrFrame() call

### Cannot Explain
- 3,461 ring writes but only ~5 codec inputs (99.86% loss)
- 30-second stable period after surface recreation
- Quality Control drops 14,535 frames with no improvement
- Relationship between counter reset and QC behavior

---

## 7. Recommended Investigation Path

1. **Add diagnostic logging in onInputBufferAvailable()** to confirm callback is being called
2. **Log codecLock acquisition/release** to identify contention
3. **Log isPaused flag state** at critical points
4. **Separate QC lag counters from perf counters** to avoid reset interference
5. **Test with Quality Control disabled** to isolate its impact
6. **Compare behavior with Rev [13]** which was reported stable

---

## References

- H264Renderer.java: `/Users/zeno/Downloads/carlink_native/app/src/main/java/com/carlink/video/H264Renderer.java`
- PacketRingByteBuffer.java: `/Users/zeno/Downloads/carlink_native/app/src/main/java/com/carlink/video/PacketRingByteBuffer.java`
- CarlinkManager.kt: `/Users/zeno/Downloads/carlink_native/app/src/main/kotlin/com/carlink/CarlinkManager.kt`
- video_protocol.md: `/Users/zeno/Downloads/carlink_native/documents/reference/firmware/RE_Documention/02_Protocol_Reference/video_protocol.md`
- carplay_video_pipeline.md: `/Users/zeno/Downloads/carlink_native/documents/reference/gminfo/video/carplay_video_pipeline.md`
- 03_LOGCAT_ANALYSIS.md: Previous analysis document
