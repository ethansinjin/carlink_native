# Receiver Contract and Operations Guide

## Document Created: 2026-01-30
## Purpose: Define receiver responsibilities, operational telemetry, and recovery procedures
## Scope: Runtime behavior, diagnostics, and self-healing policies
## Authority: This document defines operational requirements for projection receivers

---

## 1. The Receiver Contract

**The receiver is responsible for correctness. The adapter is a tunnel.**

### 1.1 The Receiver MUST

| Requirement | Rationale |
|-------------|-----------|
| Treat inbound H.264 as **live UI state**, not media | Projection is not playback |
| Maintain **bounded latency** (no growth over time) | Latency growth = policy failure |
| Use **bounded buffering** (tiny, leaky) | Deep buffers create lag |
| **Drop** frames under congestion | Dropping is cheaper than decoding late |
| **Reset** decoder on corruption or uncertainty | Healing is not expected |
| Decode **only after IDR** (re-enter only on IDR after reset) | IDR is the only safe entry point |
| Keep audio **continuous** and **never blocking video** | Audio tolerates latency, video tolerates loss |
| Accept **loss and skips** as normal operation | Completeness is not a goal |

### 1.2 The Receiver MUST NOT

| Prohibition | Consequence if Violated |
|-------------|------------------------|
| Enforce FPS | Creates artificial pacing delays |
| "Catch up" through backlog | Compounds latency, poisons decoder |
| Preserve completeness | Prioritizes wrong metric |
| Synchronize by delaying video | Reverses audio/video relationship |
| Accumulate queues | Guarantees latency growth |
| Trust decoder to heal | Corruption persists and amplifies |

### 1.3 Success Condition

A receiver is operating correctly when:

- UI is responsive
- Audio is uninterrupted
- Corruption is brief and self-correcting via reset
- Latency is bounded and stable over time

---

## 2. Apple Assumptions vs Android Reality

This is where the implementation mismatch appears.

### 2.1 What Apple Assumes (OEM Hardware)

On native CarPlay hardware (VideoToolbox + certified head units):

| Behavior | Implementation |
|----------|----------------|
| Immediate decode | VideoToolbox decodes on arrival |
| Late frame dropping | Hardware drops automatically |
| IDR reset | Happens automatically on reference errors |
| Audio buffering | Handled in hardware audio path |
| Surface management | Deterministic, hardware-controlled |
| Latency bounds | Enforced by silicon, not software |

**Apple's code relies on hardware behavior to enforce policy.**

### 2.2 What Android Provides (MediaCodec)

MediaCodec does NOT enforce policy. It obeys instructions:

| Behavior | MediaCodec Reality |
|----------|-------------------|
| Frame dropping | Does NOT drop for you |
| Decoder reset | Does NOT reset for you |
| "Late" detection | Does NOT know what "late" means |
| Timestamp handling | Obeys literally, even if wrong |
| Surface reuse | Reuses aggressively (can poison) |
| Error signaling | Decays silently instead of failing |

**Apple's implicit safety becomes YOUR explicit responsibility.**

### 2.3 The Mismatch

| Apple Assumes | Your App Originally Did | Result |
|---------------|------------------------|--------|
| Decode immediately | Buffered for "smoothness" | Latency growth |
| Drop late frames | Preserved for "fairness" | Decoder poisoning |
| Never buffer deeply | 4MB ring buffer, 120 packets | 2+ seconds of lag |
| Reset on doubt | Kept decoding through corruption | Persistent artifacts |
| Video is disposable | Treated frames as valuable | Wrong optimization target |

> **This assumption mismatch was the entire bug.**

---

## 3. Adapter Architecture: Endpoints vs Bridges

### 3.1 Open-Source AirPlay Receivers (Historical)

Projects like ShairPort, openairplay, and early mirroring receivers were **endpoints**:

| Characteristic | Behavior |
|----------------|----------|
| Role | Pretend to be Apple TV |
| Termination | Decode and render locally |
| Jitter handling | Buffer and smooth |
| Recovery | Handle internally |
| Platform | General-purpose OS |

Their job ended at:
```
receive → decode → display
```

They hid complexity by buffering and smoothing.

### 3.2 Carlinkit Devices

Carlinkit devices are **bridges**, not endpoints:

| Characteristic | Behavior |
|----------------|----------|
| Role | Protocol tunnel |
| Termination | None - forward only |
| Jitter handling | None |
| Recovery | None |
| Platform | Agnostic |

Their job ends at:
```
receive → forward
```

### 3.3 What Carlinkit Intentionally Does NOT Do

| Omission | Reason |
|----------|--------|
| No buffering for quality | Adds latency |
| No frame pacing | Would break OEM head units |
| No sync correction | Would desync CarPlay |
| No decoder reset | Not its responsibility |
| No visual inspection | Cannot know receiver capabilities |
| No timing repair | Would violate safety constraints |
| No AV sync management | Receiver's job |
| No jitter compensation | Receiver's job |

**Why these omissions exist:**

- Adapter must be cheap
- Adapter must be fast
- Adapter must be platform-agnostic
- Adapter must be stateless
- Adapter must not assume rendering behavior

> The adapter does exactly what Apple expected. All correctness decisions are left to the consumer.

---

## 4. Responsibility Model

```
iPhone (CarPlay)
    │
    │ (mirroring stream: assumes compliant receiver)
    ▼
Carlinkit Adapter (tunnel)
    │
    │ (raw H.264 + PCM: no policy, no healing)
    ▼
Your App (policy engine) ◄── YOU ARE HERE
    │
    │ (enforces: drop, reset, immediacy)
    ▼
Decoder / DAC (execution)
    │
    ▼
Display / Speakers
```

| Component | Responsibility |
|-----------|---------------|
| iPhone | Encode UI state, respond to keyframe requests |
| Adapter | Tunnel protocol, forward verbatim |
| **Your App** | **Enforce all correctness policies** |
| MediaCodec | Execute decode instructions (no policy) |
| AudioTrack | Play audio samples (no policy) |

> **The adapter is not part of correctness. You are.**

---

## 5. Telemetry and Logging Rules

You don't need extensive logging — you need the **right** metrics.

### 5.1 Video Telemetry

Track continuously:

| Metric | Purpose | Warning Threshold |
|--------|---------|-------------------|
| Ingress rate (NALs/sec) | Stream health | Sudden drops |
| Queue depth (frames waiting) | Congestion indicator | > 5 sustained |
| Frame age (ms in app before decode) | Latency indicator | > 30-50ms sustained |
| IDR interval (ms between IDRs) | Recovery opportunity | > 3000ms |
| Drop count + reason | Policy activity | Climbing while queue grows |
| Decoder resets (count + reason) | Stability indicator | Frequency spikes |
| Decode time (avg, p95) | Decoder health | Sudden p95 jumps |

### 5.2 Audio Telemetry

Track continuously:

| Metric | Purpose | Warning Threshold |
|--------|---------|-------------------|
| Buffer fill level (ms queued) | Underrun risk | < 50ms |
| Underrun count | Continuity failures | Any occurrence |
| Write latency | Pipeline health | Spikes |
| Drift (input vs playback rate) | Clock alignment | Growing divergence |

### 5.3 Red Flag Assertions

These are not just metrics — they are **policy alarms**:

| Condition | Indicates |
|-----------|-----------|
| Queue depth > threshold sustained | Dropping too late |
| Frame age > 40ms sustained | Latency policy failing |
| Drop rate climbs while queue grows | Not dropping aggressively enough |
| Corruption persists past IDR | Decoder poisoned — reset now |
| Audio underruns | Buffer too small or blocked |
| Reset frequency spikes | Upstream discontinuity or feeding errors |

---

## 6. Early Decoder Poisoning Detection

**Poisoning begins BEFORE visible artifacts.** Detect early.

### 6.1 Early Warning Indicators

| Indicator | What It Means |
|-----------|---------------|
| Decode time p95 spikes suddenly | Decoder struggling with corrupted references |
| Output cadence becomes bursty | Decoder internal state unstable |
| Queue growth after stable period | Something changed upstream or in policy |
| Corruption only during motion/animation | Motion vectors referencing contaminated data |
| Artifacts "stick" to moving UI elements | Classic reference contamination signature |
| Quality degrades progressively | Not random glitches — systematic poisoning |

### 6.2 The One IDR Rule

When corruption appears:

| Observation | Diagnosis | Action |
|-------------|-----------|--------|
| Clears at next IDR | Transient loss/drop | Continue normal operation |
| Does NOT clear at next IDR | **Decoder state poisoned** | Reset immediately |

> **If corruption survives one IDR, reset. Don't wait.**

### 6.3 Common Poisoning Causes

| Cause | Mechanism |
|-------|-----------|
| Decoding late frames | Stale data becomes reference |
| Insufficient dropping | Late frames slip through |
| Incomplete access units | Partial NAL corrupts decode |
| Missing SPS/PPS at re-entry | Decoder uses stale parameters |
| Not treating IDR as reset boundary | Old references persist |

---

## 7. Self-Healing Projection Loop

Operational policy for runtime behavior.

### 7.1 Normal Operation

```
Ingest → Minimal staging → Decode ASAP → Render ASAP
                │
                └── Audio: buffered and paced continuously
                └── Video: drops are normal and expected
```

**Goal:** Steady-state low latency with occasional frame drops.

### 7.2 Congestion Response

When queue depth grows:

| Action | Priority |
|--------|----------|
| Drop old video frames immediately | Highest |
| Keep only newest frames | Required |
| Never block ingress | Required |
| Never slow ingest to "save" frames | Required |

**Goal:** Latency remains bounded. Never "catch up."

### 7.3 Corruption Response

When visual corruption appears:

| Step | Action |
|------|--------|
| 1 | Assume decoder state is invalid |
| 2 | Reset immediately (flush or recreate) |
| 3 | Gate on next IDR before resuming P-frame decode |
| 4 | Log corruption incident with context |

**Corruption incident log should include:**
- Timestamp
- Last IDR age (ms since last IDR)
- Queue depth at time of detection
- Frame age at time of detection
- Recent drop counts
- Reset reason

### 7.4 Persistent Corruption Response

When corruption keeps happening despite resets:

| Escalation Level | Action |
|------------------|--------|
| 1 | Increase drop aggressiveness (prefer newest harder) |
| 2 | Tighten "late frame" threshold (40ms → 30ms → 20ms) |
| 3 | Increase IDR frequency if configurable (keyframe requests) |
| 4 | Treat SPS/PPS changes as full reinit triggers |
| 5 | Validate access-unit boundaries (common hidden bug) |

> **Philosophy: Fail fast, recover faster.**

---

## 8. Why Pi-CarPlay Works

Comparative analysis explaining the difference.

### 8.1 Pi-CarPlay Characteristics

| Aspect | Implementation |
|--------|----------------|
| Decoder | ffmpeg software decode |
| Acceleration | VAAPI (when available) |
| Drop behavior | Implicit in software paths |
| Reset behavior | More frequent by design |
| Timing rules | Looser, less strict |

### 8.2 Why It Accidentally Works

Pi-CarPlay accidentally aligns with Apple's assumptions:

| Apple Expects | Pi-CarPlay Does |
|---------------|-----------------|
| Drop late frames | Software path implicitly drops under load |
| Reset on errors | ffmpeg resets more liberally |
| Don't buffer deeply | Limited memory forces shallow buffers |
| Immediate decode | No complex queuing logic |

### 8.3 Android Difference

Android MediaCodec requires **explicit** policy enforcement.

| Pi-CarPlay | Android App |
|------------|-------------|
| Implicit dropping | Must implement drop logic |
| Implicit reset | Must implement reset logic |
| Loose timing | Must ignore PTS or handle correctly |
| Simple pipeline | Callback complexity adds failure modes |

> Pi-CarPlay works by accident. Android requires intention.

---

## 9. Diagnostic Quick Reference

### 9.1 Symptom → Cause → Fix

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| Latency grows over time | Queue accumulation | Drop more aggressively |
| Ghosting/tailing on motion | Reference contamination | Reset decoder |
| Corruption persists through IDR | Decoder poisoned | Full codec recreation |
| Audio glitches | Buffer underrun | Increase audio buffer |
| Video freezes, audio continues | Decoder stalled | Reset + keyframe request |
| Periodic corruption | Late frames slipping through | Tighten staleness threshold |
| Corruption after background/foreground | State not properly reset | Full reset on resume |

### 9.2 Health Check Questions

Ask these during operation:

1. Is queue depth stable or growing?
2. Is frame age bounded or increasing?
3. Are drops happening at ingress or too late?
4. Is corruption clearing at IDR boundaries?
5. Is audio continuous?
6. Is reset frequency stable or spiking?

**If any answer is wrong, policy is failing.**

---

## 10. Summary

> **Carlinkit only opens the door.**
> **The receiver decides whether the room fills with air or poison.**

This is literal in stateful decoders.

### Key Principles

1. The adapter is neutral — correctness is your responsibility
2. Apple assumes hardware enforces policy — Android requires you to enforce it
3. Monitor the right metrics — queue depth, frame age, corruption persistence
4. Detect poisoning early — before visible artifacts
5. Reset aggressively — one reset is cheaper than ten corrupted frames
6. Fail fast, recover faster — the self-healing loop

---

## References

- Document 13: Projection Design Philosophy
- Document 15: Rev [57] Decoder Poisoning Analysis
- Document 17: USB Capture Stream Analysis
- GM CINEMO framework documentation (`/documents/reference/gminfo/video/`)

---

## Document History

| Date | Changes |
|------|---------|
| 2026-01-30 | Initial creation from additions.txt integration |
