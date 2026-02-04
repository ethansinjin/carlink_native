# Complete Archive Video Code Analysis

## Document Created: 2026-01-29
## Purpose: Systematic analysis of ALL carlink_native archives (42 versions)
## Method: Direct code extraction and comparison, not relying solely on revisions.txt

---

## Archives Analyzed

42 archives spanning revisions [5] through [55]:
- [5-9], [11-16], [19], [21-30], [33-44], [46-47], [49-50], [52-55]
- Missing archives: [10], [17-18], [20], [31-32], [45], [48], [51]

---

## Code Size Evolution

### H264Renderer.java

| Revision | Lines | Change | Notes |
|----------|-------|--------|-------|
| [5] | 1335 | - | Initial |
| [6-7] | 1335 | 0 | No change |
| [8] | 1332 | -3 | Minor |
| [9] | 1319 | -13 | Minor cleanup |
| [11] | 805 | **-514** | Major rewrite/simplification |
| [12] | 963 | +158 | Building back up |
| [13] | 1060 | +97 | "Stable" version |
| [14-16] | 1060 | 0 | No change |
| [19] | 1073 | +13 | Minor |
| [21] | 1073 | 0 | No change |
| [22] | 1167 | **+94** | Added pause/resume |
| [23-25] | 1167 | 0 | No change |
| [26-27] | 1202 | +35 | Minor additions |
| [28-29] | 1434 | **+232** | Major additions |
| [30] | 1454 | +20 | Minor |
| [33-36] | 1460 | +6 | Minor |
| [37] | 1526 | +66 | Additions |
| [38-39] | 1149 | **-377** | Code cleanup/simplification |
| [40] | 1451 | **+302** | SPS/PPS caching |
| [41-44] | 1507-1511 | +56-60 | Minor additions |
| [46-50] | 1469 | -42 | Cleanup |
| [52-53] | 1558 | **+89** | Intel VPU workaround |
| [54] | 1722 | **+164** | Source PTS queue |
| [55] | 1888 | **+166** | Quality Control |

### PacketRingByteBuffer.java

| Revision | Lines | Change | Notes |
|----------|-------|--------|-------|
| [5-11] | 372-374 | - | Stable |
| [12-30] | 383 | +9-11 | Minor |
| [33-37] | 469 | **+86** | readPacketInto() added, safe copy |
| [38-39] | 401 | -68 | Cleanup |
| [40-53] | 401-426 | +0-25 | Minor |
| [55] | 480 | **+54** | Additional changes |

---

## Feature Introduction Timeline

### Verified by Code Analysis (not just revisions.txt)

| Feature | First Seen | Revision | Evidence |
|---------|-----------|----------|----------|
| Basic MediaCodec | [5] | Dec 4 | Initial implementation |
| codecLock synchronization | [13] | Dec 8 | `private final Object codecLock` |
| KeyframeRequestCallback | [13] | Dec 8 | Interface and callback |
| pause() method | [22] | Dec 21 | New method |
| resume() method | [22] | Dec 21 | New method |
| recreateCodecWithSurface() | [28] | Dec 26 | Surface handling |
| findCodecInfo() | [28] | Dec 26 | Codec capability checks |
| isPaused flag | [28] | Dec 26 | State tracking |
| readPacketInto() | [33] | Dec 28 | Zero-intermediate-allocation |
| System.arraycopy fix | [33] | Dec 28 | Race condition fix |
| SPS/PPS caching | [40] | Jan 11 | `cacheCodecConfigData()` |
| NAL unit detection | [40] | Jan 11 | `detectNalUnitType()` |
| codecConfigPending | [40] | Jan 11 | SPS/PPS injection |
| IDR with SPS/PPS prepend | [40] | Jan 11 | `queueIdrWithSpsPps()` |
| Intel VPU workaround | [52] | Jan 22 | `requiresIntelVpuWorkaround` |
| Source PTS queue | [54] | Jan 29 | `sourcePtsQueue` |
| useSourcePts flag | [54] | Jan 29 | PTS mode control |
| Quality Control | [55] | Jan 29 | `qualityControlActive` |
| Aggressive drop mode | [55] | Jan 29 | `aggressiveDropActive` |
| Buffer backpressure | [55] | Jan 29 | `MAX_BUFFER_PACKETS` |

---

## Buffer Size Evolution

| Revision | 1080p | 2400x960 | 4K | Max |
|----------|-------|----------|-----|-----|
| [13-52] | 8 MB | 16 MB | 32 MB | 64 MB |
| [54-55] | 2 MB | 4 MB | 6 MB | 8 MB |

**[54] reduced buffers by 75%** - from 16MB to 4MB for GM AAOS resolution.

---

## Callback Complexity Evolution

Lines in `onInputBufferAvailable` method:

| Revision | Lines | Architecture |
|----------|-------|--------------|
| [11] | ~11 | Simple index save |
| [13] | ~466 | Direct feed + keyframe detection |
| [22] | ~566 | + pause handling |
| [28-30] | ~732 | + verbose logging, surface handling |
| [40] | ~232 | Simplified + SPS/PPS injection |
| [52] | ~270 | + Intel workaround |
| [54] | ~277 | + Source PTS |
| [55] | ~319 | + Quality Control |

**Major architectural shifts:**
- [11] → [13]: Simple → Complex direct feed
- [30] → [40]: Complex → Simplified with SPS/PPS focus
- [54] → [55]: Added QC on top of simplified base

---

## Undocumented Changes Discovered

### [11] Major Simplification (not in revisions.txt detail)
- Dropped from 1335 to 805 lines
- Removed buffer pool complexity
- Simplified callback

### [38] Code Cleanup (not documented)
- Dropped from 1526 to 1149 lines (-377)
- Removed verbose comments
- Streamlined implementation
- PacketRingByteBuffer also reduced by 68 lines

### [40] SPS/PPS Architecture (partially documented)
- Complete rewrite of callback logic
- Added `codecConfigPending` injection system
- Added NAL unit type detection
- Added IDR frame handling with SPS/PPS prepend
- This was a significant architectural change

---

## Key Architectural Decisions by Revision

### [13] - First "Stable"
```java
// Added codecLock for thread safety
private final Object codecLock = new Object();

// Added keyframe request threshold
private static final int KEYFRAME_REQUEST_THRESHOLD = 15;
```

### [22] - Pause/Resume
```java
public void pause() { ... }
public void resume() { ... }
```

### [28] - Surface Recreation
```java
private void recreateCodecWithSurface(Surface newSurface) { ... }
private volatile boolean isPaused = false;
```

### [33] - Memory Safety
```java
// PacketRingByteBuffer - safe copy
byte[] packetData = new byte[actualLength];
System.arraycopy(buffer, startPos, packetData, 0, actualLength);

// New optimized method
int readPacketInto(ByteBuffer target) { ... }
```

### [40] - SPS/PPS Management
```java
private volatile boolean codecConfigPending = false;
private byte[] cachedSps = null;
private byte[] cachedPps = null;

private boolean injectCodecConfigToBuffer(MediaCodec codec, int index) { ... }
private void cacheCodecConfigData(ByteBuffer buffer, int dataSize) { ... }
private int detectNalUnitType(ByteBuffer buffer, int dataSize) { ... }
private boolean queueIdrWithSpsPps(...) { ... }
```

### [52] - Intel Platform
```java
private final boolean requiresIntelVpuWorkaround;

// Full codec recreation instead of flush()
if (requiresIntelVpuWorkaround) {
    // stop, release, create, configure, setCallback, start
}
```

### [54] - Timestamp Management
```java
private final ConcurrentLinkedQueue<Long> sourcePtsQueue = new ConcurrentLinkedQueue<>();
private volatile boolean useSourcePts = true;

// Reduced buffer sizes
if (pixels <= 2400 * 960) return 4 * 1024 * 1024;  // Was 16MB
```

### [55] - Frame Dropping
```java
private static final int QUALITY_CONTROL_LAG_THRESHOLD = 5;
private volatile boolean qualityControlActive = false;
private volatile boolean aggressiveDropActive = false;

// Buffer backpressure
private static final int MAX_BUFFER_PACKETS = 120;
private static final int BUFFER_DROP_BATCH_SIZE = 30;
```

---

## Patterns Observed

### 1. Complexity Oscillation
The code doesn't just grow - it oscillates:
- [5]: 1335 lines
- [11]: 805 lines (simplified)
- [30]: 1454 lines (complex)
- [38]: 1149 lines (simplified)
- [55]: 1888 lines (complex again)

### 2. Feature Accumulation Without Removal
Features added are rarely removed:
- Keyframe detection (added [13])
- Pause/resume (added [22])
- Surface recreation (added [28])
- SPS/PPS caching (added [40])
- Intel workaround (added [52])
- Source PTS (added [54])
- Quality Control (added [55])

### 3. Buffer Size Reduction
Significant reduction in [54]:
- 16MB → 4MB for 2400x960
- Rationale: "match GM's philosophy and reduce latency"
- Less headroom for timing variations

### 4. Two Major Architectural Rewrites
1. [11]: Simplified from original
2. [40]: Restructured around SPS/PPS management

---

## Current State [55] Summary

**Lines:** 1888 (H264Renderer) + 480 (PacketRingByteBuffer)

**Active Features:**
- Quality Control (lag threshold 5 frames)
- Source PTS queue
- Intel VPU workaround (full recreation)
- SPS/PPS caching and injection
- NAL unit detection
- IDR frame handling
- Pause/resume support
- Keyframe request callback
- Buffer backpressure (120 packet limit)

**Buffer Sizes:**
- 2400x960: 4MB (reduced from 16MB)

**Callback Complexity:**
- ~319 lines in onInputBufferAvailable
- Dual processing paths (callback direct + feedCodec executor)

---

## Questions Raised by Analysis

1. **Why was [38] simplified then [40] expanded?** What drove the architectural changes?

2. **Was the [54] buffer reduction tested?** 75% reduction is significant.

3. **Is Quality Control threshold of 5 frames appropriate?** Logcat shows it activates immediately.

4. **Are all features in [55] necessary?** Code has doubled since [40] simplification.

5. **What happened in missing archives [31-32]?** The fix is in [33] but what was the transition?

---

## Comparison: Simplest Stable vs Current

| Aspect | [40] Simplified | [55] Current |
|--------|-----------------|--------------|
| Lines | 1451 | 1888 (+30%) |
| Callback lines | ~232 | ~319 (+37%) |
| Buffer size | 16MB | 4MB (-75%) |
| Quality Control | No | Yes |
| Source PTS | No | Yes |
| Intel workaround | No | Yes |
| SPS/PPS caching | Yes | Yes |

---

## Document References

- `02_VIDEO_CODE_EVOLUTION.md` - Revision descriptions from revisions.txt
- `07_GITHUB_COMMIT_HISTORY.md` - GitHub commit analysis
- Archives at `/Users/zeno/Downloads/project_archieve/carlink_native_*.zip`
