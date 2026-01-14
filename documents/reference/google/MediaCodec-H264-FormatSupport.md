# H.264/AVC Video Format Support in Android

Source: https://developer.android.com/media/platform/supported-formats

## H.264 AVC Baseline Profile (BP)

| Aspect | Details |
|--------|---------|
| **Encoder Support** | Android 3.0+ |
| **Decoder Support** | All versions (required) |
| **Container Formats** | 3GPP (.3gp), MPEG-4 (.mp4), MPEG-TS (.ts), Matroska (.mkv) |
| **Muxer Support** | 3GPP (.3gp), MPEG-4 (.mp4) |

## H.264 AVC Main Profile (MP)

| Aspect | Details |
|--------|---------|
| **Encoder Support** | Android 6.0+ |
| **Decoder Support** | All versions |
| **Notes** | Decoder required; encoder recommended |

## Encoding Recommendations

### SD (Low quality)
- Resolution: 176 x 144 px
- Frame rate: 12 fps
- Video bitrate: 56 Kbps
- Audio: AAC-LC, Mono, 24 Kbps

### SD (High quality)
- Resolution: 480 x 360 px
- Frame rate: 30 fps
- Video bitrate: 500 Kbps
- Audio: AAC-LC, Stereo, 128 Kbps

### HD 720p
- Resolution: 1280 x 720 px
- Frame rate: 30 fps
- Video bitrate: 2 Mbps
- Audio: AAC-LC, Stereo, 192 Kbps

## Streaming Requirements (HTTP/RTSP)

For H.264 content streamed over HTTP or RTSP:

1. The `moov` atom must precede any `mdat` atoms but succeed the `ftyp` atom
2. Audio and video samples at the same time offset must be no more than 500 KB apart
3. Interleave audio and video in smaller chunks to minimize drift

## Dynamic Resolution Support

All H.264 implementations MUST support:
- Dynamic video resolution switching within the same stream in real-time
- Dynamic frame rate switching within the same stream in real-time
- Up to the maximum resolution supported by the device

This applies to VP8, VP9, H.264, and H.265 codecs.

## MPEG-TS Container Notes

- Available: Android 3.0+
- Audio: AAC only
- Not seekable
