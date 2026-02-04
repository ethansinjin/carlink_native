# Current State Assessment - Revision [55]

## Document Created: 2026-01-29
## Purpose: Detailed assessment of current video code state and behavior
## Applicable Revision: [55]
## Next Revision: [56]

---

## Current Code Characteristics

### File: H264Renderer.java
- **Lines:** 1888
- **Growth from Stable Baseline [13]:** +78% (1060 → 1888)

### Key Features Present in [55]:

| Feature | Status | Lines Added | Risk Level |
|---------|--------|-------------|------------|
| Intel VPU Workaround | Active | ~50 | Medium |
| Quality Control | Active | ~80 | **HIGH** |
| Source PTS Queue | Active | ~60 | **HIGH** |
| Reduced Buffer Sizes | Active | Config | Medium |
| SPS/PPS Caching | Active | ~100 | Low |
| Pause/Resume Handling | Active | ~80 | Medium |
| Multiple State Checks | Active | ~40 | **HIGH** |

---

## Code Path Analysis

### Current processDataDirectWithPts() Flow:

```java
public void processDataDirectWithPts(int length, int skipBytes, int sourcePtsMs, DirectWriteCallback callback) {
    // 1. Early exits
    if (!running || isPaused) return;                    // ← State check
    if (ringBuffer == null) return;                      // ← Null check

    // 2. Increment counters
    totalFramesReceived.incrementAndGet();
    totalBytesProcessed.addAndGet(length);

    // 3. Performance logging (every 30 sec)

    // 4. Buffer backpressure check
    int packetCount = ringBuffer.availablePacketsToRead();
    if (packetCount > MAX_BUFFER_PACKETS) {              // ← Backpressure
        // Drop oldest packets
        ringBuffer.dropOldestPackets(BUFFER_DROP_BATCH_SIZE);
        sourcePtsQueue.poll() for each dropped;          // ← PTS sync required
    }

    // 5. Write to ring buffer
    ringBuffer.directWriteToBuffer(length, skipBytes, callback);

    // 6. Enqueue source PTS
    if (useSourcePts && sourcePtsMs >= 0) {
        sourcePtsQueue.offer(sourcePtsMs * 1000L);       // ← PTS enqueue
    }

    // 7. Trigger codec feeding
    feedCodec();                                          // ← Feed attempt
}
```

### Current feedCodec() Flow:

```java
private void feedCodec() {
    // Early exit if paused or stopped
    if (isPaused || !running) {
        return;                                           // ← EARLY RETURN #1
    }

    // Skip if no saved buffer indices
    if (codecAvailableBufferIndexes.isEmpty()) {
        return;                                           // ← EARLY RETURN #2
    }

    executors.mediaCodec1().execute(() -> {
        if (isPaused || !running) {
            return;                                       // ← EARLY RETURN #3
        }

        try {
            fillAllAvailableCodecBuffers(mCodec);
        } catch (IllegalStateException e) {
            if (!isPaused) {
                log("[Media Codec] fill input buffer error (not paused): " + e);
            }
        } catch (Exception e) {
            log("[Media Codec] fill input buffer error:" + e);
        }
    });
}
```

**Potential Issue:** If `codecAvailableBufferIndexes` is empty (because callback consumed them directly), `feedCodec()` returns without scheduling executor.

### Current fillFirstAvailableCodecBuffer() Flow:

```java
private boolean fillFirstAvailableCodecBuffer(MediaCodec codec) {
    if (codec != mCodec) return false;                   // ← Check #1
    if (isPaused || !running) return false;              // ← Check #2

    if (ringBuffer.isEmpty()) {
        return false;                                     // ← Check #3
    }

    Integer index = codecAvailableBufferIndexes.poll();
    if (index == null) {
        return false;                                     // ← Check #4
    }

    ByteBuffer byteBuffer = codec.getInputBuffer(index);
    if (byteBuffer == null) {
        return false;                                     // ← Check #5
    }

    int bytesWritten = ringBuffer.readPacketInto(byteBuffer);
    if (bytesWritten <= 0) {
        codecAvailableBufferIndexes.offer(index);
        return false;                                     // ← Check #6
    }

    // NAL detection, caching, quality control...

    // QUALITY CONTROL CHECK
    if (qualityControlActive && nalType == 1) {
        boolean shouldDrop = aggressiveDropActive || (qualityDroppedFrames.get() % 2 == 0);
        if (shouldDrop) {
            qualityDroppedFrames.incrementAndGet();
            codecAvailableBufferIndexes.offer(index);    // ← Return buffer
            sourcePtsQueue.poll();                        // ← Consume PTS
            return true;                                  // ← FRAME DROPPED
        }
    }

    // Source PTS handling
    Long sourcePts = sourcePtsQueue.poll();              // ← PTS dequeue
    long pts = (useSourcePts && sourcePts != null) ? sourcePts : syntheticPts;

    // Queue to codec
    mCodec.queueInputBuffer(index, 0, bytesWritten, pts, 0);
    return true;
}
```

**Six potential failure points before frame reaches codec!**

### Current onInputBufferAvailable() Flow:

```java
public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
    synchronized (codecLock) {
        if (codec != mCodec || mCodec == null || !running) {
            return;                                       // ← Check #1
        }

        VideoDebugLogger.logCodecInputAvailable(index, codecAvailableBufferIndexes.size());

        // Priority 1: Inject SPS/PPS after codec reset
        if (codecConfigPending) {
            if (injectCodecConfigToBuffer(codec, index)) {
                return;                                   // ← SPS/PPS injected, return
            }
        }

        // Priority 2: Try to read from ring buffer
        if (!ringBuffer.isEmpty()) {
            try {
                ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
                if (byteBuffer == null) {
                    return;                               // ← Check #2
                }

                int dataSize = ringBuffer.readPacketInto(byteBuffer);
                if (dataSize == 0) {
                    codecAvailableBufferIndexes.offer(index);
                    return;                               // ← Check #3: No data
                }

                // NAL detection...

                // QUALITY CONTROL (duplicate of fillFirst logic)
                long frameLag = totalFramesReceived.get() - totalFramesDecoded.get();
                if (!qualityControlActive && frameLag >= QUALITY_CONTROL_LAG_THRESHOLD) {
                    qualityControlActive = true;
                    // ...
                }

                if (qualityControlActive && nalType == 1) {
                    // Drop P-frame logic...
                    codecAvailableBufferIndexes.offer(index);
                    return;                               // ← FRAME DROPPED
                }

                // PTS handling...
                Long sourcePts = sourcePtsQueue.poll();

                // Queue to codec
                mCodec.queueInputBuffer(index, 0, dataSize, pts, 0);
                framesReceivedSinceReset++;

                // Keyframe detection logic...

            } catch (Exception e) {
                codecAvailableBufferIndexes.offer(index);
            }
        } else {
            // No data - save buffer index
            codecAvailableBufferIndexes.offer(index);    // ← Saved for later
        }
    }
}
```

---

## Identified Issues in Current Code

### Issue 1: Quality Control in Two Places

Quality control logic exists in BOTH:
- `fillFirstAvailableCodecBuffer()` (called from feedCodec executor)
- `onInputBufferAvailable()` (called from codec callback)

This can cause:
- Double counting of dropped frames
- Inconsistent drop decisions
- Race conditions between paths

### Issue 2: Source PTS Queue Synchronization

The PTS queue must stay perfectly synchronized with ring buffer:

**Enqueue Points:**
1. `processDataDirectWithPts()` - after ring buffer write
2. (implicitly via backpressure drop)

**Dequeue Points:**
1. `fillFirstAvailableCodecBuffer()` - on successful read
2. `fillFirstAvailableCodecBuffer()` - on QC drop
3. `onInputBufferAvailable()` - on successful read
4. `onInputBufferAvailable()` - on QC drop
5. `pause()` - clears queue
6. `reset()` - clears queue
7. `resume()` - clears queue

**Risk:** If dequeue count doesn't match enqueue count, timestamps become incorrect.

### Issue 3: feedCodec() Early Return

```java
if (codecAvailableBufferIndexes.isEmpty()) {
    return;  // Skip if no saved indices
}
```

This optimization assumes the callback will handle new buffers. But if:
1. Callback processes directly (doesn't save to queue)
2. Then feedCodec() is called
3. Queue is empty, so feedCodec() returns
4. Ring buffer has data but no one reads it

This creates a potential race where data accumulates in ring buffer.

### Issue 4: Six Failure Points in fillFirstAvailableCodecBuffer()

Each check can cause early return:
1. `codec != mCodec` - codec mismatch
2. `isPaused || !running` - state check
3. `ringBuffer.isEmpty()` - no data
4. `index == null` - no buffer available
5. `byteBuffer == null` - buffer retrieval failed
6. `bytesWritten <= 0` - read failed

Combined with Quality Control drops, the probability of successfully processing a frame is low.

### Issue 5: Reduced Buffer Sizes

```java
// Current
if (pixels <= 2400 * 960) return 4 * 1024 * 1024;    // 4MB

// Previous (rev 13)
if (pixels <= 2400 * 960) return 16 * 1024 * 1024;   // 16MB
```

4MB at 60fps with average frame size of 8.9KB allows:
- 4MB / 8.9KB = ~449 frames = ~7.5 seconds

But this assumes consistent processing. If codec stalls, buffer fills quickly.

---

## Comparison: Rev [13] vs Rev [55]

| Aspect | Rev [13] (Stable) | Rev [55] (Current) |
|--------|-------------------|-------------------|
| Lines | 1060 | 1888 (+78%) |
| Buffer indices | ArrayList (sync) | ConcurrentLinkedQueue |
| Ring buffer read | readPacket() | readPacketInto() |
| Quality Control | **None** | Adaptive (lag ≥5) |
| Source PTS | **None** (uses 0) | Queue-based |
| Buffer size | 16 MB | 4 MB |
| State checks | 2 | 6+ |
| Codec feeding | Direct in callback | Callback + executor |
| SPS/PPS inject | N/A | Async with flag |

---

## Observed Behavior Summary

### From Logcat Analysis:

1. **Codec callbacks ARE firing** (SPS/PPS injection proves it)
2. **Video frames NOT reaching codec** (3,461 writes, ~5 inputs)
3. **Quality Control activated immediately** (lag 29 at first frame)
4. **14,000+ frames dropped** by Quality Control
5. **Surface recreation temporarily fixes** the issue
6. **Degradation recurs within 30 seconds**

### Probable Failure Scenario:

```
1. App starts, first frames arrive
2. Ring buffer fills faster than codec processes
3. Lag reaches 5 → Quality Control ACTIVATED
4. P-frames dropped (but lag doesn't decrease)
5. More P-frames dropped (still no improvement)
6. Codec starved of input (waiting for buffers)
7. Frame lag grows to 900+
8. Quality Control drops everything
9. Vicious cycle: drop → starve → more lag → drop more
```

### Why Surface Recreation Helps:

1. Triggers codec recreation (Intel VPU workaround path)
2. Clears all state: ring buffer, PTS queue, buffer indices
3. **Quality Control reset to OFF**
4. Fresh start with no accumulated lag
5. For 30 seconds, lag stays low, QC stays off
6. Eventually lag builds again, cycle repeats

---

## Key Questions for Investigation

1. **Why does initial lag build so fast?**
   - 29 frames behind after first frame decoded
   - Callback frequency vs write frequency mismatch?

2. **Is Quality Control ever helping?**
   - Dropped 14,000+ frames but situation never improved
   - Dropping P-frames can't help if issue is callback rate

3. **Are IDR frames being dropped?**
   - NAL stats show IDR:0 throughout
   - If IDR frames hit QC, decoder can never recover

4. **Is Source PTS queue synchronized?**
   - No logging of queue depth
   - Could be silently desynchronized

5. **Why does stable period last only 30 seconds?**
   - What triggers the return to instability?
   - Is it a specific event or gradual accumulation?

---

## Metrics to Track in [56]

If debugging, add logging for:

1. **Callback Frequency:**
   - Count `onInputBufferAvailable` calls per second
   - Compare to expected 60+/second

2. **PTS Queue Depth:**
   - Log queue size at enqueue and dequeue
   - Alert if depth grows unbounded

3. **Early Return Reasons:**
   - Log which check caused return in fillFirst
   - Track frequency of each failure point

4. **Quality Control Decisions:**
   - Log every drop with NAL type
   - Ensure IDR frames are NEVER dropped

5. **Buffer Index Queue:**
   - Track size of codecAvailableBufferIndexes
   - Alert if grows unexpectedly
