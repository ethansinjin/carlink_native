# Video Analysis Resource Locations

## Document Created: 2026-01-29
## Purpose: Central index of all resources for video pipeline analysis

---

## 1. Current Project Source Code

**Location:** `/Users/zeno/Downloads/carlink_native/`

### Key Video Files:
| File | Path | Lines | Purpose |
|------|------|-------|---------|
| H264Renderer.java | `app/src/main/java/com/carlink/video/H264Renderer.java` | 1888 | Core H.264 decoder using MediaCodec async mode |
| PacketRingByteBuffer.java | `app/src/main/java/com/carlink/video/PacketRingByteBuffer.java` | 480 | Thread-safe ring buffer for video packets |
| VideoDebugLogger.java | `app/src/main/java/com/carlink/util/VideoDebugLogger.java` | ~450 | Structured video pipeline debug logging |
| VideoSurfaceView.kt | `app/src/main/kotlin/com/carlink/ui/components/VideoSurfaceView.kt` | 91 | Android SurfaceView for HWC overlay rendering |
| VideoSurface.kt | `app/src/main/kotlin/com/carlink/ui/components/VideoSurface.kt` | 111 | Jetpack Compose wrapper for video surface |
| CarlinkManager.kt | `app/src/main/kotlin/com/carlink/CarlinkManager.kt` | ~350 | Central manager orchestrating video, audio, USB |
| PlatformDetector.kt | `app/src/main/kotlin/com/carlink/platform/PlatformDetector.kt` | ~180 | Hardware platform detection (Intel, ARM, GM AAOS) |
| MessageTypes.kt | `app/src/main/kotlin/com/carlink/protocol/MessageTypes.kt` | 476 | Protocol message types including VIDEO_DATA (0x06) |

---

## 2. Reference Documentation

**Location:** `/Users/zeno/Downloads/carlink_native/documents/reference/`

### Adapter Firmware (CPC200-CCPA) - SSH Access Available

**Base Path:** `firmware/RE_Documention/`

#### 01_Firmware_Architecture/
| Document | Content |
|----------|---------|
| `configuration.md` | Adapter configuration options |
| `firmware_encryption.md` | Encryption implementation |
| `flash_layout.md` | Flash memory structure |
| `hardware_platform.md` | Hardware specifications |
| `heartbeat_analysis.md` | Keep-alive protocol |
| `initialization.md` | Startup sequence |
| `version_comparison.md` | Firmware version differences |
| `web_interface.md` | Web configuration interface |
| `web_settings_reference.md` | Settings parameters |

#### 02_Protocol_Reference/
| Document | Content |
|----------|---------|
| `audio_protocol.md` | Audio data format and streaming |
| `command_ids.md` | All protocol command definitions |
| `device_identification.md` | Device identification protocol |
| `usb_protocol.md` | USB bulk transfer specification |
| `video_protocol.md` | **Video frame format, headers, H.264 passthrough** |
| `wireless_carplay.md` | Wireless CarPlay protocol |

#### 03_Audio_Processing/
| Document | Content |
|----------|---------|
| `audio_formats.md` | Supported audio formats |
| `microphone_processing.md` | Microphone input handling |

#### 03_Security_Analysis/
| Document | Content |
|----------|---------|
| `crypto_stack.md` | Cryptographic implementation |
| `kernel_encryption.md` | Kernel-level encryption |
| `vulnerabilities.md` | Security analysis |

#### 04_Implementation/
| Document | Content |
|----------|---------|
| `capture_playback.md` | Session capture feature |
| `firmware_update.md` | Update mechanism |
| `host_app_guide.md` | **Host application implementation guide** |
| `session_examples.md` | Protocol session examples |

#### 05_Reference/
| Document | Content |
|----------|---------|
| `firmware_internals.md` | Internal implementation details |
| `android_mediacodec/README.md` | MediaCodec integration notes |
| `vehicle_platforms/gminfo/README.md` | GM platform specifics |
| `binary_analysis/key_binaries.md` | Reverse-engineered binaries |

---

### GM Info 3.7 Platform (AAOS) - ADB Access Available

**Base Path:** `gminfo/`

#### Video Subsystem (`video/`)
| Document | Content |
|----------|---------|
| `README.md` | Video subsystem overview |
| `carplay_video_pipeline.md` | **CarPlay video flow on GM platform** |
| `cinemo_nme_framework.md` | **CINEMO/NME native CarPlay stack coexistence** |
| `display_subsystem.md` | Display hardware/software architecture |
| `h264_nal_processing.md` | **H.264 NAL unit handling specifics** |
| `pts_timing_strategies.md` | **Presentation timestamp strategies** |
| `software_rendering.md` | Software decode fallback path |
| `video_codecs.md` | Available video codecs |

#### Audio Subsystem (`audio/`)
| Document | Content |
|----------|---------|
| `README.md` | Audio subsystem overview |
| `audio_codecs.md` | Available audio codecs |
| `audio_effects.md` | Audio processing effects |
| `audio_subsystem.md` | Audio architecture |
| `automotive_audio.md` | AAOS audio specifics |
| `carplay_audio_pipeline.md` | CarPlay audio flow |

#### Intel Media SDK (`intel_media_sdk/`)
| Document | Content |
|----------|---------|
| `README.md` | Intel Media SDK overview |
| `mediasdk-man.pdf` | **Intel Media SDK manual (PDF)** |
| `intel-media-developers-guide.pdf` | **Intel developer guide (PDF)** |

#### Other GM Platform Docs
| Document | Content |
|----------|---------|
| `README.md` | GM Info platform overview |
| `cluster_navigation_pipeline.md` | Cluster display integration |
| `hardware_rendering.md` | Hardware rendering details |
| `projection_comparison.md` | CarPlay vs Android Auto comparison |
| `third_party_access.md` | Third-party app integration |
| `resouces/codecs/media_codecs.md` | Complete MediaCodec list |

---

### Google/Android MediaCodec Reference

**Base Path:** `google/`

| Document | Content |
|----------|---------|
| `INDEX.md` | Google documentation index |
| `MediaCodec-AsyncCallback.md` | **Async callback mode implementation** |
| `MediaCodec-BufferHandling.md` | **Input/output buffer management** |
| `MediaCodec-H264-FormatSupport.md` | H.264 format support details |
| `MediaCodec-H264-SPS-PPS.md` | **SPS/PPS codec config handling** |
| `MediaCodec-LowLatency.md` | Low latency decoding mode |
| `MediaCodec-NDK-Reference.md` | NDK API reference |
| `MediaCodecInfo-Reference.md` | Codec capability queries |

---

### Video-Specific Quick Reference

**For video pipeline investigation, prioritize these documents:**

1. `firmware/.../02_Protocol_Reference/video_protocol.md` - Frame format
2. `gminfo/video/carplay_video_pipeline.md` - Platform video flow
3. `gminfo/video/h264_nal_processing.md` - NAL unit handling
4. `gminfo/video/pts_timing_strategies.md` - Timestamp strategies
5. `gminfo/video/cinemo_nme_framework.md` - CINEMO coexistence
6. `google/MediaCodec-AsyncCallback.md` - Callback best practices
7. `google/MediaCodec-BufferHandling.md` - Buffer management
8. `google/MediaCodec-H264-SPS-PPS.md` - SPS/PPS injection
9. `gminfo/intel_media_sdk/*.pdf` - Intel VPU specifics

---

## 3. Project Archive (Historical Source Code)

**Location:** `/Users/zeno/Downloads/project_archieve/`

### carlink_native Revisions:
| Revision | File | Date | Size | Key Changes |
|----------|------|------|------|-------------|
| 5 | carlink_native_5.zip | Dec 4 | 87MB | Early version |
| 11 | carlink_native_11.zip | Dec 7 | 96MB | Video code rewrite |
| 13 | carlink_native_13.zip | Dec 8 | 1.2MB | **STABLE VIDEO** - Frame message recovery fixed |
| 30 | carlink_native_30.zip | Dec 27 | 3.3MB | **UNSTABLE** - Aggressive recovery logic |
| 31 | carlink_native_31.zip | - | - | Race condition fix (System.arraycopy) |
| 32 | carlink_native_32.zip | - | - | readPacketInto() optimization |
| 52 | carlink_native_52.zip | Jan 22 | 1.4MB | Intel VPU full codec recreation |
| 54 | carlink_native_54.zip | Jan 29 | 6.4MB | GM AAOS optimization, Source PTS |
| 55 | carlink_native_55.zip | Jan 29 | 6.4MB | **CURRENT** - Audio types, video changes |

### Original Flutter Project:
| Revision | File | Date | Size |
|----------|------|------|------|
| 90 | carlink_90.zip | Nov 28 | 160MB |
| 91-102 | carlink_91-102.zip | Dec 1-7 | Various |

### Revision History Document:
**Location:** `/Users/zeno/Downloads/carlink_native/documents/revisions.txt`

---

## 4. Logcat Capture (Performance Analysis)

**Location:** `/Volumes/POTATO/logcat/`

### Primary Analysis File:
| File | Size | Time Range | Content |
|------|------|------------|---------|
| logcat_20260130_024549.log | 71MB | 17:46:00 - 18:06:00 | Full carlink_native session with video issues |

### Key Time Periods in Logcat:
| Time | Event | Video Status |
|------|-------|--------------|
| 17:46:00-17:46:30 | App launch, initial connection | Starting |
| 17:46:30-17:52:00 | Active video issues | Broken (0-5 FPS, lag 400-900 frames) |
| 17:52:00-18:00:00 | Continued degradation | Broken (45-92 second decode latency) |
| 18:00:25 | Surface recreation (homescreen return) | Recovery initiated |
| **18:01:00-18:01:30** | **Stable period** | **Working (25.6 FPS)** |
| 18:01:30-18:06:00 | Degradation resumed | Broken again |

### App-Specific Log Tags:
- `CARLINK` - Main application tag
- `[VIDEO]` - Video subsystem events
- `[VIDEO_RING]` - Ring buffer operations
- `[VIDEO_CODEC]` - MediaCodec operations
- `[VIDEO_DIAG]` - Diagnostic/timing information
- `[PERF]` - Performance statistics (30-second intervals)
- `[ADAPTR]` - Adapter/H264Renderer events

---

## 5. Extracted Revision Directories (Temporary)

**Location:** `/tmp/carlink_compare/`

| Directory | Content |
|-----------|---------|
| rev13/ | Revision 13 - Stable video baseline |
| rev30/ | Revision 30 - Unstable aggressive recovery |
| rev52/ | Revision 52 - Intel VPU workaround |

---

## 6. Key Metrics from Logcat Analysis

### Performance Statistics Summary:
```
Total Ring Buffer Writes: 3,461
Total Codec Inputs: ~5 (severely starved)
Total Codec Outputs: ~2,700 (during brief stable period)
Peak Frame Lag: 958 frames
Peak Decode Latency: 92 seconds
User Reset Button Presses: 5+
Codec Resets: 45
```

### Stable Period Metrics (18:01:00-18:01:30):
```
FPS: 25.6/25 (target met)
Frames Received: 767
Frames Decoded: 767 (100% efficiency)
Frame Lag: 0
Write-to-Output Latency: 6-12ms
```

---

## 7. Hardware Platform Reference

### GM Info 3.7 Specifications:
- **Display:** 2400x960 @ 60Hz (Chunghwa CMN DD134IA-01B)
- **CPU:** Intel Atom x7-A3960 (4 cores @ 1.88 GHz)
- **GPU:** Intel HD Graphics 505 (Apollo Lake)
- **Decoder:** OMX.Intel.hw_vd.h264 (4K60 @ 40Mbps, rank 256)
- **RAM:** 6GB
- **Android:** AAOS (Automotive)

### CPC200-CCPA Adapter:
- **Function:** CarPlay/Android Auto to USB bridge
- **Video:** H.264 passthrough (no transcoding)
- **Protocol:** USB bulk transfer with 36-byte header
