# Complete Video Code Lineage and History

## Document Created: 2026-01-29
## Purpose: Comprehensive documentation of video code evolution across all projects

---

## Project Lineage

```
abuharsky/carplay (Jan 2024)
    │   Original Flutter CarPlay plugin
    │   Package: ru.bukharskii.carplay
    │   ~216 lines H264Renderer, ~240 lines PacketRingByteBuffer
    │
    ▼
lvalen91/Carlink (Aug 2025)
    │   Flutter fork with major expansion
    │   Package: com.carlink
    │   Added: Audio, PlatformDetector, extensive troubleshooting
    │   Final: v2.1.0 (Jan 2026)
    │
    ▼
lvalen91/carlink_native (Dec 2025)
    │   Native Android rewrite (no Flutter)
    │   Current development: Revision [55]
    │   Next: Revision [56]
    │
    └── MotoInsight/carlink_native (Jan 2026)
            Fork with independent modifications
            Build 61 (Jan 11, 2026)
```

---

## 1. abuharsky/carplay - Original Source

**Repository:** https://github.com/abuharsky/carplay
**Created:** January 22, 2024
**Language:** Dart/Flutter

### Commit History

| SHA | Date | Description |
|-----|------|-------------|
| f426be8 | 2024-01-22 | first commit |
| 8755dff | 2024-01-22 | added usb on mac hack |
| f0a3068 | 2024-01-22 | possible fixed #1 by adding await to timer |
| b4a485f | 2024-07-20 | Update README.md |
| f7a194f | 2024-07-20 | initial |
| 2948322 | 2024-07-20 | Update README.md |

### Original Video Architecture

**H264Renderer.java (~216 lines):**
- Basic MediaCodec async callback implementation
- ArrayList for buffer indices
- Timer-based retry on start failure
- Simple direct feed in callback

**PacketRingByteBuffer.java (~240 lines):**
- Circular buffer with auto-resize
- Synchronized read/write operations
- Direct write callback interface

**Key Characteristics:**
- Minimal implementation
- No platform-specific handling
- No recovery mechanisms beyond basic retry
- No performance monitoring

---

## 2. lvalen91/Carlink - Flutter Expansion

**Repository:** https://github.com/lvalen91/Carlink
**Created:** August 27, 2025 (forked/derived from abuharsky)
**Final Version:** v2.1.0 (January 4, 2026)

### Commit History

| SHA | Date | Description |
|-----|------|-------------|
| ec22471 | 2025-08-27 | Initial commit |
| 382b0a7 | 2025-08-27 | Project Upload |
| 59091fa | 2025-08-28 | V 1.5.0 |
| 4e780be | 2025-09-04 | 1.6 |
| d594d3b | 2025-09-02 | Code reconstruction |
| 8420cdb | 2025-11-19 | **v1.9.0** - Major rewrite |
| 8136197 | 2025-11-28 | **Audio Support** |
| 22ba92e | 2026-01-04 | **v2.1.0** - Final Flutter version |

### Major Version Changes

#### v1.9.0 (Nov 19, 2025) - Major Rewrite
**Files Changed:**
- H264Renderer.java: +226/-43 lines
- PacketRingByteBuffer.java: +105/-68 lines

**New Components:**
- AppExecutors.java - Thread pool management
- BulkTransferManager.kt - USB transfer handling
- VideoTextureManager.kt - Flutter texture integration
- VideoHandler.kt - Platform channel handler

**Architectural Changes:**
- Package renamed: ru.bukharskii.carlink → com.carlink
- Added dedicated executor threads for codec operations
- Enhanced buffer management with size buckets
- Performance monitoring (FPS, throughput)

#### Audio Support (Nov 28, 2025)
**H264Renderer.java: +15/-2 lines (minor video changes)**

**New Audio Components:**
- AudioPlaybackManager.kt
- DualStreamAudioManager.kt
- MicrophoneCaptureManager.kt
- MediaSessionManager.kt
- CarlinkMediaBrowserService.kt

**Troubleshooting Documentation Created:**
- release_86/session_1-6/ - Audio crash, ANR, buffer overflow analysis

#### v2.1.0 (Jan 4, 2026) - Final Flutter Version
**Files Changed:**
- H264Renderer.java: +443/-76 lines
- PacketRingByteBuffer.java: +4/-3 lines

**New Platform Components:**
- PlatformDetector.kt - Intel/GM AAOS detection
- MediaCodecConfig.kt - Platform-specific decoder config
- AudioConfig.kt - Platform audio settings
- AudioDebugLogger.kt - Diagnostic logging

**New Troubleshooting Docs:**
- video_instability_analysis.md - setCallback order, ringBuffer.reset fixes
- video_investigation_dec5.md - Intel async mode analysis
- intel_async_revert_analysis.md - SYNC mode failure analysis

### Issues Identified and Fixed in Carlink

| Issue | Fix | Revision |
|-------|-----|----------|
| setCallback after configure | Move setCallback before configure | Dec 1, 2025 |
| ringBuffer not cleared on reset | Add ringBuffer.reset() | Dec 1, 2025 |
| No soft reset option | Add softReset() with flush() | Dec 1, 2025 |
| Intel SYNC mode 0 FPS | Revert to async mode | Dec 5, 2025 |
| Audio-video thread contention | Identified, not fully resolved | Dec 5, 2025 |

---

## 3. lvalen91/carlink_native - Native Android

**Repository:** https://github.com/lvalen91/carlink_native
**Created:** December 7, 2025
**Current Local:** Revision [55]
**GitHub Last Sync:** ~Revision [54] (Jan 28, 2026)

### Commit History

| SHA | Date | Description | Revision |
|-----|------|-------------|----------|
| 3c4648a | 2025-12-07 | Initial commit | - |
| 15457c6 | 2025-12-09 | Initial Push | [1-10] |
| e06d804 | 2025-12-10 | Video Fix, +/- Settings | [11-12] |
| 197220f | 2025-12-10 | Video Testing | [13] |
| 6dc00ef | 2025-12-19 | Removed AdapterStatusMonitor | [14-15] |
| fccfa1b | 2025-12-21 | See revision.txt | [16-20] |
| dc361f3 | 2025-12-22 | see revision.txt | [21-22] |
| 6dfe7f6 | 2025-12-23 | See revisions.txt | [23-27] |
| fff86dd | 2025-12-26 | Video Code Test | [28-29] |
| b128020 | 2025-12-27 | See revisions | [30] |
| 4d27cfd | 2025-12-28 | Video Code changes | [31] |
| fcd58ea | 2025-12-28 | Video degrading Fix Test | [31] |
| c0a9227 | 2025-12-29 | Audio Tweaking | [32-33] |
| ecb8ab5 | 2025-12-29 | See revision | [34-39] |
| 9248cc4 | 2025-12-29 | Doc Updates | [40] |
| 7e85306 | 2025-12-30 | See revisions.txt | [41-44] |
| 7abdd0f | 2026-01-12 | See revisions.txt | [45-46] |
| 64e8d55 | 2026-01-14 | See Revisions.txt | [47-50] |
| cdb53fd | 2026-01-21 | See revisions.txt | [51] |
| 3eb5c73 | 2026-01-22 | See Revisions.txt | [52] |
| acecbb1 | 2026-01-23 | See Revisions.txt | [53] |
| 2895f6b | 2026-01-28 | See Revisions.txt | [54] |
| 7866d77 | 2026-01-28 | See Revisions.txt | [54] |

### Revision History (from revisions.txt)

| Rev | Date | Description |
|-----|------|-------------|
| [1-4] | Dec | Initial development, basic video output |
| [9] | Dec | Video code reverted to Flutter v1.9.0 |
| [11-12] | Dec | Video code rewrite |
| [13] | Dec 8 | "Video Frame message not being recovered properly. Corrected for stable Video." |
| [14] | Dec | Removed Status page |
| [16] | Dec 10 | Video Frame Interval - Timer to coroutine |
| [18] | Dec | PlatformDetector fix for hardware decoder |
| [22] | Dec | "Return to App Blank Video fixed?" |
| [28] | Dec | "App resume testing for Video black screen" |
| [30] | Dec 27 | "Video Code change TESTING. Seems unstable or long to recover, too aggressive and compounding recovery logic." |
| [31] | Dec 28 | ByteBuffer.wrap race condition fix (System.arraycopy) |
| [32] | Dec 29 | readPacketInto() optimization |
| [40] | Jan 11 | SPS/PPS caching for keyframe reference |
| [45] | Jan 12 | SPS/PPS Cache fix |
| [51] | Jan 21 | Skip prepending when SPS already present |
| [52] | Jan 22 | Intel VPU workaround - full codec recreation |
| [54] | Jan 29 | GM AAOS optimization - Source PTS, reduced buffers, Quality Control |
| [55] | Jan 29 | Audio types for GM AAOS volume control, video changes |

### Feature Evolution

| Feature | First Added | Current State [55] |
|---------|-------------|-------------------|
| Basic MediaCodec | [1] | Present |
| Ring buffer | [1] | Present, optimized |
| PlatformDetector | [18] | Present |
| SPS/PPS caching | [40] | Present |
| Intel VPU workaround | [52] | Present |
| Source PTS queue | [54] | Present |
| Quality Control | [54] | Present (lag≥5) |
| Reduced buffer sizes | [54] | 4MB (was 16MB) |

### Code Size Evolution

| Revision | H264Renderer Lines | Notes |
|----------|-------------------|-------|
| [13] | ~1060 | Claimed "stable" |
| [30] | ~1454 | +37%, "unstable" |
| [52] | ~1558 | +47%, Intel workaround |
| [55] | ~1888 | +78%, QC + Source PTS |

---

## 4. MotoInsight/carlink_native - Independent Fork

**Repository:** https://github.com/MotoInsight/carlink_native
**Forked:** January 3, 2026 (from b128020, pre-[31])
**Last Commit:** January 11, 2026 (Build 61)

### Commit History

| SHA | Date | Description |
|-----|------|-------------|
| e45d158 | 2026-01-03 | OSEV Build 49 - First Build |
| 362f84d | 2026-01-03 | Enable Android Auto on every connection |
| 943cc96 | 2026-01-04 | Build 52 - Enhanced video resume with keyframe requests |
| 0a1b310 | 2026-01-08 | Build 56 - Fix black screen after standby |
| 9230eae | 2026-01-08 | Updated README |
| d8d8fce | 2026-01-10 | Build 60 - Major resume/black-screen fixes |
| 2532199 | 2026-01-11 | Build 61 - Post-reconnect pixelation/distortion fix |

### Architectural Differences from Main

| Aspect | MotoInsight | Main Repo [55] |
|--------|-------------|----------------|
| H264Renderer | 1249 lines | 1888 lines |
| Quality Control | None | lag≥5 threshold |
| Source PTS Queue | None | Present |
| Intel VPU Workaround | None | Full recreation |
| Callback Style | Direct feed | Dual path (callback + executor) |

### Their Focus Areas

1. **Suspend/Resume Handling**
   - Surface rebind throttling
   - Ring buffer clearing on resume
   - Lifecycle flag gating

2. **Keyframe Management**
   - Double/triple keyframe bursts
   - Immediate request on state transitions
   - Delayed post-stream recovery

3. **Issues They're Addressing**
   - Black screen after standby
   - Post-reconnect pixelation/distortion
   - Quick reconnect stability

---

## Summary: Video Code Across All Projects

### Complexity Growth

```
abuharsky/carplay    → ~216 lines (minimal)
lvalen91/Carlink     → ~860 lines (v2.1.0, with audio)
carlink_native [13]  → ~1060 lines (first "stable")
carlink_native [55]  → ~1888 lines (current)
MotoInsight fork     → ~1249 lines (simpler approach)
```

### Key Architectural Decisions Over Time

| Decision | When | Rationale | Outcome |
|----------|------|-----------|---------|
| Async MediaCodec | Original | Low latency | Standard approach |
| Dedicated executors | v1.9.0 | Thread management | Added complexity |
| setCallback before configure | Dec 2025 | Android API requirement | Fixed initialization |
| Ring buffer clear on reset | Dec 2025 | Stale packet prevention | Improved recovery |
| Intel async mode | Dec 2025 | SYNC mode broken | Platform-specific |
| ByteBuffer copy vs wrap | [31] | Memory safety | Fixed corruption |
| readPacketInto() | [32] | Reduce allocations | Optimization |
| SPS/PPS caching | [40] | Faster recovery | Improved keyframe handling |
| Intel VPU full recreation | [52] | flush() insufficient | Platform workaround |
| Source PTS queue | [54] | A/V sync | Added synchronization complexity |
| Quality Control | [54] | Reduce lag | May drop too aggressively |
| Reduced buffers | [54] | Lower latency | Less headroom |

### Current State Summary

**Main repo [55]:**
- Most feature-complete
- Highest complexity
- Video issues persist (codec input starvation per logcat)
- Quality Control may be counterproductive

**MotoInsight fork:**
- Simpler architecture
- No QC, no Source PTS
- Different issues (suspend/resume focused)
- Semi-active development

### Open Questions

1. Is Quality Control helping or hurting in [55]?
2. Is Source PTS queue staying synchronized?
3. Why does callback frequency not match write frequency?
4. What causes the 30-second stable period after surface recreation?
5. Are there platform-specific issues beyond Intel VPU?

---

## Document References

- `00_RESOURCE_LOCATIONS.md` - File paths and archives
- `01_VIDEO_PIPELINE_ARCHITECTURE.md` - Data flow diagram
- `02_VIDEO_CODE_EVOLUTION.md` - Detailed revision analysis
- `03_LOGCAT_ANALYSIS.md` - Session evidence
- `04_CURRENT_STATE_ASSESSMENT.md` - [55] code analysis
- `05_SYNTHESIS_AND_RECOMMENDATIONS.md` - Recommendations for [56]
- `06_ORIGINAL_CARLINK_ANALYSIS.md` - Flutter project analysis
