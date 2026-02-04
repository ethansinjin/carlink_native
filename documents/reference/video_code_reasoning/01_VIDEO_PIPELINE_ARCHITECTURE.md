# Video Pipeline Architecture

## Document Created: 2026-01-29
## Purpose: Document the complete video flow from CarPlay to screen rendering

---

## Overview Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           CARPLAY VIDEO PIPELINE                                 │
└─────────────────────────────────────────────────────────────────────────────────┘

┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   iPhone     │    │  CPC200-CCPA │    │   GM AAOS    │    │  carlink_    │
│   CarPlay    │───▶│   Adapter    │───▶│   Host OS    │───▶│   native     │
│              │    │              │    │              │    │              │
│  H.264 Enc   │    │  Passthrough │    │  USB Stack   │    │  H.264 Dec   │
└──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
       │                   │                   │                   │
       ▼                   ▼                   ▼                   ▼
  AirPlay/iAP2        USB Bulk           USB Driver          MediaCodec
  H.264 Stream        Transfer           + Permissions       + Surface
```

---

## 1. iPhone / CarPlay (Video Source)

### Role: Video Encoder and Source

**What it does:**
- Renders CarPlay UI on iPhone
- Encodes screen content to H.264 video stream
- Transmits via AirPlay protocol (wireless) or iAP2 (wired)

**H.264 Encoding Characteristics:**
| Parameter | Value |
|-----------|-------|
| Profile | High Profile (100) |
| Level | 5.0 (Level IDC = 50) |
| NAL Format | Annex B (00 00 00 01 start codes) |
| Color Format | YUV420 (NV12) |
| Frame Rate | Variable (1.7 - 60 fps) |
| Keyframe Interval | ~2 seconds (with SPS+PPS) |

**Important Behaviors:**
- Frame rate is NOT constant - drops to 1-5 fps on static screens
- SPS+PPS sent with every IDR frame (not just at stream start)
- Responds to keyframe requests within 100-200ms
- Bitrate adapts to content complexity (70 kbps - 15 Mbps)

---

## 2. CPC200-CCPA Adapter (Protocol Bridge)

### Role: Protocol Translation (NOT Transcoding)

**Critical Understanding:** The adapter does NOT re-encode video. It is a passthrough device.

**What it does:**
1. Receives H.264 NAL units from iPhone via AirPlay/iAP2
2. Parses NAL units for keyframe detection (logging only)
3. Extracts presentation timestamp
4. Prepends USB protocol header (36 bytes total)
5. Forwards raw H.264 data unchanged to USB endpoint

**USB Message Structure:**
```
┌─────────────────────────────────────────────────┐
│ USB HEADER (16 bytes)                           │
├─────────┬──────┬─────────────┬──────────────────┤
│ Offset  │ Size │ Field       │ Value            │
├─────────┼──────┼─────────────┼──────────────────┤
│ 0x00    │  4   │ Magic       │ 0x55AA55AA       │
│ 0x04    │  4   │ PayloadLen  │ Bytes after hdr  │
│ 0x08    │  4   │ MsgType     │ 6 (VideoData)    │
│ 0x0C    │  4   │ Checksum    │ MsgType ^ 0xFFF  │
├─────────┴──────┴─────────────┴──────────────────┤
│ VIDEO HEADER (20 bytes)                         │
├─────────┬──────┬─────────────┬──────────────────┤
│ 0x10    │  4   │ Width       │ e.g., 2400       │
│ 0x14    │  4   │ Height      │ e.g., 960        │
│ 0x18    │  4   │ EncoderState│ Stream ID        │
│ 0x1C    │  4   │ PTS         │ 1kHz timestamp   │
│ 0x20    │  4   │ Flags       │ Usually 0x00     │
├─────────┴──────┴─────────────┴──────────────────┤
│ H.264 PAYLOAD (variable)                        │
│ Raw NAL units with 00 00 00 01 start codes      │
│ (Unchanged from iPhone output)                  │
└─────────────────────────────────────────────────┘
```

**Message Types:**
- `0x06` - Main video stream
- `0x2C` - Navigation video (iOS 13+, separate stream)

**Keyframe Request Handling:**
- Host sends Command `0x0C` (Frame) to request IDR
- Adapter forwards request to iPhone
- Response arrives within 98-181ms (measured)
- Response contains: SPS + PPS + IDR frame

---

## 3. GM AAOS Host OS (Android Automotive)

### Role: USB Communication Layer

**What it does:**
1. USB device enumeration and permission management
2. USB bulk transfer handling
3. Provides MediaCodec API access
4. Manages SurfaceFlinger and HWC composition

**USB Stack:**
```
Application (carlink_native)
        │
        ▼
  UsbDeviceConnection
        │
        ▼
  USB Bulk Transfer (bulkTransfer())
        │
        ▼
  Linux USB Gadget Driver
        │
        ▼
  Physical USB 2.0 Connection
```

**Relevant System Services:**
| Service | Role |
|---------|------|
| UsbManager | Device enumeration, permissions |
| MediaCodecService | Hardware decoder allocation |
| SurfaceFlinger | Display composition |
| HWC 2.1 | Hardware composer overlay |

**GM-Specific Considerations:**
- CINEMO service runs continuously (native CarPlay stack)
- Video focus management (`mVideoFocus`)
- Intel VPU shared between applications
- Custom automotive audio zones

---

## 4. carlink_native Application (Video Decoder)

### Role: H.264 Decoding and Rendering

**Component Architecture:**
```
┌─────────────────────────────────────────────────────────────┐
│                    carlink_native                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐    ┌──────────────────┐    ┌───────────┐  │
│  │ USB Layer   │───▶│ PacketRingBuffer │───▶│ H264      │  │
│  │             │    │                  │    │ Renderer  │  │
│  │ UsbDevice   │    │ Thread-safe      │    │           │  │
│  │ Wrapper     │    │ circular buffer  │    │ MediaCodec│  │
│  │             │    │ (2-8 MB)         │    │ Async     │  │
│  └─────────────┘    └──────────────────┘    └─────┬─────┘  │
│        │                                          │        │
│        │ USB Bulk Read                            │        │
│        │ Thread (high priority)                   ▼        │
│        │                              ┌───────────────────┐│
│        │                              │   SurfaceView     ││
│        │                              │   (HWC Overlay)   ││
│        │                              └───────────────────┘│
│        │                                        │          │
└────────┼────────────────────────────────────────┼──────────┘
         │                                        │
         ▼                                        ▼
    USB Hardware                           Display Panel
    (CPC200-CCPA)                          (2400x960)
```

**Data Flow Within App:**

```
1. USB RECEPTION
   UsbDeviceWrapper.readLoop()
         │
         ▼ Parse 36-byte header
         │ Extract: width, height, PTS, H.264 data
         │
2. RING BUFFER WRITE
         │
         ▼ PacketRingByteBuffer.directWriteToBuffer()
         │ Format: [4B length][4B skip][H.264 data]
         │ Enqueue PTS to sourcePtsQueue (current code)
         │
3. CODEC FEEDING (Two paths)
         │
    ┌────┴────┐
    │         │
    ▼         ▼
 Path A    Path B
 Callback  feedCodec()
 Direct    Executor
    │         │
    └────┬────┘
         │
         ▼ fillFirstAvailableCodecBuffer()
         │ Read from ring buffer
         │ Dequeue PTS
         │ Detect NAL type
         │ Quality control (current code)
         │
4. MEDIACODEC PROCESSING
         │
         ▼ mCodec.queueInputBuffer(index, offset, size, pts, flags)
         │
         ▼ [Hardware H.264 Decode - Intel VPU]
         │
         ▼ onOutputBufferAvailable callback
         │
5. SURFACE RENDERING
         │
         ▼ mCodec.releaseOutputBuffer(index, true)
         │
         ▼ Frame written to Surface
         │
         ▼ SurfaceFlinger composition
         │
         ▼ HWC overlay to display
```

---

## 5. Threading Model

```
┌─────────────────────────────────────────────────────────────┐
│                     THREAD ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Thread 4637 (Main)                                         │
│  ├── UI rendering                                           │
│  ├── Codec lifecycle (create/start/stop)                    │
│  └── Surface management                                     │
│                                                             │
│  Thread 4987/6619 (USB Read)                                │
│  ├── Priority: THREAD_PRIORITY_URGENT_AUDIO                 │
│  ├── USB bulk transfers                                     │
│  └── Ring buffer writes                                     │
│                                                             │
│  Thread 4682 (codecCallbackHandler)                         │
│  ├── Priority: THREAD_PRIORITY_URGENT_AUDIO                 │
│  ├── onInputBufferAvailable                                 │
│  └── onOutputFormatChanged                                  │
│                                                             │
│  Thread 4778/4779/6592/6593 (mediaCodec2 executor)          │
│  ├── onOutputBufferAvailable                                │
│  └── releaseOutputBuffer                                    │
│                                                             │
│  Thread (mediaCodec1 executor)                              │
│  └── feedCodec() / fillAllAvailableCodecBuffers()           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. Key Synchronization Points

### Ring Buffer Synchronization:
```
USB Thread                    Codec Callback Thread
    │                                │
    ▼                                │
directWriteToBuffer()                │
    │ synchronized                   │
    │                                ▼
    │                         onInputBufferAvailable()
    │                                │
    │                                ▼
    │                         ringBuffer.isEmpty()?
    │                                │ synchronized
    │                                │
    │                                ▼
    │                         readPacketInto()
    │                                │ synchronized
```

### Codec State Synchronization:
```
Main Thread                   Callback Thread
    │                                │
    ▼                                │
reset() / pause()                    │
    │ synchronized(codecLock)        │
    │                                ▼
    │                         onInputBufferAvailable()
    │                                │ synchronized(codecLock)
    │                                │
    │ mCodec.flush()                 │ Check codec == mCodec
    │                                │
```

---

## 7. Latency Budget

| Stage | Component | Typical Latency |
|-------|-----------|-----------------|
| 1 | iPhone encoding | 5-10 ms |
| 2 | AirPlay/iAP2 transmission | 1-5 ms |
| 3 | Adapter passthrough | < 1 ms |
| 4 | USB transfer | 1-5 ms |
| 5 | Ring buffer write | < 1 ms |
| 6 | Codec input queue | < 1 ms |
| 7 | H.264 decode (Intel VPU) | 5-15 ms |
| 8 | Surface composition | 1-2 ms |
| 9 | VSYNC alignment | 0-16 ms |
| **Total** | **End-to-end** | **20-50 ms** |

**Observed in Stable Period (18:01:00):**
- Write-to-Output: 6-12 ms
- Total measured: ~25-35 ms

**Observed in Unstable Period:**
- Write-to-Output: 45,000 - 92,000 ms (!)
- Indicates severe codec input starvation

---

## 8. Error Recovery Mechanisms

### Keyframe Request Flow:
```
Decoder detects no output for N frames
         │
         ▼
keyframeCallback.onKeyframeNeeded()
         │
         ▼
Send Command 0x0C to adapter
         │
         ▼
Adapter requests IDR from iPhone
         │
         ▼
iPhone sends SPS + PPS + IDR (100-200ms)
         │
         ▼
Decoder receives fresh reference frame
         │
         ▼
P-frames can now be decoded
```

### Codec Reset Flow:
```
Error or stall detected
         │
         ▼
H264Renderer.reset()
         │
         ├──▶ Intel VPU? ──▶ Full recreation (stop/release/create/start)
         │
         └──▶ Other? ──▶ flush() + start()
         │
         ▼
Clear ring buffer
Clear PTS queue
Clear buffer indices
         │
         ▼
Request keyframe
         │
         ▼
Wait for SPS + PPS + IDR
```

---

## 9. Platform-Specific Considerations

### Intel VPU (OMX.Intel.hw_vd.h264):
- Does NOT properly reset reference frames on flush()
- Requires full codec recreation for reliable reset
- May not support KEY_LOW_LATENCY
- Returns UnsupportedIndex errors for some queries (informational)

### GM AAOS CINEMO:
- Native CarPlay stack uses SOFTWARE decoder (libNmeVideoSW.so)
- Runs continuously, may affect MediaCodec scheduling
- Uses different video pipeline (NME framework)
- Has its own QualityControl() mechanism

### Surface/HWC:
- SurfaceView uses HWC overlay (low latency path)
- Surface recreation triggers codec surface update
- setOutputSurface() available for surface changes without codec recreation
