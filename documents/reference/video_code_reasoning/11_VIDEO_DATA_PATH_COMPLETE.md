# Complete Video Data Path: CPC200-CCPA Adapter → GM Info 3.7 Display

## Document Created: 2026-01-29
## Purpose: Trace raw H.264 video from adapter through carlink_native to GM AAOS display
## Sources: Protocol documentation, platform documentation, source code analysis

---

## Executive Summary

The video data path consists of three major segments:

1. **Source (CPC200-CCPA Adapter)** - Receives H.264 from iPhone via AirPlay, forwards unchanged via USB
2. **Processing (carlink_native App)** - Receives USB packets, buffers, decodes via MediaCodec
3. **Destination (GM Info 3.7)** - Intel hardware decoder outputs to SurfaceView → HWC overlay → Display

**Critical Insight:** The adapter does NOT transcode video. It forwards raw H.264 NAL units from CarPlay/AirPlay with only a header prepended. This means the app receives the exact same H.264 stream the iPhone generates.

---

## 1. Source: CPC200-CCPA Adapter

### 1.1 How Video Enters the Adapter

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              iPhone                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  CarPlay UI Compositor                                               │    │
│  │  → H.264 Encoder (High Profile, Level 5.0)                          │    │
│  │  → AirPlay Stream                                                    │    │
│  └────────────────────────────────┬────────────────────────────────────┘    │
└───────────────────────────────────┼─────────────────────────────────────────┘
                                    │ USB (iAP2) / WiFi (AirPlay TCP)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CPC200-CCPA Adapter                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │  AppleCarPlay Binary                                                 │    │
│  │  ├─ AirPlayReceiverSession (manages stream)                         │    │
│  │  ├─ AirPlayReceiverSessionScreen (receives video frames)            │    │
│  │  └─ NO TRANSCODING - just frame reception                           │    │
│  └────────────────────────────────┬────────────────────────────────────┘    │
│                                   │ Unix Socket IPC                          │
│  ┌────────────────────────────────▼────────────────────────────────────┐    │
│  │  ARMadb-driver Binary                                                │    │
│  │  ├─ Receives video frames from AppleCarPlay                         │    │
│  │  ├─ Prepends USB header (16 bytes)                                  │    │
│  │  ├─ Prepends video header (20 bytes)                                │    │
│  │  └─ Transmits via USB bulk endpoint                                 │    │
│  └────────────────────────────────┬────────────────────────────────────┘    │
└───────────────────────────────────┼─────────────────────────────────────────┘
                                    │
                                    ▼ USB Bulk Transfer
```

### 1.2 USB Packet Format (36-byte header + H.264 payload)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         USB HEADER (16 bytes)                                │
├─────────┬───────┬──────────────────────────────────────────────────────────┤
│ Offset  │ Size  │ Description                                               │
├─────────┼───────┼──────────────────────────────────────────────────────────┤
│ 0x00    │ 4     │ Magic: 0x55AA55AA (little-endian)                        │
│ 0x04    │ 4     │ PayloadLength: bytes after this 16-byte header           │
│ 0x08    │ 4     │ MessageType: 0x06 (VideoData)                            │
│ 0x0C    │ 4     │ TypeCheck: 0xFFFFFFF9 (type XOR 0xFFFFFFFF)              │
├─────────┴───────┴──────────────────────────────────────────────────────────┤
│                       VIDEO HEADER (20 bytes)                                │
├─────────┬───────┬──────────────────────────────────────────────────────────┤
│ 0x10    │ 4     │ Width: e.g., 2400                                        │
│ 0x14    │ 4     │ Height: e.g., 960                                        │
│ 0x18    │ 4     │ EncoderState: stream ID (7 for CarPlay, 3 for AA)        │
│ 0x1C    │ 4     │ PTS: Presentation Timestamp (1kHz clock, milliseconds)   │  ← CRITICAL
│ 0x20    │ 4     │ Flags: usually 0x00000000                                │
├─────────┴───────┴──────────────────────────────────────────────────────────┤
│                       H.264 PAYLOAD (variable)                               │
├─────────────────────────────────────────────────────────────────────────────┤
│ 0x24+   │ NAL Units with Annex B start codes (00 00 00 01 or 00 00 01)     │
│         │ Typical contents:                                                 │
│         │   - SPS (type 7): 00 00 00 01 67 ...                             │
│         │   - PPS (type 8): 00 00 00 01 68 ...                             │
│         │   - IDR (type 5): 00 00 00 01 65 ... (keyframe)                  │
│         │   - P-frame (type 1): 00 00 00 01 41 ...                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Key Protocol Facts

| Aspect | Value | Source |
|--------|-------|--------|
| H.264 Profile | High (100) | USB capture analysis |
| H.264 Level | 5.0 | Supports up to 4096x2304@30 |
| NAL Format | Annex B | 00 00 00 01 start codes |
| SPS/PPS | With every IDR | Every ~2 seconds |
| Frame Rate | Variable (1.7-50 fps) | Content-dependent |
| PTS Clock | 1kHz (milliseconds) | Video header offset 0x1C |

**The adapter does NOT:**
- Transcode video
- Re-encode frames
- Modify H.264 data
- Change resolution/profile

**The adapter ONLY:**
- Receives H.264 from iPhone
- Adds USB + video headers
- Forwards via USB bulk transfer

---

## 2. Processing: carlink_native App

### 2.1 USB Data Reception

```kotlin
// UsbDeviceWrapper reads bulk data from USB endpoint
// Calls VideoDataProcessor.processVideoDirect() for type 0x06 messages

interface VideoDataProcessor {
    fun processVideoDirect(
        payloadLength: Int,      // Total payload including 20-byte video header
        sourcePtsMs: Int,        // Extracted from video header offset 0x1C
        readCallback: (buffer: ByteArray, offset: Int, length: Int) -> Int
    )
}
```

### 2.2 CarlinkManager Video Processor

```kotlin
// CarlinkManager.kt lines 1668-1706
private fun createVideoProcessor(): UsbDeviceWrapper.VideoDataProcessor {
    return object : UsbDeviceWrapper.VideoDataProcessor {
        override fun processVideoDirect(
            payloadLength: Int,
            sourcePtsMs: Int,
            readCallback: (buffer: ByteArray, offset: Int, length: Int) -> Int
        ) {
            val renderer = h264Renderer ?: run {
                // Discard if renderer not ready
                val discardBuffer = ByteArray(payloadLength)
                readCallback(discardBuffer, 0, payloadLength)
                return
            }

            // Direct write to ring buffer
            // skipBytes=20 tells ring buffer to skip video header when reading
            renderer.processDataDirectWithPts(payloadLength, 20, sourcePtsMs) { buffer, offset ->
                readCallback(buffer, offset, payloadLength)
            }
        }
    }
}
```

### 2.3 H264Renderer Data Path

```
processDataDirectWithPts(length, skipBytes=20, sourcePtsMs)
        │
        ├── detectFrameDrops(sourcePtsMs)   // Check PTS gaps
        │
        ├── Buffer backpressure check       // MAX_BUFFER_PACKETS = 120
        │   └── dropOldestPackets() if overwhelmed
        │
        ├── sourcePtsQueue.offer(sourcePtsUs)  // Queue PTS for later use
        │
        ├── ringBuffer.directWriteToBuffer(length, skipBytes, callback)
        │   └── USB data written directly to ring buffer
        │       Packet format: [4B length][4B skip=20][USB data including headers]
        │
        └── feedCodec()
                │
                └── fillAllAvailableCodecBuffers(mCodec)
                        │
                        ├── ringBuffer.readPacketInto(byteBuffer)
                        │   └── Copies H.264 data (skipping 20-byte video header) to codec buffer
                        │
                        ├── detectNalUnitType(byteBuffer, bytesWritten)
                        │   └── Returns: 1=P-frame, 5=IDR, 7=SPS, 8=PPS
                        │
                        ├── cacheCodecConfigData() if SPS/PPS/IDR
                        │   └── Stores SPS/PPS for later injection
                        │
                        ├── Quality Control (if active)
                        │   └── Drop P-frames (type 1) when decoder behind
                        │
                        ├── Get PTS from sourcePtsQueue
                        │   └── Or generate synthetic PTS if not available
                        │
                        ├── queueIdrWithSpsPps() for IDR frames
                        │   └── Prepends cached SPS+PPS to IDR (if not already present)
                        │
                        └── mCodec.queueInputBuffer(index, 0, bytesWritten, pts, flags)
                                │
                                └── H.264 data now in MediaCodec input buffer
```

### 2.4 Ring Buffer Internal Format

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    PacketRingByteBuffer Packet Format                        │
├─────────┬───────┬──────────────────────────────────────────────────────────┤
│ Offset  │ Size  │ Description                                               │
├─────────┼───────┼──────────────────────────────────────────────────────────┤
│ 0       │ 4     │ Length: total payload size                                │
│ 4       │ 4     │ SkipBytes: 20 (video header size to skip)                │
│ 8       │ 20    │ Video Header (width, height, encoderState, PTS, flags)   │
│ 28      │ N     │ H.264 NAL Data (SPS+PPS+IDR or P-frames)                 │
└─────────────────────────────────────────────────────────────────────────────┘

When readPacketInto() is called:
  - Reads from offset (8 + skipBytes) = offset 28
  - Returns only the H.264 NAL data
  - Video header is skipped
```

### 2.5 MediaCodec Async Callback

```java
// H264Renderer.java - createCallback()

new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(MediaCodec codec, int index) {
        // Called when codec has empty input buffer ready

        // 1. Inject SPS/PPS if pending (after reset)
        if (codecConfigPending) {
            injectCodecConfigToBuffer(codec, index);
            return;
        }

        // 2. Read from ring buffer
        ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
        int dataSize = ringBuffer.readPacketInto(byteBuffer);

        // 3. Detect NAL type
        int nalType = detectNalUnitType(byteBuffer, dataSize);

        // 4. Cache SPS/PPS if present
        if (nalType == 7 || nalType == 8 || nalType == 5) {
            cacheCodecConfigData(byteBuffer, dataSize);
        }

        // 5. Quality control - drop P-frames if behind
        if (qualityControlActive && nalType == 1) {
            // Return buffer, don't queue
            codecAvailableBufferIndexes.offer(index);
            return;
        }

        // 6. Get PTS
        Long sourcePts = sourcePtsQueue.poll();
        long pts = (sourcePts != null) ? sourcePts : syntheticPts;

        // 7. Queue to codec
        mCodec.queueInputBuffer(index, 0, dataSize, pts, 0);
    }

    @Override
    public void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info) {
        // Called when codec has decoded frame ready

        // Release to surface for rendering
        codec.releaseOutputBuffer(index, true /* render */);
    }
}
```

---

## 3. Destination: GM Info 3.7 Platform

### 3.1 Platform Hardware

| Component | Specification |
|-----------|---------------|
| CPU | Intel Atom x7-A3960 (Apollo Lake/Broxton) |
| GPU | Intel HD Graphics 505 |
| Display | 2400x960 @ 60Hz (Chunghwa CMN DD134IA-01B) |
| RAM | 6GB |
| Android | AAOS (Android 12, API 32) |

### 3.2 Hardware Decoder

```xml
<!-- /vendor/etc/media_codecs.xml on GM Info 3.7 -->
<MediaCodec name="OMX.Intel.hw_vd.h264" type="video/avc">
    <Limit name="size" min="64x64" max="3840x2160" />
    <Limit name="bitrate" range="1-40000000" />
    <Limit name="performance-point-3840x2160" value="60" />
    <Feature name="adaptive-playback" />
</MediaCodec>
```

**Decoder Selection by carlink_native:**
```kotlin
// PlatformDetector.kt
val hwDecoder = codecList.codecInfos.firstOrNull { info ->
    !info.isEncoder &&
    info.isHardwareAccelerated &&
    info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
}
// Returns: "OMX.Intel.hw_vd.h264"

// H264Renderer constructor receives this name
H264Renderer(..., preferredDecoderName = "OMX.Intel.hw_vd.h264", ...)
```

### 3.3 Intel VPU Workaround

**Problem:** Intel VPU decoders don't properly reset reference frames on flush().

**Evidence:** P-frame corruption persists after flush() until full codec recreation.

**Solution in carlink_native:**
```java
// H264Renderer.java lines 577-597
if (requiresIntelVpuWorkaround) {
    // Instead of flush() + start()...
    mCodec.stop();
    mCodec.release();
    mCodec = null;

    // Full recreation
    initCodec(width, height, surface);
    mCodec.start();
}
```

### 3.4 Rendering Path

```
MediaCodec Output
        │
        ▼
releaseOutputBuffer(index, render=true)
        │
        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SurfaceView                                          │
│  ├── Backed by ANativeWindow (Android Native Window)                        │
│  ├── Uses HWC overlay path (bypasses GPU composition)                       │
│  └── Direct to display, lowest latency                                      │
└───────────────────────────────────────────┬─────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SurfaceFlinger                                       │
│  ├── Layer management                                                        │
│  ├── Determines composition type: DEVICE (HWC) or CLIENT (GPU)              │
│  └── Queues buffer for display                                              │
└───────────────────────────────────────────┬─────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   Hardware Composer 2.1 (hwcomposer.broxton)                 │
│  ├── DEVICE composition: Direct DRM/KMS display plane                       │
│  ├── Triple buffering (3 framebuffers)                                      │
│  └── VSYNC: 16.666ms @ 60Hz                                                 │
└───────────────────────────────────────────┬─────────────────────────────────┘
                                            │
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Display Output                                  │
│                         2400x960 @ 60Hz (Chunghwa)                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Complete Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              iPhone                                          │
│  CarPlay UI → H.264 Encoder → AirPlay Stream                                │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ USB iAP2 or WiFi AirPlay
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CPC200-CCPA Adapter                                  │
│  AirPlay Receiver → Unix Socket → ARMadb-driver                             │
│  └── Add 16B USB header + 20B video header                                  │
│  └── Forward H.264 unchanged                                                │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ USB Bulk Transfer (Type 0x06)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         carlink_native App                                   │
│                                                                              │
│  UsbDeviceWrapper                                                            │
│       │                                                                      │
│       ▼                                                                      │
│  VideoDataProcessor.processVideoDirect()                                     │
│       │ Extract PTS from offset 0x1C                                        │
│       ▼                                                                      │
│  H264Renderer.processDataDirectWithPts()                                     │
│       │                                                                      │
│       ▼                                                                      │
│  PacketRingByteBuffer                                                        │
│       │ Store packet: [len][skip=20][data]                                  │
│       ▼                                                                      │
│  feedCodec() → fillAllAvailableCodecBuffers()                               │
│       │ Read H.264 (skip 20-byte header)                                    │
│       │ Detect NAL type                                                      │
│       │ Cache SPS/PPS                                                        │
│       │ Quality Control (optional P-frame drop)                              │
│       ▼                                                                      │
│  MediaCodec.queueInputBuffer()                                               │
│       │                                                                      │
│       ▼                                                                      │
│  onOutputBufferAvailable()                                                   │
│       │                                                                      │
│       ▼                                                                      │
│  releaseOutputBuffer(render=true)                                            │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         GM Info 3.7 Platform                                 │
│                                                                              │
│  SurfaceView (HWC Overlay Path)                                             │
│       │                                                                      │
│       ▼                                                                      │
│  SurfaceFlinger                                                              │
│       │                                                                      │
│       ▼                                                                      │
│  HWC 2.1 (DEVICE composition)                                               │
│       │                                                                      │
│       ▼                                                                      │
│  Display: 2400x960 @ 60Hz                                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Timing Analysis

### 5.1 Latency Budget

| Stage | Estimated Latency |
|-------|-------------------|
| iPhone encoding | 8-16 ms |
| AirPlay/USB transport | 1-5 ms |
| Adapter passthrough | <1 ms |
| USB bulk transfer | 1-2 ms |
| Ring buffer write | <1 ms |
| Codec queue | <1 ms |
| Intel VPU decode | 5-10 ms |
| Buffer queue | 0-16 ms (vsync) |
| Display scanout | 8 ms |
| **Total** | **~30-50 ms** |

### 5.2 PTS Handling

**Source (iPhone):**
- CarPlay uses variable frame rate
- PTS in milliseconds (1kHz clock)
- Frames only sent when UI changes

**Transport (Adapter):**
- PTS preserved at video header offset 0x1C
- No modification by adapter

**Receiver (carlink_native):**
```java
// Extract PTS from video header
int sourcePtsMs = // read from offset 0x1C

// Convert to microseconds for MediaCodec
long sourcePtsUs = sourcePtsMs * 1000L;

// Queue to PTS queue (synchronized with ring buffer packets)
sourcePtsQueue.offer(sourcePtsUs);

// Later, when feeding codec:
Long sourcePts = sourcePtsQueue.poll();
mCodec.queueInputBuffer(index, 0, size, sourcePts, 0);
```

### 5.3 Frame Rate Characteristics

| Metric | Value | Source |
|--------|-------|--------|
| Target FPS | 60 | Adapter configuration |
| Actual FPS | 1.7-50 (variable) | USB capture analysis |
| Typical active FPS | 25-30 | Navigation/animation |
| Static screen FPS | 1-5 | Power saving |
| PTS delta typical | 16-17 ms | 60 fps periods |
| PTS delta range | 10-500+ ms | Varies with content |

---

## 6. Comparison with GM Native CarPlay (CINEMO)

GM AAOS has native CarPlay via CINEMO framework. How does carlink_native differ?

| Aspect | CINEMO (Native) | carlink_native |
|--------|-----------------|----------------|
| Transport | USB NCM + IPv6 | USB Bulk (via adapter) |
| Video Source | Direct iPhone AirPlay | Adapter passthrough |
| Decoder | Software NVDEC | Hardware OMX.Intel |
| Library | libNmeVideoSW.so | Android MediaCodec |
| Quality Control | Built-in | Custom implementation |
| SEI Processing | Full (freeze, snapshot) | Not implemented |
| Latency | ~20-40 ms | ~30-50 ms |

**Key Difference:** CINEMO uses software decoder for tighter AirPlay timing integration. carlink_native uses hardware decoder which is faster but less integrated.

---

## 7. Key Code Locations

| Component | File | Lines |
|-----------|------|-------|
| USB packet reception | UsbDeviceWrapper.kt | ~200 |
| Video processor creation | CarlinkManager.kt | 1668-1706 |
| Ring buffer write | PacketRingByteBuffer.java | 180-254 |
| Codec feeding | H264Renderer.java | 343-413 |
| MediaCodec callback | H264Renderer.java | 1158-1431 |
| NAL detection | H264Renderer.java | 1554-1595 |
| SPS/PPS caching | H264Renderer.java | 1611-1690 |
| Quality Control | H264Renderer.java | 1200-1240 |
| Intel VPU workaround | H264Renderer.java | 577-597 |

---

## References

- video_protocol.md: `/Users/zeno/Downloads/carlink_native/documents/reference/firmware/RE_Documention/02_Protocol_Reference/video_protocol.md`
- usb_protocol.md: `/Users/zeno/Downloads/carlink_native/documents/reference/firmware/RE_Documention/02_Protocol_Reference/usb_protocol.md`
- carplay_video_pipeline.md: `/Users/zeno/Downloads/carlink_native/documents/reference/gminfo/video/carplay_video_pipeline.md`
- h264_nal_processing.md: `/Users/zeno/Downloads/carlink_native/documents/reference/gminfo/video/h264_nal_processing.md`
- H264Renderer.java: Current source code
- CarlinkManager.kt: Current source code
- PacketRingByteBuffer.java: Current source code
