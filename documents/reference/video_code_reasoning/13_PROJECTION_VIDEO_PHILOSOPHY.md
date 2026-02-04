# Projection Design Philosophy

## Document Created: 2026-01-29
## Updated: 2026-01-30
## Purpose: Non-negotiable design philosophy for CarPlay/Android Auto projection systems
## Authority: This document defines hard rules, not suggestions

---

## 1. Fundamental Principle

> **Projection systems optimize for immediacy, not completeness.**

A frame that is late is **wrong**, regardless of quality.
A frame that is dropped is **harmless**.

---

## 2. What Projection Video IS and IS NOT

**Projection Video (CarPlay, Android Auto, AirPlay Mirroring) is NOT cinema playback.**

These systems are **real-time UI projection systems** that use compressed video as a transport mechanism.

| It IS | It is NOT |
|-------|-----------|
| Live, real-time UI delivered via H.264 | Pre-recorded content requiring smooth playback |
| Interactive - touch requires immediate feedback | Something that benefits from deep buffering |
| Latency-sensitive - users perceive lag, not drops | Something that should "catch up" via backlog |
| Variable frame rate (idle vs animation) | Fixed-rate media requiring pacing |
| Disposable snapshots of UI state | Durable media to be preserved |

**Violating these principles causes:** persistent visual corruption, ghosting, smearing, latency, and unstable user experience.

---

## 3. The Fundamental Mistake

**Treating projection video like a streaming video file.**

When you buffer projection video:
- Old frames are **worthless** - the UI has already changed
- Playing through a backlog creates **artificial latency**
- The user sees stale UI while their touches seem unresponsive
- The system falls further behind trying to "catch up"

> **CarPlay UIs tolerate visual skips far better than input lag.**

---

## 4. Video Philosophy

### 4.1 Video is UI State

- Each video frame represents a snapshot of UI state at a moment in time
- Frames are **disposable**
- Frames do not represent durable media

### 4.2 Latency Is the Primary Metric

- Visual immediacy is more important than smoothness
- Frame drops are preferred over delay
- FPS stability is not a goal
- Frame pacing is not enforced

### 4.3 Decoder Safety Over Frame Preservation

- Stateful decoders accumulate errors
- Late frames poison reference history
- Corruption must trigger immediate reset
- IDR frames are the only safe entry points

**Decode only what is current. Drop everything else.**

### 4.4 Timing Is Advisory

- PTS is not authoritative for live projection
- Synthetic monotonic timestamps are acceptable
- Frames must never be delayed to meet a schedule
- Decode and render occur ASAP

---

## 5. Audio Philosophy

### 5.1 Audio Is a Continuous Signal

- Audio is not frame-based
- Audio must be buffered to avoid gaps
- Latency is acceptable; discontinuity is not

### 5.2 Audio Is the Clock Master

- The audio device clock defines time
- Video must not block or wait for audio
- Sync is loose by design

### 5.3 Audio Error Handling

- Underruns are filled (silence or repeat)
- Audio may drift slightly
- Audio must never stall waiting for data

---

## 6. Sync Philosophy

> **Audio flows. Video snaps.**

- Audio tolerates latency
- Video tolerates loss
- **Never reverse this relationship**

---

## 7. Recovery Philosophy

- Decoder corruption must be assumed, not feared
- Reset is correct behavior
- Healing is not expected
- Recovery happens at IDR boundaries

> "If corruption survives one IDR, your decoder state is poisoned. Reset immediately."

---

## 8. Design Principles

### 8.1 Shallow, Time-Bounded Queue

Think "elastic jitter buffer," not "video buffer."

| Wrong Approach | Correct Approach |
|----------------|------------------|
| Queue depth in frames/packets | Queue depth in milliseconds |
| `MAX_BUFFER_PACKETS = 120` | `MAX_BUFFER_TIME_MS = 150` |
| Buffer holds 2+ seconds | Buffer holds ~100-200ms jitter |

### 8.2 Drop Late Frames, Don't Block

```
Frame arrives → Is it late? → Yes → Discard (unless IDR)
                            → No  → Decode immediately
```

Never:
- Wait to "fill" the buffer
- Block on reference frame gaps
- Decode based purely on PTS order

### 8.3 Always Prefer Newest Frame

When overwhelmed:
- Skip to newest IDR, not oldest queued frame
- Discard stale P-frames without decoding
- Reset decoder on hard discontinuities

### 8.4 Bounded, Non-Growing Buffer

Some buffering is necessary to:
- Absorb USB jitter (~10-20ms variance)
- Handle bursty NAL delivery
- Smooth variable encode cadence
- Prevent decoder underruns during brief stalls

But the buffer must be:
- **Bounded** - fixed maximum size
- **Non-growing** - discard when full, don't expand
- **Latency-first** - measured in time, not frames

### 8.5 Decode ASAP

- Process frames immediately upon arrival
- Don't wait for "optimal" buffer state
- Allow frame drops rather than accumulating lag

### 8.6 PTS for Staleness, Not Ordering

- Prefer monotonic PTS (even if synthetic)
- Don't wait for "missing" frames based on PTS gaps
- Use PTS for staleness detection, not ordering

---

## 9. Never Do Checklist

**This is a hard prohibition list. If any item appears in live projection code, it is a bug by definition.**

### 9.1 Never Treat Projection Video as Media

- ❌ Never target FPS (30/60)
- ❌ Never pace video
- ❌ Never smooth motion
- ❌ Never "play back" frames
- ❌ Never preserve all frames
- ❌ Never reorder for fairness

### 9.2 Never Allow Video to Wait

- ❌ Never delay decode for timing
- ❌ Never sleep to align frames
- ❌ Never wait for render time
- ❌ Never block ingress
- ❌ Never backpressure USB input
- ❌ Never wait for audio

### 9.3 Never Accumulate Frames

- ❌ Never use deep queues
- ❌ Never allow unbounded buffers
- ❌ Never let queues grow under load
- ❌ Never try to "catch up"

**If the queue grows, frames must be dropped.**

### 9.4 Never Decode Late Frames

- ❌ Late frames are invalid
- ❌ Late frames poison decoder state
- ❌ Drop immediately if late
- ❌ Do not decode "just in case"

### 9.5 Never Trust the Decoder to Heal

- ❌ Never assume corruption will fix itself
- ❌ Never ignore smearing or ghosting
- ❌ Never keep decoding after visual doubt
- ❌ Never skip reset when corruption appears

### 9.6 Never Tie Audio and Video Clocks

- ❌ Never block video for audio sync
- ❌ Never pause audio to wait for video
- ❌ Never use video timestamps for audio pacing
- ❌ Never share queues between audio/video

### 9.7 Never Share Playback Logic with Live Projection

- ❌ Never reuse player queues
- ❌ Never reuse player clocks
- ❌ Never reuse player timing
- ❌ Never reuse player sync rules
- ❌ Never reuse player drop rules

**Playback and live projection are different systems.**

### 9.8 Never Optimize for Completeness

- ❌ Never preserve frames
- ❌ Never preserve timing
- ❌ Never preserve order
- ❌ Never preserve accuracy

**Preserve immediacy only.**

---

## 10. Why This Architecture Exists

### The Adapter is a Tunnel

Carlinkit and similar adapters use a modified AirPlay-style receiver stack to impersonate a CarPlay head unit and tunnel Apple's mirroring stream over USB.

```
Open AirPlay receiver concept
    ↓
Embedded Linux device
    ↓
Custom USB gadget interface
    ↓
CarPlay-capable handshake profile
    ↓
Forward raw H.264 + PCM over USB
```

**They do NOT:**
- Decode video
- Render UI
- Understand CarPlay UI semantics

**They ARE:**
- A protocol bridge and transport tunnel
- Agnostic to the receiver platform

### What This Means

Because this is **mirroring, not playback**, Apple's internal rules apply:

- Drop over delay
- Reset over heal
- Now over later
- Audio continuous, video disposable

The adapter does exactly what Apple expected. **All correctness decisions are left to the consumer.**

> The fundamental error was treating a **mirroring transport** like **media**.

---

## 11. Success Criteria

A projection system is successful when:

| Criterion | Target |
|-----------|--------|
| UI feels immediate | ✓ |
| Touch response is instant | ✓ |
| Audio never glitches | ✓ |
| Video may skip but never smear | ✓ |
| Corruption is short-lived | ✓ |
| Latency never grows over time | ✓ |

---

## 12. Quick Reference

### Decode Rules
- Feed complete NAL units
- Start only on IDR
- Reset decoder on corruption
- Allow frame drops
- Never block decode on timing

### PTS Rules
- Use monotonic synthetic PTS
- Or ignore PTS and render ASAP
- Never wait for a "late" frame
- Prefer newest frame

### Quality Diagnosis

| Symptom | Cause | Fix |
|---------|-------|-----|
| Visual corruption (blocky, green, artifacts) | Bitstream/reference problem | Request IDR, reset decoder |
| Jitter (stuttering, uneven playback) | Timing problem | Adjust jitter buffer, PTS handling |

**PTS only influences jitter, not corruption.**

---

## 13. Final Statement

> **Projection video is disposable UI state.**
> **Projection audio is continuous time.**
> **Drop video. Buffer audio. Reset often. Never wait.**

---

> **If a frame is late, it is wrong.**
> **If it is wrong, it must die.**

---

> **Projection systems fail silently and degrade gracefully.**
> **If you see smearing, ghosting, or decay — you are already violating this document.**

**Reset. Drop. Move forward.**

---

## 14. GM CINEMO vs carlink_native

### GM Native Projection (Correct)

```
iPhone → AirPlay → OnFrame() → H264DeliverAnnexB() → Display
                      │
                      └── Validate, forward IMMEDIATELY
                          No buffer between receive and decode
```

- **Frame path:** Direct function call chain
- **Late detection:** Frame PTS vs presentation clock (real-time)
- **When behind:** Reference-only mode (decode IDR/P, skip display)
- **Invalid PTS:** Discard immediately

### carlink_native Rev [55] (Incorrect - Historical)

```
Adapter → USB Read → Ring Buffer → Callback → MediaCodec → Display
                         │
                         └── 4MB buffer, 120 packet limit
                             Sequential processing of backlog
```

- **Frame path:** Buffered with callback dependency
- **Late detection:** Counter-based (received - decoded)
- **When behind:** Quality Control drops from buffer (wrong frames)
- **Invalid PTS:** Queue anyway, use synthetic fallback

### carlink_native Rev [56+] (Corrected)

| Before (Rev [55]) | After (Rev [56+]) |
|-------------------|-------------------|
| 4MB ring buffer | 192KB jitter buffer |
| 120 packet limit (~2s) | 12 packets (~200ms) |
| Drop oldest when full | Skip to newest IDR |
| Counter-based lag | Time-based staleness |
| No IDR gate | IDR gate + stall timeout |

---

## 15. Correct Architecture Model

```java
// Time-bounded, not frame-bounded
private static final int MAX_BUFFER_TIME_MS = 150;  // Jitter tolerance
private static final int MAX_BUFFER_PACKETS = 10;   // Hard cap for safety

public void processFrame(byte[] data, int sourcePtsMs) {
    long now = System.currentTimeMillis();
    long frameAgeMs = now - sourcePtsMs;

    // DISCARD if frame is already stale (before buffering)
    if (frameAgeMs > MAX_BUFFER_TIME_MS) {
        int nalType = detectNalType(data);
        if (nalType != 5) {  // Keep IDR for reference recovery
            return;  // Frame is late - don't even buffer it
        }
    }

    // Minimal buffer for jitter absorption
    if (jitterBuffer.size() >= MAX_BUFFER_PACKETS) {
        // Overwhelmed - skip to newest IDR
        skipToNewestIdr();
    }

    // Feed decoder immediately (don't wait)
    feedDecoder(data, sourcePtsMs);
}
```

---

## 16. USB Capture Validation (Jan 2026)

**Comprehensive analysis of 215,191 frames across 10 sessions (133.7 minutes).**

See `17_USB_CAPTURE_STREAM_ANALYSIS.md` for complete quantitative data.

### Aggregate Statistics
| Metric | Value | Source |
|--------|-------|--------|
| Sessions analyzed | 10 | JAN13, JAN14, JAN19, JAN20, JAN21, JAN28 |
| Total frames | 215,191 | All VIDEO_DATA packets |
| Total duration | 8,020s (133.7 min) | Measured |
| Sessions starting with SPS+PPS+IDR | **100%** | 10/10 verified |

### IDR Periodicity (538 intervals)
| Metric | Value |
|--------|-------|
| Median interval | 2000ms |
| Range | 83ms - 2117ms |
| 2.0-2.5s (standard) | 66% |
| < 500ms (keyframe requests) | 7% |

### Frame Timing & Jitter
| Metric | Value |
|--------|-------|
| 16-17ms intervals (60fps) | 56% |
| 50ms intervals (20fps) | 31% |
| Jitter std dev | 25.6ms |
| Frames within ±40ms | 85% |

### Stream Structure Confirmed
```
[SPS+PPS+IDR] → [P] → [P] → ... → [SPS+PPS+IDR] → [P] → ...
     │                                  │
     └── Session start (100%)           └── ~2s typical (on request: 83ms min)

NAL bundling: SPS(22B) + PPS(4B) + IDR(avg 49KB) - NEVER standalone
```

---

## 17. Implementation Status

### Rev [56] (COMPLETED)
- [x] Add frame age check at write time (discard stale non-IDR)
- [x] Reduce buffer limits from 120 to 12 packets (~200ms)
- [x] Replace counter-based lag with time-based staleness
- [x] Implement "skip to newest IDR" on buffer overflow
- [x] Reduce ring buffer size from 2-4MB to 192KB
- [x] Remove PTS queue ordering dependency (monotonic synthetic only)
- [x] Add decoder discipline: IDR gate + stall timeout with reset

### Rev [57] (COMPLETED)
- [x] Fix staleness check time base bug (was comparing incompatible bases)
- [x] Reduce stall timeout 500ms → 200ms
- [x] Add corruption detection (IDR tracking)

### Rev [58] (TODO)
- [ ] Implement proper staleness check with correct time base
- [ ] 40ms threshold based on USB capture jitter analysis
- [ ] Protect IDR/SPS/PPS from staleness discard

---

## References

- GM CINEMO framework documentation (`/documents/reference/gminfo/video/`)
- Document 12: GM CINEMO vs carlink_native Analysis
- Document 14: Projection Model Implementation (Rev [56])
- Document 15: Rev [57] Decoder Poisoning Analysis
- USB captures: `/Volumes/KING/carlink_native/recording/`
- Pi-carplay captures: `/Users/zeno/.pi-carplay/usb-capture/`

---

## Document History

| Date | Changes |
|------|---------|
| 2026-01-29 | Initial creation |
| 2026-01-30 | Integrated rules.txt: Added Never Do checklist, Audio philosophy, Sync philosophy, Adapter architecture context, Success criteria, Final statements |
