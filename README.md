# carlink_native
Carlink Alternative without Flutter/Dart

# Work In progress

## Multiple updates expected. Assume they will each break something.

I'm focusing on my gminfo Intel AAOS radio. Both for Video and Audio performance. Many things have been carried over from the Flutter Carlink that have not been fixed. 

## Change to your package name, version, and version code for PlayStore Upload.
section: defaultConfig InFile: /app/build.gradle.kts

Remember kids: (mostly me)
```
Projection streams are live UI state, not video playback.
Do not buffer, pace, preserve, or “play” frames.
Late frames must be dropped. Corruption must trigger reset.

CarPlay / Android Auto h264 is not media.
It is a real-time projection of UI state.
Correctness is defined by latency, not completeness.
Buffers create corruption. Queues create lies.

Video is a best-effort, disposable representation of UI state.
Audio is a continuous time signal that must never stall.
Video may drop. Audio may buffer. Neither may block the other
```

Video:
- Represents live UI state
- Late == invalid
- Drop aggressively
- Reset on corruption
- Never wait

Audio:
- Represents continuous time
- Late == fill
- Buffer aggressively
- Never stall
- Never block video

---

## Known Video Issues & Future Improvements

### Video Freeze (Audio Continues)
**Symptom**: Live UI freezes while audio continues playing. Does not self-recover. User must manually use "Reset Video Decoder" button or navigate away from app.

**Root Cause Analysis (Feb 2026)**:

MediaCodec enters a "zombie state" - alive by all observable metrics, but internally dead:
- `mCodec != null` ✓
- `running == true` ✓
- `surface.isValid() == true` ✓
- BUT `onInputBufferAvailable` callbacks stop
- AND `getInputBuffer(index)` returns null
- AND `onError` callback never triggers

**Evidence from logcat analysis**:
```
H264_PIPELINE: Rx:1330 Dec:0 InAvail:4 Feed:1330/0 LastIn:65502ms run=true codec=true surface=true
```
- 1330 frames received, 0 decoded
- Feed attempts succeed (index obtained) but `getInputBuffer()` returns null
- Same buffer indexes recycle indefinitely: poll → null → offer → poll → null...
- Codec stuck for 65+ seconds without triggering error callback

**Diagnostic logging added** (temporary, to confirm root cause):
- `H264Renderer.java` now tracks silent failures: `nullBufferCount`, `zeroReadCount`, `feedExceptionCount`
- Periodic stats show `FAIL[null:X zero:Y exc:Z]` when failures occur
- Throttled per-failure logging (1st occurrence + every 100th)

**Workaround**: Manual reset via Settings → Reset Video Decoder, or navigate to home screen and back. Home button works because it triggers Activity lifecycle → `stop()` → `resume()` → full codec recreation.

**The Only Bug**: Missing automatic reset when codec stuck. Frame drops elsewhere are **correct behavior** - late frames are invalid, dropping them is right.

**Planned Fix** - see `H264Renderer.java:72-89` TODO `[SELF_HEALING]`:

Once diagnostic logging confirms `getInputBuffer() == null` is the failure path:
- Watchdog detects: `Rx > 0 && Dec == 0` for 5+ seconds
- Action: `reset()` - full codec recreation (same as home button)
- Codec recreated, keyframe requested, UI resumes

**Philosophy**: This is live UI, not media playback. Do not add complexity. Do not preserve frames. Do not retry. Do not buffer "just in case." The fix is simple: detect stuck, reset. Broken = reset. That's it.

**What Is NOT A Bug**:
- Frames dropped due to late arrival → correct
- Frames dropped when codec busy → correct
- Partial USB reads discarded → correct
- Ring buffer overflow drops → correct (buffer shouldn't exist anyway)

**Future Work** (see `H264Renderer.java` TODOs):

1. **`[LIVE_UI_OPTIMIZATION]`** `:95` - Reduce buffer from 10 frames to 1-2 (thread handoff only). Current 10 frames = 166ms of stale UI state. Drops will increase - that's correct.

2. **`[DIRECT_HANDOFF]`** `:383` - Eliminate buffer entirely. Feed codec directly or drop. GM AAOS does this.

---

## Reference: GM AAOS Native CarPlay

GM uses NO buffer, direct handoff, drops aggressively. Same philosophy, native implementation.
See `/gm_aaos/system/lib64/libNmeVideo*.so`.
