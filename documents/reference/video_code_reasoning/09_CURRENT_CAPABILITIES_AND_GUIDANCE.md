# Current Capabilities and Session Guidance

## Document Created: 2026-01-29
## Purpose: Define what resources are available and establish expectations for Claude sessions
## Authority: This document reflects direct user guidance and must be followed

---

## Available Access and Documentation

### Adapter: CPC200-CCPA

**Access Level:** Direct SSH to Linux platform

**Available Information:**
- Firmware source/binary analysis
- Protocol implementation details
- Video encoding behavior (passthrough, no transcoding)
- USB bulk transfer implementation
- Configuration options and their effects

**Documentation Location:** `/Users/zeno/Downloads/carlink_native/documents/reference/`
- Firmware protocol tables
- Initialization sequences
- Video frame structure (36-byte header: 16B USB + 20B video)
- Configuration parameters

### Host Platform: GM Info 3.7 (AAOS)

**Access Level:** ADB access + update package analysis

**Available Information:**
- Full partition structure
- System services (CINEMO, MediaCodec, etc.)
- Intel HD Graphics 505 / Atom x7 behavior
- Native CarPlay stack coexistence
- Audio/Video focus management

**Hardware Specs:**
- Intel Atom x7-A3960 (Apollo Lake/Broxton)
- Intel HD Graphics 505
- 2400x960 @ 60fps display
- 6GB RAM

### Code History

**Available Archives:** 42 versions of carlink_native ([5] through [55])
**GitHub Repos:**
- abuharsky/carplay (origin)
- lvalen91/Carlink (Flutter)
- lvalen91/carlink_native (current)
- MotoInsight/carlink_native (fork)

**Logcat Captures:** Available on /Volumes/POTATO/

---

## Current State Assessment

### Audio: STABLE
- Not perfect but consistent
- Problematic early on, now resolved
- No active investigation needed

### Video: UNSTABLE
- Active problem requiring investigation
- Symptoms persist despite numerous changes
- Root cause not yet definitively identified

---

## Baseline Testing Available

### abuharsky/carplay APK
- Original foundation code
- Can be installed and tested on GM Info 3.7
- Visual observation of performance possible
- Logcat capture of AAOS state during operation
- No internal logging but external observation possible

**Purpose:** Establish whether the fundamental approach works on this platform before investigating app-specific issues.

---

## Expectations for Claude Sessions

### DO:
1. **Reference the documentation** - Read before suggesting changes
2. **Understand the full pipeline** - Adapter → USB → Host OS → App
3. **Consider platform specifics** - Intel VPU, CINEMO service, GM AAOS
4. **Review code history** - Understand what was tried and why
5. **Use available evidence** - Logcat analysis, archive comparisons
6. **Ask for clarification** - When information is unclear or missing

### DO NOT:
1. **Hallucinate** - Do not invent information not in the documentation
2. **Guess at fixes** - Do not propose changes without understanding context
3. **Ignore history** - Previous sessions have documented their work
4. **Assume standard behavior** - This is a specialized automotive platform
5. **Focus on single issues** - The problem may be systemic, not singular

---

## Information Sources (Priority Order)

1. **Reference Documents** - `/Users/zeno/Downloads/carlink_native/documents/reference/`
2. **Video Reasoning Documents** - `/Users/zeno/Downloads/carlink_native/documents/video_code_reasoning/`
3. **Current Source Code** - `/Users/zeno/Downloads/carlink_native/`
4. **Archive Comparisons** - `/Users/zeno/Downloads/project_archieve/`
5. **Logcat Evidence** - `/Volumes/POTATO/logcat/`
6. **GitHub History** - Commit messages and diffs

---

## Why This Matters

Previous Claude sessions have made changes based on:
- Incomplete understanding
- Standard Android assumptions that don't apply
- Treating symptoms instead of causes
- Not referencing available documentation

The result: Code complexity increased from ~200 lines to ~1900 lines over 2 years, with video still unstable.

With the current level of access to:
- Adapter firmware internals
- Host platform internals
- Complete code history
- Detailed documentation

There is no reason for continued guesswork. The information exists. Use it.

---

## Testing Protocol

### Before Code Changes:
1. Review relevant documentation
2. Understand current implementation
3. Identify specific hypothesis
4. Plan how to verify

### After Code Changes:
1. Test on actual GM Info 3.7 hardware
2. Capture logcat during test
3. Document results
4. Update revisions.txt with accurate description

### Baseline Comparison:
- abuharsky/carplay APK can establish platform baseline
- If original works, problem is in subsequent changes
- If original fails, problem may be platform-specific

---

## Document Maintenance

This document should be updated when:
- New access/documentation becomes available
- Testing reveals new information
- Previous assumptions are proven wrong

Future Claude sessions should read this document first.

---

## Summary

**Available:** Unprecedented access to adapter firmware, host platform internals, complete code history, and detailed documentation.

**Expected:** Accurate, informed analysis and recommendations based on available evidence.

**Not Acceptable:** Hallucination, guesswork, or ignoring documented information.
