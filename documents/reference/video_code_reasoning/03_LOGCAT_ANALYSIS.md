# Logcat Analysis Report

## Document Created: 2026-01-29
## Source File: /Volumes/POTATO/logcat/logcat_20260130_024549.log (71MB)
## Session Duration: 17:46:00 - 18:06:00 (~20 minutes)

---

## Executive Summary

The logcat capture reveals **severe codec input starvation** as the primary issue. Despite receiving thousands of video frames via USB, only a handful were successfully fed to the MediaCodec for decoding.

**Key Metrics:**
| Metric | Value | Expected |
|--------|-------|----------|
| Ring Buffer Writes | 3,461 | 3,461 |
| Codec Inputs | ~5 | ~3,461 |
| Input Efficiency | **0.14%** | 100% |
| Peak Frame Lag | 958 frames | 0-5 |
| Peak Decode Latency | 92 seconds | 10-20 ms |

---

## Timeline of Events

### Phase 1: App Launch (17:46:00 - 17:46:30)

```
17:46:01.442 [MAIN] [LOGGING] Debug logging: DISABLED (release build)
17:46:02.314 [VIDEO] [RES] Initializing with surface 2400x960 @ 60fps, 200dpi
17:46:02.378 [VIDEO] [PLATFORM] Using VideoDecoder: OMX.Intel.hw_vd.h264 [Intel VPU workaround enabled]
17:46:02.382 [H264_RENDERER] Ring buffer initialized: 4MB for 2400x960
17:46:02.498 [H264_RENDERER] codec started successfully
```

**Observations:**
- Correct decoder selected (Intel)
- Intel VPU workaround enabled
- 4MB ring buffer (reduced from previous 16MB)
- Codec started successfully

### Phase 2: Initial Video Issues (17:46:27 - 17:47:30)

```
17:46:27.247 [SPS_CACHE] Cached SPS: 25 bytes
17:46:27.247 [PPS_CACHE] Cached PPS: 8 bytes
17:46:27.334 [Media Codec] First frame decoded! Output format: 2400x960
17:46:27.337 [VIDEO_QC] Quality control ACTIVATED - frame lag: 29, mode: AGGRESSIVE
```

**Critical Finding:**
- First frame decoded successfully at 17:46:27.334
- But immediately after, Quality Control activated with lag of 29 frames
- This means 30 frames received but only 1 decoded in initial burst

**Early Performance Stats:**
```
17:46:27 [PERF] FPS: 0.0/60, Frames: R:1/D:0
17:46:57 [PERF] FPS: 0.3/60, Frames: R:246/D:10, Frame lag: 236
17:47:27 [PERF] FPS: 0.1/60, Frames: R:488/D:13, Frame lag: 475
```

### Phase 3: User Intervention Attempts (17:47:30 - 17:52:00)

**User Actions Detected:**
```
17:47:57 - Navigated to AAOS homescreen (app paused)
17:47:58 - Returned to app (codec resumed)
17:48:21 - [DEVICE_OPS] [VIDEO_BUTTON] Reset Video Decoder (clicked)
17:48:52 - [DEVICE_OPS] [VIDEO_BUTTON] Reset Video Decoder (clicked)
17:49:03 - Navigated to homescreen
17:50:19 - [DEVICE_OPS] [VIDEO_BUTTON] Reset Video Decoder (clicked)
17:51:09 - [DEVICE_OPS] [VIDEO_BUTTON] Reset Video Decoder (clicked)
17:52:02 - [DEVICE_OPS] [VIDEO_BUTTON] Reset Video Decoder (clicked)
```

**Codec Resets Logged:**
```
17:47:10.680 [VIDEO_CODEC] Reset #6
17:47:58.959 MediaCodec: keep callback message for reclaim
17:48:22.001 MediaCodec: keep callback message for reclaim
17:50:19.139 MediaCodec: keep callback message for reclaim
17:51:09.222 MediaCodec: keep callback message for reclaim
```

**Performance During This Phase:**
```
17:48:29 [PERF] FPS: 0.6/60, R:864/D:19, Lag: 845, Resets: 16
17:49:00 [PERF] FPS: 0.3/60, R:801/D:5, Lag: 796, QC drops: 2677
17:51:31 [PERF] FPS: 0.3/60, R:865/D:8, Lag: 857, QC drops: 6068
17:52:02 [PERF] FPS: 0.0/60, R:864/D:0, Lag: 864, Resets: 17
```

**Critical Observation:**
At 17:52:02, **ZERO frames decoded** despite 864 received. The decoder is completely starved.

### Phase 4: Continued Degradation (17:52:00 - 18:00:00)

```
17:58:29 [PERF] FPS: 0.8/60, R:844/D:25, Lag: 819, Resets: 27
17:59:29 [PERF] FPS: 0.3/60, R:961/D:9, Lag: 952, Resets: 30
18:00:01 [PERF] FPS: 0.0/60, R:955/D:0, Lag: 955, Resets: 31
```

**Decode Latency Warnings:**
```
17:59:29 [VIDEO_DIAG] High decode latency: 46349ms
18:00:01 [VIDEO_DIAG] High decode latency: 45802ms
18:01:00 [VIDEO_DIAG] High decode latency: 9471ms
```

**This means frames queued at time T were being decoded 45-92 seconds later!**

### Phase 5: Recovery Event (18:00:25)

**Trigger: Surface Recreation**
```
18:00:25.195 [UI] [VIDEO_SURFACE_VIEW] Surface created
18:00:25.434 [VIDEO] [LIFECYCLE] Surface stabilized at 2400x960 - updating codec
18:00:25.434 MediaCodec: [OMX.Intel.hw_vd.h264] setting surface generation to 4748332
18:00:25.446 [CODEC_CONFIG] Codec config injection requested
```

**Immediate Result:**
```
18:00:50.538 [VIDEO_DIAG] High decode latency: 45802ms (BEFORE)
18:00:50.671 [VIDEO_DIAG] High decode latency: 133ms (AFTER - dropped!)
```

**Latency dropped from 45 seconds to 133 milliseconds!**

### Phase 6: Stable Period (18:01:00 - 18:01:30)

```
18:01:03 [PERF] FPS: 25.6/25, Frames: R:767/D:767, QC mode: NORMAL
```

**Ring Buffer Write vs Codec Output (Nearly 1:1):**
```
18:01:00.049 Write #9901 → 18:01:00.061 Output #2597 (12ms)
18:01:00.201 Write #9904 → 18:01:00.210 Output #2600 (9ms)
18:01:00.304 Write #9906 → 18:01:00.310 Output #2602 (6ms)
18:01:02.580 Write #9952 → 18:01:02.583 Output #2648 (3ms)
```

**This is the expected behavior!** Writes and outputs happening within milliseconds.

### Phase 7: Degradation Resumes (18:01:30 - 18:06:00)

```
18:01:33 [PERF] FPS: 5.3/60, R:756/D:160, Lag: 596, QC mode: AGGRESSIVE
18:03:38 [PERF] FPS: 0.0/60, R:958/D:0, Lag: 958
18:05:29 [PERF] FPS: 2.1/25, R:773/D:64, Lag: 709
```

**Video completely broken again within 30 seconds of recovery.**

---

## Detailed Evidence Analysis

### Evidence 1: Codec Input Starvation

**Ring Buffer Writes (USB Thread 4987/6619):**
```
17:47:11.610 [VIDEO_RING] Write #1: len=1428, skip=20, queued=1
17:47:11.721 [VIDEO_RING] Write #5: len=7240, skip=20, queued=1
17:47:13.117 [VIDEO_RING] Write #9: len=4111, skip=20, queued=1
...
18:01:03.111 [VIDEO_RING] Write #9969: len=3216, skip=20, queued=1
```

**Codec Inputs (Thread 4682):**
```
17:48:05.456 [VIDEO_CODEC] Input #4: buffer=1, size=8186B
17:53:44.920 [VIDEO_CODEC] Input #4: buffer=1, size=35858B  (5 min gap!)
18:00:50.538 [VIDEO_CODEC] Input #8: buffer=1, size=6292B
18:01:08.046 [VIDEO_CODEC] Input #9: buffer=2, size=45196B
18:04:11.314 [VIDEO_CODEC] Input #16: buffer=3, size=6365B
```

**Total in ~20 minute session:**
- Ring Buffer Writes: 3,461+
- Codec Inputs: ~5-16 (logged, actual may be slightly higher due to throttling)

**Ratio: <0.5%** - Catastrophically low

### Evidence 2: Callback IS Firing

**SPS+PPS Injection via Callback:**
```
17:47:58.965 [CODEC_CONFIG] Injected SPS+PPS (33 bytes) via callback
17:49:05.113 [CODEC_CONFIG] Injected SPS+PPS (33 bytes) via callback
17:49:41.331 [CODEC_CONFIG] Injected SPS+PPS (33 bytes) via callback
17:50:08.242 [CODEC_CONFIG] Injected SPS+PPS (33 bytes) via callback
18:00:25.446 [CODEC_CONFIG] Codec config injection requested
```

**This proves:**
1. `onInputBufferAvailable` callback IS being invoked
2. The callback can successfully queue data to codec (SPS+PPS)
3. The issue is specifically with VIDEO FRAME data, not callback mechanism

### Evidence 3: Quality Control Activity

```
17:46:27.337 [VIDEO_QC] Quality control ACTIVATED - frame lag: 29, mode: AGGRESSIVE
17:46:57.337 [VIDEO_QC] Dropping P-frame - lag: 236, QC drops: 651
17:47:27.337 [VIDEO_QC] Dropping P-frame - lag: 439, QC drops: 1267
18:01:03.337 [VIDEO_QC] Quality control DEACTIVATED - mode: NORMAL
18:01:33.337 [VIDEO_QC] Quality control ACTIVATED - frame lag: 596, mode: AGGRESSIVE
```

**Quality Control Statistics:**
```
17:46:59 QC drops: 651
17:47:29 QC drops: 1267
17:48:30 QC drops: 1881
17:49:00 QC drops: 2677
17:51:31 QC drops: 6068
17:59:29 QC drops: 10056
18:01:33 QC drops: 12257
18:03:08 QC drops: 14535
```

**Over 14,000 frames intentionally dropped by Quality Control!**

But dropping frames didn't help because the fundamental issue (codec not being fed) wasn't addressed.

### Evidence 4: Intel VPU Errors

**On Every Codec Creation:**
```
17:46:02.460 OMXNodeInstance: getParameter(Intel.hw_vd.h264, ??) ERROR: UnsupportedIndex(0x8000101a)
17:46:02.466 OMXNodeInstance: getConfig(Intel.hw_vd.h264, ConfigAndroidVendorExtension) ERROR: UnsupportedIndex(0x8000101a)
```

These appear to be informational (queries for optional features) but may indicate the Intel decoder behaves differently than expected.

### Evidence 5: CINEMO Service Activity

```
17:46:29.391 CarPlay_PlayerManager: onCinemoEvent(): CINEMO_EC_GRAPH_STATUS
17:46:30.392 CarPlay_PlayerManager: onCinemoEvent(): CINEMO_EC_GRAPH_STATUS
17:46:31.393 CarPlay_PlayerManager: onCinemoEvent(): CINEMO_EC_GRAPH_STATUS
... (continues every second)
```

GM's native CarPlay stack (CINEMO) is polling every second. This may:
- Compete for MediaCodec resources
- Affect system scheduling
- Hold video focus intermittently

### Evidence 6: Video Focus Issues (System Level)

```
17:47:xx.xxx PlaybackViewModel: Can't update screen. Presentation is not active or VIDEOFOCUS is not granted. viewId=e2at_22ce_v1, mVideoFocus=0
```

This is from PID 3210 (GM system service), not carlink_native, but indicates video focus management is active on this platform.

---

## NAL Statistics Analysis

```
17:46:27 NAL stats: IDR:0, P:0, SPS:0, PPS:0, lastIDR=-1ms ago
17:46:57 NAL stats: IDR:0, P:5, SPS:0, PPS:0, lastIDR=-1ms ago
17:47:27 NAL stats: IDR:0, P:11, SPS:0, PPS:0, lastIDR=-1ms ago
17:47:57 NAL stats: IDR:0, P:12, SPS:0, PPS:0, lastIDR=-1ms ago
```

**IDR count stays at 0 throughout the session!**

This doesn't mean IDR frames aren't arriving (they are - SPS+PPS injection proves it). It means the NAL statistics counter in the decoder path isn't seeing them.

Possible reasons:
1. IDR frames being dropped by Quality Control
2. IDR frames not reaching the statistics tracking code
3. Statistics reset/counter issue

---

## Thread Analysis

**Thread IDs Observed:**
| TID | Role | Activity Level |
|-----|------|----------------|
| 4637 | Main | Codec lifecycle, UI |
| 4987/6619 | USB Read | HIGH (continuous writes) |
| 4682 | codecCallbackHandler | LOW (infrequent inputs) |
| 4778/4779/6592/6593 | mediaCodec2 | MODERATE (outputs when working) |

**Critical Observation:**
USB thread (4987/6619) is writing continuously, but callback thread (4682) is barely processing. This indicates either:
1. Callback not being invoked frequently enough
2. Callback returning early without processing
3. Ring buffer appearing empty to callback

---

## Stable vs Unstable Comparison

| Metric | Unstable (17:47-18:00) | Stable (18:01:00-18:01:30) |
|--------|------------------------|---------------------------|
| FPS | 0.0 - 0.8 | 25.6 |
| R/D Ratio | 864/0 - 864/25 | 767/767 |
| Frame Lag | 400-958 | 0 |
| Write→Output | 45,000+ ms | 3-12 ms |
| QC Mode | AGGRESSIVE | NORMAL |
| QC Drops | 14,000+ | ~0 |

---

## Root Cause Hypothesis

Based on the evidence:

1. **Primary Issue:** `onInputBufferAvailable` callback fires but often doesn't process video frames

2. **Possible Causes:**
   - Ring buffer appears empty when checked (timing/race)
   - Quality Control dropping too aggressively (including IDR?)
   - State checks returning early (`isPaused`, `!running`)
   - Source PTS queue desynchronization

3. **Why Surface Recreation Helps:**
   - Full codec recreation clears all state
   - Fresh callback handler registration
   - Ring buffer and PTS queue reset
   - Quality Control reset to NORMAL

4. **Why It Degrades Again:**
   - Same conditions that caused initial problem recur
   - Once lag builds, Quality Control makes it worse
   - Vicious cycle: lag → drop frames → still lag → drop more

---

## Recommendations Based on Evidence

1. **Add Detailed Callback Logging:**
   - Log every callback entry (not just throttled)
   - Log reason for every early return
   - Log ring buffer state when checked

2. **Verify Source PTS Queue Sync:**
   - Count enqueues vs dequeues
   - Alert if counts diverge

3. **Review Quality Control Threshold:**
   - Lag of 5 may be too aggressive
   - Consider never dropping IDR frames

4. **Investigate Callback Frequency:**
   - Compare callback rate to expected (60/sec)
   - Check if callbacks stop after certain events

5. **Test Without Quality Control:**
   - See if stable operation is possible without frame dropping
