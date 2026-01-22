# CPC200-CCPA Firmware Internals

**Purpose:** Technical reference for firmware architecture and internal processing
**Consolidated from:** carlink_native firmware analysis, binary reverse engineering
**Last Updated:** 2026-01-22

---

## Architecture Overview

The CPC200-CCPA operates as an intelligent protocol bridge with severe hardware constraints:

| Resource | Limit | Impact |
|----------|-------|--------|
| RAM | 128MB | Limits processing to basic format conversion |
| Storage | 16MB | Compressed rootfs (~15MB) |
| CPU | Single-core ARM32 | No complex DSP operations |

**Design Philosophy:** "Smart Interface, Dumb Processing" - the adapter handles protocol translation and format conversion, delegating sophisticated processing (WebRTC, noise cancellation) to the host application.

---

## DMSDP Framework

The Digital Media Streaming DisplayPort (DMSDP) framework is the core protocol implementation.

### Core Libraries

| Library | Size | Purpose |
|---------|------|---------|
| libdmsdp.so | 184KB | Core DMSDP protocol stack |
| libdmsdpcrypto.so | 80KB | Crypto (X25519, AES-GCM, ChaCha20) |
| libdmsdpaudiohandler.so | 48KB | Audio routing and dispatch |
| libdmsdpdvaudio.so | 48KB | Digital audio streaming |
| libdmsdpdvdevice.so | - | Device protocol constants |
| libdmsdpplatform.so | 242KB | FILLP, crypto, socket management |

### Protocol Initialization (libdmsdp.so)

```cpp
// Protocol lifecycle
DMSDPInitial();                          // Initialize DMSDP protocol stack
DMSDPServiceStart();                     // Start DMSDP services
DMSDPServiceStop();                      // Stop DMSDP services

// Data transmission
DMSDPConnectSendData();                  // Send structured data
DMSDPConnectSendBinaryData();            // Send raw binary data
DMSDPNetworkSessionSendCrypto();         // Send encrypted data (ChaCha20-Poly1305)

// Session management
DMSDPDataSessionNewSession();            // Create new data session
DMSDPDataSessionSendCtrlMsg();           // Send control messages

// RTP streaming
DMSDPCreateRtpReceiver();                // Create RTP receiver (audio/video)
DMSDPCreateRtpSender();                  // Create RTP sender
DMSDPRtpSendPCMPackMaxUnpacket();        // Process PCM audio packets

// Channel management
DMSDPChannelProtocolCreate();            // Create protocol channel
DMSDPChannelGetDeviceType();             // Query device type
DMSDPChannelGetDeviceState();            // Query device state
DMSDPChannelGetBusinessID();             // Get business identifier
DMSDPChannelMakeNotifyMsg();             // Create notification message
DMSDPChannelHandleMsg();                 // Handle incoming message
DMSDPChannelDealGlbCommand();            // Process global commands
DMSDPNearbyChannelSendData();            // Send data to nearby channel
DMSDPNearbyChannelUnPackageRcvData();    // Unpack received data

// Service loading
DMSDPLoadAudioService();                 // Load audio streaming service
DMSDPLoadCameraService();                // Load video/camera service
DMSDPLoadGpsService();                   // Load GPS data service
```

---

## Audio Processing Internals

### MicAudioProcessor Class

```cpp
class MicAudioProcessor {
    void PushAudio(unsigned char* data, unsigned int size, unsigned int type);
    void PopAudio(unsigned char* data, unsigned int size, unsigned int type);
    void Reset();
};
// Mangled: _ZN17MicAudioProcessor[9PushAudio|8PopAudio|5Reset]E*
```

### AudioService Class

```cpp
class AudioService {
    void PushMicData(unsigned char* data, unsigned int size, unsigned int type);
    bool IsUsePhoneMic();
    bool IsSupportBGRecord();
    void OpenAudioRecord(const char* profile, int p1, int p2, const DMSDPProfiles*);
    void CloseAudioRecord(const char* profile, int p3);
    void requestAudioFocus(int type, int flags);
    void abandonAudioFocus();
    void GetAudioCapability(DMSDPAudioCapabilities** caps, unsigned int* count);
    void OnCallStateChangeE(CALL_STATE state);
    void getCurAudioType(int*, int*);
    void OnAudioFocusChange(int);
    void OnMediaStatusChange(MEDIA_STATE);
};
// Mangled: _ZN12AudioService[11PushMicData|15OpenAudioRecord|16CloseAudioRecord]E*
```

### AudioConvertor Class

```cpp
class AudioConvertor {
    void SetFormat(AudioPCMFormat src, AudioPCMFormat dst);
    void PushSrcAudio(unsigned char* data, unsigned int size);
    void PopDstAudio(unsigned char* data, unsigned int size);
    float GetConvertRatio();
    void SteroToMono(short* left, short* right, int samples);
    int GetConvertSrcSamples(int src_rate, int dst_rate, int samples);
};
// Mangled: _ZN14AudioConvertor[9SetFormat|12PushSrcAudio|11PopDstAudio|15GetConvertRatio|11SteroToMono|20GetConvertSrcSamples]E*
```

### Audio Type Enumeration

```cpp
enum MicrophoneAudioTypes {
    AUDIO_TYPE_VOICE_COMMAND = 1,  // Siri, Google Assistant
    AUDIO_TYPE_PHONE_CALL = 2,     // Hands-free calling
    AUDIO_TYPE_VOICE_MEMO = 3,     // Voice recording
    AUDIO_TYPE_NAVIGATION = 4,     // Navigation voice input
};
```

### Audio Processing Pipeline

```
┌─────────────────────────────────────┐
│        CarPlay/Android Auto         │
│    (iPhone/Android Phone Audio)     │
└─────────────────┬───────────────────┘
                  │ Lightning/USB-C
                  │ AAC/PCM Streams
                  ▼
┌─────────────────────────────────────┐
│         CPC200-CCPA Firmware        │
│  ┌─────────────────────────────────┐│
│  │     IAP2/NCM USB Interface      ││
│  │   VID: 0x08e4, PID: 0x01c0      ││
│  │   Functions: iap2,ncm           ││
│  └─────────────────────────────────┘│
│                  │                  │
│                  ▼                  │
│  ┌─────────────────────────────────┐│
│  │    Lightweight Audio Router     ││
│  │  • AAC Decoder (libfdk-aac)     ││
│  │  • Sample Rate Conversion       ││
│  │  • Audio Type Classification    ││
│  │  • Format Validation            ││
│  └─────────────────────────────────┘│
│                  │                  │
│                  ▼                  │
│  ┌─────────────────────────────────┐│
│  │       Hardware Codec Layer      ││
│  │    WM8960 / AC6966 Codecs       ││
│  │    TinyALSA Configuration       ││
│  └─────────────────────────────────┘│
└─────────────────┬───────────────────┘
                  │ CPC200-CCPA Protocol
                  │ 0x55AA55AA + PCM Data
                  ▼
┌─────────────────────────────────────┐
│           Host Application          │
│     (Advanced WebRTC Processing)    │
└─────────────────────────────────────┘
```

### Microphone Data Flow

```
Host App → USB NCM → boxNetworkService → MicAudioProcessor →
AudioConvertor → DMSDP RTP → CarPlay/Android Auto
```

---

## Video Processing Internals (Binary Verified Jan 2026)

**CRITICAL:** Video from CarPlay/Android Auto is **forwarded passthrough** - the adapter does NOT decode, transcode, or re-encode the H.264 stream.

### Video Data Flow (Phone → Host)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         VIDEO DATA FLOW                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│   ┌──────────────┐                                                       │
│   │   iPhone/    │  AirPlay/iAP2                                        │
│   │ Android Auto │  H.264 stream                                        │
│   └──────┬───────┘                                                       │
│          │                                                               │
│          ▼                                                               │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                    AppleCarPlay Binary                            │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  AirPlayReceiverSessionScreen_ProcessFrames                 │  │  │
│   │  │  _AirPlayReceiverSessionScreen_ProcessFrame                 │  │  │
│   │  │  ScreenStreamProcessData                                    │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   │                           │                                       │  │
│   │                           │ H.264 NAL units (unchanged)           │  │
│   │                           ▼                                       │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  CRiddleUnixSocketServer (IPC to ARMadb-driver)            │  │  │
│   │  │  "### Send screen h264 frame data failed!"                  │  │  │
│   │  │  "### Send h264 I frame data %d byte!"                      │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   └──────────────────────────────────────────────────────────────────┘  │
│          │                                                               │
│          │ Unix Socket                                                   │
│          ▼                                                               │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                    ARMadb-driver Binary                           │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  CMiddleManClient (receives video data)                     │  │  │
│   │  │  "recv CarPlay videoTimestamp:%llu" (at 0x6d139)            │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   │                           │                                       │  │
│   │                           │ Add headers only                      │  │
│   │                           ▼                                       │  │
│   │  ┌────────────────────────────────────────────────────────────┐  │  │
│   │  │  _SendDataToCar (at 0x18e2c)                                │  │  │
│   │  │  - Prepend USB header (16 bytes, magic 0x55AA55AA)          │  │  │
│   │  │  - Prepend video header (20 bytes: W, H, PTS, flags)        │  │  │
│   │  │  - "may need send ZLP" (at 0x6b823)                         │  │  │
│   │  └────────────────────────────────────────────────────────────┘  │  │
│   └──────────────────────────────────────────────────────────────────┘  │
│          │                                                               │
│          │ USB Bulk Transfer                                             │
│          ▼                                                               │
│   ┌──────────────┐                                                       │
│   │   Host App   │  H.264 + 36-byte header                              │
│   │  (Decodes)   │  Host performs MediaCodec/FFmpeg decode              │
│   └──────────────┘                                                       │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Key Video Functions (Binary Analysis)

| Binary | Function/String | Address | Purpose |
|--------|-----------------|---------|---------|
| AppleCarPlay | `AirPlayReceiverSessionScreen_ProcessFrames` | 0x7ecbf | Receives AirPlay screen |
| AppleCarPlay | `_AirPlayReceiverSessionScreen_ProcessFrame` | 0x7ecea | Processes single frame |
| AppleCarPlay | `### Send screen h264 frame data failed!` | 0x9016d | H.264 send error |
| AppleCarPlay | `### Send h264 I frame data %d byte!` | 0x90196 | I-frame send log |
| ARMadb-driver | `recv CarPlay videoTimestamp:%llu` | 0x6d139 | Timestamp logging |
| ARMadb-driver | `_SendDataToCar iSize: %d` | 0x6b823 | USB transmission |
| ARMadb-driver | USB magic `0x55AA55AA` | 0x62e18 | Header constant |

### No Video Codec in Firmware

**Verified absence of video codec libraries:**
- ❌ No FFmpeg (`libavcodec`, `libavformat`)
- ❌ No x264/x265
- ❌ No libvpx (VP8/VP9)
- ❌ No OpenH264
- ❌ No hardware video decoder imports

**Only codec found:** AAC audio (`aacDecoder_*`, `aacEncoder_*` in AppleCarPlay)

**libdmsdpdvcamera.so video functions are for REVERSE CAMERA:**
- `OmxVideoEncoder*` - encodes backup camera feed TO phone
- Not used for CarPlay video FROM phone

---

## Hardware Codec Configuration

### Supported Codecs

| Codec | I2C Address | Purpose |
|-------|-------------|---------|
| **WM8960** (Primary) | 0x1a | Full-duplex stereo, high-quality audio |
| **AC6966** (Alternative) | 0x15 | Bluetooth SCO optimized, voice calls |

### Codec Detection

```bash
# Detect WM8960
i2cdetect -y -a 0 0x1a 0x1a | grep "1a" && audioCodec=wm8960

# Detect AC6966
i2cdetect -y -a 0 0x15 0x15 | grep "15" && audioCodec=ac6966
```

### Kernel Module Loading

```bash
insmod /tmp/snd-soc-wm8960.ko
insmod /tmp/snd-soc-imx-wm8960.ko
insmod /tmp/snd-soc-bt-sco.ko
insmod /tmp/snd-soc-imx-btsco.ko
```

### TinyALSA Mixer Configuration

**WM8960 Playback:**
```bash
tinymix 0 60 60        # Master volume L/R (0-255)
tinymix 2 1 0          # Channel routing
tinymix 35 180 180     # Mic input boost
tinymix 4 7            # Output routing
tinymix 7 3            # Output routing
tinymix 48 1           # Output enable
tinymix 50 1           # Output enable
tinymix 52 1           # Output enable
```

**Microphone Recording:**
```bash
tinymix 8 255 255      # Mic boost maximum
tinymix 47 63 63       # Additional gain
```

---

## D-Bus Service Integration

### HFP Daemon Configuration (/etc/hfpd.conf)

```ini
[daemon]
acceptunknown=1          # Accept unknown Bluetooth devices
voiceautoconnect=1       # Automatically connect voice audio

[audio]
packetinterval=40        # 40ms packet intervals for low latency
```

### D-Bus Policy (/etc/dbus-1/system.d/hfpd.conf)

```xml
<policy user="root">
    <allow own="net.sf.nohands.hfpd"/>
</policy>

<allow send_destination="net.sf.nohands.hfpd.HandsFree"/>
<allow send_destination="net.sf.nohands.hfpd.SoundIo"/>
<allow send_destination="net.sf.nohands.hfpd.AudioGateway"/>
```

### HFP AT Command Exchange (NEW Jan 2026)

The HFP daemon exchanges AT commands with the phone for hands-free profile setup:

**Adapter → Phone:**
```
AT+BRSF=63           # Supported features bitmask (adapter)
AT+CIND=?            # Query indicator descriptions
AT+CMER=3,0,0,1      # Enable unsolicited result codes
AT+CLIP=1            # Enable caller ID
AT+CCWA=1            # Enable call waiting
AT+CHLD=?            # Query call hold modes
AT+CIND?             # Query current indicator values
```

**Phone → Adapter:**
```
+BRSF: 879           # Supported features bitmask (phone)
+CIND: ("call",(0,1)),("callsetup",(0-3)),("service",(0-1)),...
+CHLD: (0,1,2,3)     # Supported hold modes
+BSIR: 0/1           # In-band ring tone setting
+CIND: 0,0,0,0,0,4,0 # Current indicator values
OK                   # Command acknowledged
```

**Feature Bitmask (BRSF):**
| Bit | Feature |
|-----|---------|
| 0 | Three-way calling |
| 1 | EC/NR function |
| 2 | Voice recognition |
| 3 | In-band ring tone |
| 4 | Voice tag |
| 5 | Call reject |

### org.riddle D-Bus Interface

```cpp
// HUD Commands
HUDComand_A_HeartBeat
HUDComand_A_ResetUSB
HUDComand_A_UploadFile
HUDComand_B_BoxSoftwareVersion
HUDComand_D_BluetoothName
kRiddleHUDComand_A_Reboot
kRiddleHUDComand_CommissionSetting

// Audio Signals
kRiddleAudioSignal_MEDIA_START
kRiddleAudioSignal_MEDIA_STOP
kRiddleAudioSignal_ALERT_START
kRiddleAudioSignal_ALERT_STOP
kRiddleAudioSignal_PHONECALL_Incoming
```

---

## USB Protocol Message Dispatch (ARMadb-driver)

### Key Functions (Binary Analysis - Jan 2026)

| Function | Address | Purpose |
|----------|---------|---------|
| `FUN_00017340` | `0x17340` | Generic message sender (21 call sites) |
| `FUN_00018088` | `0x18088` | Message pre-processor (header validation) |
| `FUN_00018244` | `0x18244` | Message encryption/decryption handler |
| `FUN_00018e2c` | `0x18e2c` | Main message dispatcher (type routing) |
| `FUN_00062e1c` | `0x62e1c` | Message buffer initialization |
| `FUN_00062f34` | `0x62f34` | Message buffer populate |
| `FUN_000628a4` | `0x628a4` | Message buffer/send wrapper |

### Adapter-to-Host Message Senders

| Function | Address | Message Types | Trigger |
|----------|---------|---------------|---------|
| `fcn.00018628` | `0x18628` | 0x06, 0x09, 0x0B, 0x0D, 0xA1 | State-dependent routing |
| `fcn.000186ba` | `0x186ba` | 0x14, 0xA1 | ManufacturerInfo response |
| `fcn.00018850` | `0x18850` | 0x06, 0x0B | HiCar device list |
| `fcn.00018990` | `0x18990` | StartPhoneLink data | Phone connection |
| `fcn.0001af48` | `0x1af48` | 0x01, 0x1E, 0xF0 | Display resolution |

### Status Event Strings

| String | Address | Associated Type |
|--------|---------|-----------------|
| `OnCarPlayPhase %d` | `0x5c415` | Phase (0x03) |
| `OnAndroidPhase _val=%d` | `0x5bf52` | Phase (0x03) |
| `DeviceBluetoothConnected` | `0x5bc88` | Status event |
| `DeviceWifiConnected` | `0x5bcbd` | Status event |
| `CMD_BOX_INFO` | `0x5b44c` | BoxSettings (0x19) |
| `CMD_CAR_MANUFACTURER_INFO` | `0x5b3e4` | ManufacturerInfo (0x14) |
| `_SendDataToCar` | `0x5b823` | Debug logging |

---

## Service Architecture

### Startup Sequence

```bash
/script/init_audio_codec.sh
cp /usr/sbin/mdnsd /tmp/bin/; mdnsd
/script/start_iap2_ncm.sh
/script/start_ncm.sh
boxNetworkService &
```

### Key Processes

| Process | Purpose |
|---------|---------|
| **mdnsd** | CarPlay mDNS discovery (_carplay._tcp) |
| **boxNetworkService** | Network communication (45KB) |
| **hfpd** | Bluetooth HFP daemon (static) |
| **ARMAndroidAuto** | Android Auto handler (489KB) |
| **AppleCarPlay** | Main CarPlay receiver (557KB) |
| **ARMiPhoneIAP2** | iPhone IAP2 protocol (494KB) |
| **bluetoothDaemon** | Bluetooth management (396KB) |

### USB Gadget Configuration

```bash
echo 0 > /sys/class/android_usb/android0/enable
echo 239 > /sys/class/android_usb/android0/bDeviceClass     # Misc device
echo 2 > /sys/class/android_usb/android0/bDeviceSubClass
echo 1 > /sys/class/android_usb/android0/bDeviceProtocol
echo "Auto Box" > /sys/class/android_usb/android0/iProduct
echo 08e4 > /sys/class/android_usb/android0/idVendor        # Magic Communication VID
echo 01c0 > /sys/class/android_usb/android0/idProduct       # Auto Box PID
echo "iap2,ncm" > /sys/class/android_usb/android0/functions # IAP2 + NCM
echo 1 > /sys/class/android_usb/android0/enable
```

---

## Performance Metrics

### Audio Processing Performance

| Component | Time | Memory | CPU |
|-----------|------|--------|-----|
| USB Reception | <0.5ms | 5KB | <1% |
| MicAudioProcessor | 1-2ms | 20KB | 3-5% |
| Format Conversion | 0.5-1ms | 15KB | 2-3% |
| RTP Assembly | <0.5ms | 10KB | 1-2% |
| Protocol Transmission | 1-2ms | 8KB | 2-3% |
| **Total Audio Pipeline** | **3-6ms** | **58KB** | **9-14%** |

### Video Processing Performance (CarPlay/AA → Host)

**IMPORTANT:** The adapter does NOT decode or transcode H.264 video from CarPlay/Android Auto. Video is **forwarded passthrough** with only header prepending.

| Component | Time | Memory | CPU |
|-----------|------|--------|-----|
| AirPlay Reception (AppleCarPlay) | 1-2ms | 100KB | 2-5% |
| NAL Unit Parsing (keyframe detect) | <0.5ms | 10KB | <1% |
| Unix Socket IPC | <0.5ms | 50KB | 1-2% |
| USB Header Construction | <0.5ms | 36 bytes | <1% |
| USB Bulk Transfer | 1-2ms | 50KB | 2-3% |
| **Total Video Pipeline** | **3-5ms** | **~200KB** | **6-12%** |

**Binary Evidence (Jan 2026):**
- No H.264 decoder/encoder libraries (FFmpeg, x264, etc.) found
- Only codec imports are AAC (audio): `aacDecoder_*`, `aacEncoder_*`
- Video encoder in `libdmsdpdvcamera.so` is for **reverse camera** (TO phone), not CarPlay
- Strings: `### Send screen h264 frame data failed!` - sends raw H.264
- Log: `recv CarPlay videoTimestamp:%llu` - timestamp forwarding only

### Video Resolution/FPS Limits (Binary Verified)

**No hardcoded limits found.** The adapter forwards whatever the phone sends.

| Limit Type | Binary Evidence | Finding |
|------------|-----------------|---------|
| Resolution | `recv CarPlay size info:%dx%d` | Logged only, no validation |
| FPS | `kScreenProperty_MaxFPS :%d` | Dynamic property, not hardcoded |
| Buffer | `### H264 data buffer overrun!` | Fixed size, will overflow on large frames |
| Memory | `### Failed to allocate memory for video frame` | Dynamic allocation can fail |
| Bandwidth | `Not Enough Bandwidth`, `Bandwidth Limit Exceeded` | Runtime configured |

**Practical Limits (not programmatic):**
- USB 2.0: ~280 Mbps practical throughput
- RAM: ~128MB total, limits frame buffer size
- 4K@60: Marginal (bandwidth limit)
- 8K: Will fail (memory allocation)
- 120fps: Doubles bandwidth requirement

### Memory Footprint (~1.1MB Audio)

| Component | Size |
|-----------|------|
| DMSDP Framework | 500KB |
| AAC Decoder | 336KB |
| Buffers | 50KB |
| System | 200KB |

---

## RTP Transport Functions

### DMSDP RTP API

```cpp
void DMSDPRtpSendPCMPackFillPayload(rtp_packet_t*, unsigned char*, unsigned int);
void DMSDPPCMPostData(unsigned char*, unsigned int stream_id, unsigned int timestamp);
void DMSDPPCMProcessPacket(unsigned char*, unsigned int);
void DMSDPDataSessionRtpSenderCallback(rtp_session_t*, rtp_event_t*);
void DMSDPDataSessionRtpSenderEventsHandler(rtp_session_t*, rtp_events_t);
void DMSDPDataSessionInitRtpRecevier(...);

// Stream callbacks
void DMSDPStreamSetCallback(stream_id_t, stream_callback_t);
void DMSDPServiceProviderStreamSetCallback(provider_t*, stream_callback_t);
void DMSDPServiceSessionSetStreamCallback(session_t*, stream_callback_t);
```

---

## Authentication (libauthagent.so)

```cpp
// Trust management for paired devices (44KB library)
GetAuthagentInstance();
DestroyAuthagent();
RefreshPinAuth();
ListTrustPhones();
DelTrustPhones();
IsTrustPhones();
is_trust_peer();
list_trust_peers();
delete_local_auth_info();
```

---

## iAP2 Protocol Engines (ARMiPhoneIAP2)

```cpp
iAP2CallStateEngine        // Phone call state machine
iAP2CommunicationEngine    // Core communication
iAP2LocationEngine         // GPS/location data
iAP2MediaPlayerEngine      // Media playback control
iAP2RouteGuidanceEngine    // Navigation guidance
iAP2WiFiConfigEngine       // WiFi configuration exchange
```

---

## Capability Detection

```cpp
CheckMultiAudioBusCap();      // Multi-bus audio support
CheckMultiAudioBusVersion();  // Audio bus version
CheckMultiAudioBusPolicy();   // Audio routing policy
IsSupportBGRecord();          // "Hey Siri"/"OK Google" support
IsUsePhoneMic();              // External/app microphone check
```

---

## Audio Format Support

| Sample Rate | Use Case |
|-------------|----------|
| 8kHz | Phone calls (narrow-band), voice |
| 16kHz | Voice/Siri (wide-band), phone calls |
| 44.1kHz | Music (CD quality) |
| 48kHz | Professional audio |

**WebRTC Processing (Binary Verified at 0x2dfa2):**
- WebRTC AECM only accepts **8000 Hz or 16000 Hz** for microphone input
- Other sample rates will cause initialization failure
- Sample rate is configured dynamically based on audio context

**Conversion Capabilities:**
- Sample rate: 8↔16↔44.1↔48 kHz
- Channels: Mono ↔ Stereo
- Bit depth: 16-bit PCM primary
- Buffer: Push/Pop with conversion ratios

---

## Processing Boundaries

### What Firmware Does:
1. Protocol translation: IAP2/NCM USB ↔ CPC200-CCPA protocol
2. Basic AAC decoding: Compressed streams → PCM
3. Format conversion: Sample rate, channels, bit depth
4. Hardware configuration: Codec init, mixer settings
5. Audio routing: Stream classification/direction
6. Buffer management: Simple I/O buffering

### What Firmware DOES Do (WebRTC Processing):
- **WebRTC AGC** (Automatic Gain Control) - applied to microphone audio
- **WebRTC AECM** (Acoustic Echo Cancellation, Mobile) - 8kHz/16kHz only
- **WebRTC NS** (Noise Suppression) - configurable via VoiceQuality setting

### What Firmware Does NOT Do (Host App Responsibility):
- Automotive-specific optimizations
- Complex multi-stream mixing
- Advanced real-time DSP beyond WebRTC
- Multi-channel processing beyond basic routing

---

## Testing & Debug

### DTMF Testing

```bash
tinycap -- -c 1 -r 16000 -b 16 -t 4 > /tmp/dtmf.pcm
result=`dtmf_decode /tmp/dtmf.pcm | grep 14809414327 | wc -l`
[ $result -eq 1 ] && echo "mic test success!!" || exit 1
```

### Debug Functions

```cpp
AudioService::getCurAudioType(int*, int*);
AudioService::OnAudioFocusChange(int);
AudioService::OnMediaStatusChange(MEDIA_STATE);
```

### Debug Mode (CMD_DEBUG_TEST 0x88)

| Value | Action |
|-------|--------|
| 1 | Open `/tmp/userspace.log`, run `/script/open_log.sh` |
| 2 | Read log file, send contents to host |
| 3 | Enable persistent debug mode flag |

---

## Bluetooth/WiFi Hardware (RTL8822CS)

*Verified via adapter TTY log capture (Jan 2026)*

### Realtek RTL8822CS Module

| Property | Value |
|----------|-------|
| Device ID | `0xc822` |
| HCI Revision | `0x000c` |
| HCI Version | `0x08` |
| LMP Subversion | `0x8822` |
| IC Type | RTL8822CS (combo WiFi+BT) |

### Firmware Loading

| File | Size | Purpose |
|------|------|---------|
| `/lib/firmware/rtlbt/rtl8822cs_fw` | 60980 bytes | Bluetooth firmware |
| `/lib/firmware/rtlbt/rtl8822cs_config` | 41 bytes | Bluetooth config |

**Firmware Version:** `0x05a8cbcd`
- Patch number: 3
- Patch length: `0x8a6c` (35436 bytes)
- Start offset: `0x00006380`
- SVN version: 1940234490
- Coexistence: `BTCOEX_20210106-2020`

### Bluetooth/WiFi Coexistence

The adapter uses Realtek's `rtk_btcoex` driver for Bluetooth/WiFi coexistence management.

**Profile Bitmap Values:**

| Bitmap | Profile |
|--------|---------|
| 0x01 | Unknown profile 0 |
| 0x02 | Unknown profile 1 |
| 0x04 | Unknown profile 2 |
| 0x08 | HFP (Hands-Free Profile) |
| 0x10 | Unknown profile 4 |
| 0x20 | Unknown profile 5 |
| 0x40 | Unknown profile 6 |
| 0x80 | Unknown profile 7 |

**Coex Events (from kernel log):**

| Event | Meaning |
|-------|---------|
| `hci accept conn req` | Incoming Bluetooth connection |
| `connected, handle XXXX` | Connection established |
| `Page success` | Outgoing page completed |
| `link key notify` | Link key exchanged |
| `io capability request` | Pairing negotiation |
| `l2cap conn req, PSM 0xXXXX` | L2CAP channel request |
| `pan idle->busy` / `pan busy->idle` | PAN profile state |

**Vendor Command:** `opcode 0xfc19` - Profile info notification to firmware

### L2CAP Protocol Service Multiplexers (PSM)

| PSM | Service |
|-----|---------|
| 0x0001 | SDP (Service Discovery Protocol) |
| 0x0003 | RFCOMM (Serial port emulation) |

### HFP AT Command Exchange (Runtime Verified Jan 2026)

*Full handshake captured during Pixel 10 Android Auto connection*

| Direction | Command | Description |
|-----------|---------|-------------|
| Host→Phone | `AT+BRSF=63` | Supported features bitmask (adapter) |
| Phone→Host | `+BRSF: 879` | Supported features bitmask (phone) |
| Host→Phone | `AT+CIND=?` | Query indicator descriptions |
| Phone→Host | `+CIND: ("call",(0,1)),("callsetup",(0-3)),...` | Indicator support |
| Host→Phone | `AT+CMER=3,0,0,1` | Enable event reporting |
| Host→Phone | `AT+CLIP=1` | Enable caller ID |
| Host→Phone | `AT+CCWA=1` | Enable call waiting |
| Host→Phone | `AT+CHLD=?` | Query call hold modes |
| Phone→Host | `+CHLD: (0,1,2,3)` | Supported hold modes |
| Host→Phone | `AT+CIND?` | Query current indicators |
| Phone→Host | `+CIND: 0,0,0,0,0,4,0` | Current indicator values |
| Phone→Host | `+BSIR: 0` / `+BSIR: 1` | In-band ring setting |

**BRSF Bitmap (Adapter = 63):**
- Bit 0: EC/NR function
- Bit 1: Call waiting / three-way calling
- Bit 2: CLI presentation
- Bit 3: Voice recognition
- Bit 4: Remote volume control
- Bit 5: Enhanced call status

**BRSF Bitmap (Pixel 10 = 879):**
- Includes all adapter features plus extended codecs

---

## Bluetooth Link Key Storage

*Directory structure verified via adapter TTY log (Jan 2026)*

### Directory Structure

```
/tmp/bluetooth/
└── [LOCAL_ADDR]/              # e.g., 48:8F:4C:E0:AC:2B
    ├── linkkeys               # Paired device link keys
    ├── names                  # Cached device names
    ├── features               # Device feature flags
    ├── manufacturers          # Device manufacturer info
    ├── lastused               # Last used timestamps
    ├── classes                # Device class codes
    ├── config                 # Adapter configuration
    └── services               # Service definitions (at root level)
```

### File Formats

| File | Format | Description |
|------|--------|-------------|
| `linkkeys` | Text | Paired device keys (see below) |
| `names` | Text | Cached friendly names by MAC |
| `features` | Text | LMP feature bitmask per device |
| `manufacturers` | Text | Manufacturer ID per device |
| `lastused` | Text | Unix timestamp of last connection |
| `classes` | Text | CoD (Class of Device) per device |
| `config` | Text | Adapter configuration |

**Link Key Format:**
```
MAC_ADDRESS LINK_KEY_HEX KEY_TYPE PIN_TYPE
```
Example: `B0:D5:FB:A3:7E:AA 68851F93529776F17B9A155512568EA5 5 -1`

**Key Type Values:**
| Value | Type |
|-------|------|
| 0 | Combination key |
| 1 | Local unit key |
| 2 | Remote unit key |
| 3 | Debug combination key |
| 4 | Unauthenticated P-192 |
| 5 | Authenticated P-192 |
| 6 | Changed combination key |
| 7 | Unauthenticated P-256 |
| 8 | Authenticated P-256 |

**Config File Format:**
```
class 0x000408
onmode discoverable
mode off
```

---

## DeletedDevList JSON Format

*Verified via adapter TTY log (Jan 2026)*

The `DeletedDevList` is a JSON array tracking devices scheduled for removal from pairing:

```json
[{"id": "B0:D5:FB:A3:7E:AA", "name": ""}]
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Bluetooth MAC address |
| `name` | string | Device name (may be empty) |

**Usage:** Referenced by `RiddleBluetoothService_Interface_Control` during device removal operations. Persisted in configuration to handle removals across reboots.

---

## LED Status Daemon (colorLightDaemon)

*Runtime states verified via TTY log (Jan 2026)*

The CPC200-CCPA has two LEDs: **Red** and **Blue** (not RGB).

| Status String | LED | Meaning |
|---------------|-----|---------|
| `StartUp` | Red | Adapter initializing / waiting for host app |
| `LinkSuccess` | Blue | Phone connected and streaming |
| `WifiConnected` | Blue | WiFi connection established |
| `BtConnecting` | Blue (blink) | Bluetooth pairing in progress |
| (disconnected) | Red | No device connected / idle state |

**Observed Behavior:**
- **Blue LED**: Indicates active connection (WiFi connected, device streaming)
- **Red LED**: Indicates disconnected/idle state

Status changes logged as:
```
colorLightDaemon[colorLightDaemon]: Change status to "STATUS" or switch songs!!
```

---

## References

- Source: `carlink_native/documents/reference/Firmware/firmware_audio.md`
- Source: `carlink_native/documents/reference/Firmware/firmware_microphone.md`
- Binary analysis: Ghidra 12.0, radare2
- Firmware version: 2025.02.25.1521CAY
- TTY capture: adapter-ttylog_26JAN22_03-52-39.log
