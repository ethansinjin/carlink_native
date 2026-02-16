# CarPlay Navigation Metadata → AAOS Instrument Cluster

> **Status**: Reference documentation. The carlink_native project reverted from `CarAppActivity` to `MainActivity` due to Car App Library limitations. This document preserves the complete pipeline — from adapter firmware configuration through AAOS cluster rendering — for anyone implementing cluster navigation with a Carlinkit CPC200-CCPA adapter.

---

## Table of Contents

1. [End-to-End Pipeline](#1-end-to-end-pipeline)
2. [Step 1: Firmware DashboardInfo Configuration](#2-step-1-firmware-dashboardinfo-configuration)
3. [Step 2: iAP2 Route Guidance on the Wire](#3-step-2-iap2-route-guidance-on-the-wire)
4. [Step 3: USB Protocol — NaviJSON Message Format](#4-step-3-usb-protocol--navijson-message-format)
5. [Step 4: Parsing NaviJSON in the App](#5-step-4-parsing-navijson-in-the-app)
6. [Step 5: NavigationStateManager — Merging Incremental Updates](#6-step-5-navigationstatemanager--merging-incremental-updates)
7. [Step 6: AAOS Car App Library Integration](#7-step-6-aaos-car-app-library-integration)
8. [Step 7: Custom Maneuver Icons for Cluster](#8-step-7-custom-maneuver-icons-for-cluster)
9. [Step 8: Distance Conversion (Meters → Imperial/Metric)](#9-step-8-distance-conversion-meters--imperialmetric)
10. [GM AAOS: Templates Host and Cluster Architecture](#10-gm-aaos-templates-host-and-cluster-architecture)
11. [Gotchas and Lessons Learned](#11-gotchas-and-lessons-learned)
12. [File Index](#12-file-index)

---

## 1. End-to-End Pipeline

```
iPhone (Apple Maps / Google Maps / Waze)
    │
    │ iAP2 Route Guidance Display (RGD) protocol
    │ Messages: 0x5200 StartRouteGuidance, 0x5201 RouteGuidanceUpdate,
    │           0x5202 RouteGuidanceManeuverUpdate
    ▼
Carlinkit CPC200-CCPA Adapter (iMX6UL, Linux 3.0.15)
    │
    │ Firmware: ARMiPhoneIAP2 → iAP2RouteGuidanceEngine
    │ Converts iAP2 binary → JSON via _SendNaviJSON()
    │ Sends: USB bulk transfer, MessageType 0x2A (MEDIA_DATA), subtype 200
    ▼
Host App: MessageParser.parseMediaData()
    │ Extracts UTF-8 JSON from MEDIA_DATA payload
    ▼
Host App: CarlinkManager.processMediaMetadata()
    │ Routes MediaType.NAVI_JSON → NavigationStateManager.onNaviJson()
    ▼
Host App: NavigationStateManager (StateFlow<NavigationState>)
    │ Incrementally merges partial JSON fields into complete state
    │
    ├─────────────────────────────────────────────┐
    ▼                                             ▼
CarlinkProjectionScreen                     CarlinkClusterSession
(Main display session)                      (Cluster display session)
    │                                             │
    │ NavigationManager.updateTrip(trip)           │ screen.updateState(state)
    ▼                                             ▼
Templates Host                              CarlinkClusterScreen
(com.google.android.apps.                       │
 automotive.templates.host)                     │ NavigationTemplate
    │                                             │   + RoutingInfo (maneuver + distance + road)
    │ Forwards Trip to cluster                    │   + Explicit CarIcon (vector drawable)
    ▼                                             ▼
Instrument Cluster Display              Templates Host renders on cluster
```

---

## 2. Step 1: Firmware DashboardInfo Configuration

The adapter will **not** send navigation data unless explicitly configured. The `DashboardInfo` field in the BoxSettings JSON controls which iAP2 data engines the adapter advertises to the phone during identification.

### DashboardInfo Bitmask

```
DashboardInfo = 3-bit bitmask (0-7)
├─ Bit 0 (value 1): iAP2MediaPlayerEngine     → NowPlaying (track, artist, album)
├─ Bit 1 (value 2): iAP2LocationEngine        → GPS/Location (requires GNSSCapability ≥ 1)
└─ Bit 2 (value 4): iAP2RouteGuidanceEngine   → Navigation turn-by-turn (NaviJSON)
```

**Common values:**
- `1` = Media only (NowPlaying metadata)
- `5` = Media + Navigation (bits 0 + 2) — **this is what carlink_native uses**
- `7` = All three (Media + Location + Navigation)

### How the App Sends DashboardInfo

DashboardInfo is sent in the `BoxSettings` JSON message (MessageType `0x19`) during adapter initialization:

```kotlin
// MessageSerializer.kt — serializeBoxSettings()
fun serializeBoxSettings(config: AdapterConfig, syncTime: Long? = null): ByteArray {
    val json = JSONObject().apply {
        put("phoneWorkMode", config.workMode.ordinal)
        put("audioType", config.sampleRate.ordinal)
        put("wifiChannel", 161)
        put("wifiName", config.boxName)
        put("boxName", config.boxName)
        put("DashboardInfo", 5)  // Bits: media(1) + navi(4) → enables NaviJSON
        put("autoConn", true)
        put("autoPlay", false)
    }
    val payload = json.toString().toByteArray(StandardCharsets.US_ASCII)
    return serializeWithPayload(MessageType.BOX_SETTINGS, payload)
}
```

### What Happens in the Firmware

Verified from firmware binary analysis (ARMiPhoneIAP2 at address 0x15f50-0x15f98):

```asm
; Firmware reads DashboardInfo value and tests each bit
tst r7, #1      ; Test bit 0 → MediaPlayerEngine
bne 0x282b8     ; If set → initialize iAP2MediaPlayerEngine

tst r7, #2      ; Test bit 1 → LocationEngine
bne 0x2aa6c     ; If set → initialize iAP2LocationEngine (needs GNSSCapability)

tst r7, #4      ; Test bit 2 → RouteGuidanceEngine
bne 0x2ebc4     ; If set → initialize iAP2RouteGuidanceEngine
```

When bit 2 is set:
1. Adapter advertises `iAP2RouteGuidanceDisplay` capability to the phone
2. Phone recognizes adapter supports navigation and registers for route guidance callbacks
3. When the user starts navigation, phone sends RGD messages to the adapter
4. Adapter firmware converts iAP2 binary → JSON and sends as USB type 0x2A/subtype 200

**If DashboardInfo does not have bit 2 set, the phone will never send navigation data.**

---

## 3. Step 2: iAP2 Route Guidance on the Wire

Once `DashboardInfo` bit 2 is active, the phone sends iAP2 Route Guidance Display (RGD) messages:

| iAP2 Message | ID | Purpose |
|---|---|---|
| `StartRouteGuidanceUpdate` | 0x5200 | Adapter requests turn-by-turn updates |
| `RouteGuidanceUpdate` | 0x5201 | Route status changes (active, calculating, ended) |
| `RouteGuidanceManeuverUpdate` | 0x5202 | Turn instructions (maneuver type, distance, road name) |
| `LaneGuidanceInformation` | — | Lane guidance data (not used by carlink_native) |

The adapter firmware's `iAP2RouteGuidanceEngine` processes these messages and calls `_SendNaviJSON()`, which serializes the data as a JSON string and wraps it in a USB MEDIA_DATA packet.

**Key RGD metadata fields from firmware strings (libNmeIAP.so):**
```
NMEMETANAME_IAP_RGD_MAX_CURRENT_ROAD_NAME_LENGTH
NMEMETANAME_IAP_RGD_MAX_AFTER_MANEUVER_ROAD_NAME_LENGTH
NMEMETANAME_IAP_RGD_MAX_MANEUVER_DESCRIPTION_LENGTH
NMEMETANAME_IAP_RGD_MAX_GUIDANCE_MANEUVER_CAPACITY
```

---

## 4. Step 3: USB Protocol — NaviJSON Message Format

### Packet Structure

```
┌────────────────────────────────────────────────────────────┐
│ USB Message Header (16 bytes)                              │
│ ┌──────────┬──────────┬──────────┬───────────────────────┐ │
│ │ Magic    │ Length   │ Type     │ Type Check            │ │
│ │ 55AA55AA │ payload  │ 0000002A │ FFFFFFD5              │ │
│ │ (4 bytes)│ size     │ MEDIA_   │ (type ^ 0xFFFFFFFF)   │ │
│ │          │ (4 bytes)│ DATA     │ (4 bytes)             │ │
│ └──────────┴──────────┴──────────┴───────────────────────┘ │
├────────────────────────────────────────────────────────────┤
│ Media Subtype (4 bytes, little-endian)                     │
│ ┌──────────────────────────────────────────────────────┐   │
│ │ 0x000000C8 (200 = MediaType.NAVI_JSON)               │   │
│ └──────────────────────────────────────────────────────┘   │
├────────────────────────────────────────────────────────────┤
│ JSON Payload (variable length, null-terminated UTF-8)      │
│ ┌──────────────────────────────────────────────────────┐   │
│ │ {"NaviStatus":1,"NaviRoadName":"Main St",...}\0      │   │
│ └──────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────┘
```

### Protocol Constants

```kotlin
// MessageTypes.kt
enum class MessageType(val id: Int) {
    MEDIA_DATA(0x2A),   // Navigation and media metadata
    // ...
}

enum class MediaType(val id: Int) {
    DATA(1),            // NowPlaying media info (song/artist)
    ALBUM_COVER(3),     // Album artwork (JPEG)
    NAVI_JSON(200),     // Navigation JSON (subtype 0xC8)
    UNKNOWN(-1),
}
```

### NaviJSON Fields

All distances are in **meters**, all times in **seconds**. Fields arrive **incrementally** — a single update may contain only 1-5 fields.

| JSON Key | Type | Description | Example |
|---|---|---|---|
| `NaviStatus` | int | 0=idle, 1=active, 2=calculating | `1` |
| `NaviManeuverType` | int | CarPlay CPManeuverType (0-53) | `2` (turn right) |
| `NaviOrderType` | int | 0=continue, 1=turn, 2=exit, 3=roundabout, 4=uturn, 5=keepLeft, 6=keepRight | `1` |
| `NaviRoadName` | string | Current or next road name | `"Farrior Dr"` |
| `NaviRemainDistance` | int | Meters to next maneuver | `500` |
| `NaviDistanceToDestination` | int | Total meters to destination | `6124` |
| `NaviTimeToDestination` | int | Seconds to destination (ETA) | `480` |
| `NaviDestinationName` | string | Destination POI name or address | `"Speedway"` |
| `NaviAPPName` | string | Source navigation app | `"Apple Maps"` |
| `NaviTurnAngle` | int | Turn angle in degrees | `90` |
| `NaviTurnSide` | int | 0=right-hand driving (US/EU), 1=left-hand driving (UK/JP/AU) | `0` |
| `NaviJunctionType` | int | 0=intersection, 1=roundabout | `0` |

**Example NaviJSON burst** (3 messages over ~300ms):
```json
// Message 1:
{"NaviStatus":1,"NaviDestinationName":"Speedway","NaviAPPName":"Apple Maps"}
// Message 2 (~150ms later):
{"NaviRemainDistance":26,"NaviRoadName":"Farrior Dr","NaviOrderType":1}
// Message 3 (~100ms later):
{"NaviManeuverType":11,"NaviDistanceToDestination":6124,"NaviTimeToDestination":480}
```

---

## 5. Step 4: Parsing NaviJSON in the App

### Message Routing

```kotlin
// MessageParser.kt — dispatches by MessageType
fun parse(header: MessageHeader, payload: ByteArray?): Message =
    when (header.type) {
        MessageType.MEDIA_DATA -> parseMediaData(header, payload)
        // ... other types
    }
```

### NaviJSON Extraction

```kotlin
// MessageParser.kt — parseMediaData()
private fun parseMediaData(header: MessageHeader, payload: ByteArray?): Message {
    if (payload == null || header.length < 4) {
        return MediaDataMessage(header, MediaType.UNKNOWN, emptyMap())
    }

    val buffer = ByteBuffer.wrap(payload, 0, header.length).order(ByteOrder.LITTLE_ENDIAN)
    val typeInt = buffer.int                      // First 4 bytes = MediaType ID
    val mediaType = MediaType.fromId(typeInt)      // 200 → NAVI_JSON

    val mediaPayload: Map<String, Any> = when (mediaType) {
        MediaType.DATA, MediaType.NAVI_JSON -> {
            if (header.length < 6) {
                emptyMap()
            } else try {
                // Skip 4-byte type int, exclude trailing null
                val jsonBytes = ByteArray(header.length - 5)
                System.arraycopy(payload, 4, jsonBytes, 0, jsonBytes.size)
                val jsonString = String(jsonBytes, StandardCharsets.UTF_8).trim('\u0000')
                val json = JSONObject(jsonString)
                json.keys().asSequence().associateWith { json.get(it) }
            } catch (e: JSONException) {
                emptyMap()
            }
        }
        // ... other media types
    }

    return MediaDataMessage(header, mediaType, mediaPayload)
}
```

### Routing to NavigationStateManager

```kotlin
// CarlinkManager.kt — processMediaMetadata()
private fun processMediaMetadata(message: MediaDataMessage) {
    // Route NaviJSON to NavigationStateManager
    if (message.type == MediaType.NAVI_JSON) {
        logNavi { "[NAVI] Routing NaviJSON to NavigationStateManager (${message.payload.size} fields)" }
        NavigationStateManager.onNaviJson(message.payload)
        return
    }
    // ... handle NowPlaying metadata, album covers, etc.
}
```

On USB disconnect, state is cleared:
```kotlin
NavigationStateManager.clear()
```

---

## 6. Step 5: NavigationStateManager — Merging Incremental Updates

NaviJSON arrives in bursts — 1 to 5 fields per message, 100-500ms apart. A single navigation update (e.g., "turn right in 500m onto Main St") may arrive as 3 separate messages. The `NavigationStateManager` singleton merges these incremental updates into a complete state, published via `StateFlow`.

```kotlin
// NavigationStateManager.kt
data class NavigationState(
    val status: Int = 0,               // 0=idle, 1=active, 2=calculating
    val maneuverType: Int = 0,         // CPManeuverType 0-53
    val orderType: Int = 0,            // 0=continue, 1=turn, 2=exit, 3=roundabout, 4=uturn
    val roadName: String? = null,
    val remainDistance: Int = 0,        // Meters to next maneuver
    val distanceToDestination: Int = 0, // Total meters remaining
    val timeToDestination: Int = 0,    // Seconds to destination
    val destinationName: String? = null,
    val appName: String? = null,       // "Apple Maps", "Google Maps", "Waze"
    val turnAngle: Int = 0,            // Turn angle in degrees
    val turnSide: Int = 0,             // 0=RHD, 1=LHD
    val junctionType: Int = 0,         // 0=intersection, 1=roundabout
) {
    val isActive: Boolean get() = status == 1
    val isIdle: Boolean get() = status == 0
}

object NavigationStateManager {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    fun onNaviJson(payload: Map<String, Any>) {
        if (payload.isEmpty()) return

        val naviStatus = (payload["NaviStatus"] as? Number)?.toInt()

        // Flush signal: NaviStatus=0 → clear entire state (navigation ended)
        if (naviStatus == 0) {
            _state.value = NavigationState()
            return
        }

        // Incremental merge: update only fields present in this payload.
        // Fields not in this message retain their previous values.
        val current = _state.value
        _state.value = current.copy(
            status = naviStatus ?: current.status,
            maneuverType = (payload["NaviManeuverType"] as? Number)?.toInt() ?: current.maneuverType,
            orderType = (payload["NaviOrderType"] as? Number)?.toInt() ?: current.orderType,
            roadName = (payload["NaviRoadName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.roadName,
            remainDistance = (payload["NaviRemainDistance"] as? Number)?.toInt() ?: current.remainDistance,
            distanceToDestination = (payload["NaviDistanceToDestination"] as? Number)?.toInt() ?: current.distanceToDestination,
            timeToDestination = (payload["NaviTimeToDestination"] as? Number)?.toInt() ?: current.timeToDestination,
            destinationName = (payload["NaviDestinationName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.destinationName,
            appName = (payload["NaviAPPName"] as? String)?.takeIf { it.isNotEmpty() } ?: current.appName,
            turnAngle = (payload["NaviTurnAngle"] as? Number)?.toInt() ?: current.turnAngle,
            turnSide = (payload["NaviTurnSide"] as? Number)?.toInt() ?: current.turnSide,
            junctionType = (payload["NaviJunctionType"] as? Number)?.toInt() ?: current.junctionType,
        )
    }

    fun clear() {
        _state.value = NavigationState()
    }
}
```

**Thread safety**: Called from USB read thread; `MutableStateFlow` is thread-safe. Consumers collect on `Dispatchers.Main`.

**Merge strategy**: New fields overwrite, absent fields retain previous values, `NaviStatus=0` is the flush signal.

---

## 7. Step 6: AAOS Car App Library Integration

### Prerequisites

**AndroidManifest.xml:**
```xml
<!-- Permissions -->
<uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />
<uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />

<!-- Service declaration -->
<service android:name=".car.CarlinkCarAppService" android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <!-- Navigation category — required for NavigationTemplate and surface -->
        <category android:name="androidx.car.app.category.NAVIGATION" />
        <!-- Cluster feature — tells Templates Host to create a cluster session -->
        <category android:name="androidx.car.app.category.FEATURE_CLUSTER" />
    </intent-filter>
    <!-- Car API level 6+ required for SessionInfo.displayType -->
    <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="6" />
</service>
```

**build.gradle:**
```groovy
implementation "androidx.car.app:app:1.7.0"
implementation "androidx.car.app:app-projected:1.7.0"
```

### 7a. CarAppService — Session Factory

Templates Host binds to your `CarAppService` and calls `onCreateSession(SessionInfo)` for each display. You return a different `Session` subclass based on `displayType`.

```kotlin
class CarlinkCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    // Fallback for Car API < 6 (no SessionInfo parameter)
    override fun onCreateSession(): Session = CarlinkMainSession()

    // Car API 6+ — dispatches by display type
    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return when (sessionInfo.displayType) {
            SessionInfo.DISPLAY_TYPE_CLUSTER -> CarlinkClusterSession()
            else -> CarlinkMainSession()
        }
    }
}
```

### 7b. CarlinkClusterSession — Cluster Lifecycle Owner

Observes `NavigationStateManager` and pushes `Trip` updates to Templates Host via `NavigationManager`. Uses 200ms debounce to batch rapid NaviJSON bursts.

```kotlin
class CarlinkClusterSession : Session() {
    private var screen: CarlinkClusterScreen? = null
    private var navigationManager: NavigationManager? = null
    private var isNavigating = false
    private var scope: CoroutineScope? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val clusterScreen = CarlinkClusterScreen(carContext)
        screen = clusterScreen

        navigationManager = carContext.getCarService(NavigationManager::class.java)

        // IMPORTANT: Callback MUST be set BEFORE calling navigationStarted()
        navigationManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { isNavigating = false }
            override fun onAutoDriveEnabled() { /* no-op */ }
        })

        scope = CoroutineScope(Dispatchers.Main)
        scope!!.launch { collectNavigationState() }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (isNavigating) navigationManager?.navigationEnded()
                scope?.cancel()
            }
        })

        return clusterScreen
    }

    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null
        NavigationStateManager.state.collectLatest { state ->
            debounceJob?.cancel()
            debounceJob = scope?.launch {
                delay(200) // Debounce NaviJSON bursts (100-500ms apart)
                processStateUpdate(state)
            }
        }
    }

    private fun processStateUpdate(state: NavigationState) {
        val navManager = navigationManager ?: return

        if (state.isActive) {
            if (!isNavigating) {
                navManager.navigationStarted()
                isNavigating = true
            }
            navManager.updateTrip(buildTrip(state))
            screen?.updateState(state)
        } else if (state.isIdle && isNavigating) {
            navManager.navigationEnded()
            isNavigating = false
            screen?.updateState(state)
        }
    }

    private fun buildTrip(state: NavigationState): Trip {
        val tripBuilder = Trip.Builder()

        // Current step: maneuver + road name + distance
        val maneuver = ManeuverMapper.buildManeuver(state, carContext)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuver)
        state.roadName?.let { stepBuilder.setCue(it) }

        val stepEstimate = TravelEstimate.Builder(
            DistanceFormatter.toDistance(state.remainDistance),
            ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong())),
        ).build()
        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        // Destination info (optional)
        if (state.destinationName != null || state.distanceToDestination > 0) {
            val destBuilder = Destination.Builder()
            state.destinationName?.let { destBuilder.setName(it) }
            val destEstimate = TravelEstimate.Builder(
                DistanceFormatter.toDistance(state.distanceToDestination),
                ZonedDateTime.now().plus(Duration.ofSeconds(state.timeToDestination.toLong())),
            ).build()
            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        tripBuilder.setLoading(false)
        return tripBuilder.build()
    }
}
```

### 7c. CarlinkClusterScreen — Cluster Display Template

Returns a `NavigationTemplate` with `RoutingInfo` showing: maneuver arrow (icon), distance to next turn, and road name. **ActionStrip is mandatory** — the app crashes without it.

```kotlin
class CarlinkClusterScreen(carContext: CarContext) : Screen(carContext) {
    private var currentState: NavigationState? = null

    // ActionStrip is MANDATORY — NavigationTemplate.build() crashes without it
    private val actionStrip = ActionStrip.Builder()
        .addAction(Action.APP_ICON)
        .build()

    fun updateState(state: NavigationState) {
        currentState = state
        invalidate() // Triggers onGetTemplate() re-call by Templates Host
    }

    override fun onGetTemplate(): Template {
        val state = currentState
        if (state == null || !state.isActive) {
            return NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .build()
        }

        return try {
            val maneuver = ManeuverMapper.buildManeuver(state, carContext)

            val stepBuilder = Step.Builder()
            stepBuilder.setManeuver(maneuver)
            state.roadName?.let { stepBuilder.setCue(it) }

            val distance = DistanceFormatter.toDistance(state.remainDistance)

            val routingInfo = RoutingInfo.Builder()
                .setCurrentStep(stepBuilder.build(), distance)
                .build()

            NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .setNavigationInfo(routingInfo)
                .build()
        } catch (e: Exception) {
            // Fallback — never crash the cluster
            NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .build()
        }
    }
}
```

### 7d. Main Session Also Pushes Trip (Dual Path)

The main projection session **also** pushes `Trip` updates via `NavigationManager`. Templates Host forwards Trip data from the **focused** (main) session to the cluster. Both paths are needed — the Trip path feeds Templates Host's internal routing, while the cluster session's direct rendering gives explicit control.

```kotlin
// In CarlinkProjectionScreen (main display)
private suspend fun collectNavigationState() {
    var debounceJob: Job? = null
    NavigationStateManager.state.collectLatest { state ->
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(200)
            pushNavigationState(state)
        }
    }
}

private fun pushNavigationState(state: NavigationState) {
    val navManager = navigationManager ?: return
    if (state.isActive) {
        if (!isNavigating) {
            navManager.navigationStarted()
            isNavigating = true
        }
        navManager.updateTrip(buildTrip(state))
    } else if (state.isIdle && isNavigating) {
        navManager.navigationEnded()
        isNavigating = false
    }
}
```

---

## 8. Step 7: Custom Maneuver Icons for Cluster

### Why Explicit Icons Are Required

The AAOS `Maneuver.TYPE_*` enum is **too generic** — it has ~18 types with no left/right distinction (e.g., just `TYPE_TURN`, not `TYPE_TURN_LEFT` vs `TYPE_TURN_RIGHT`). GM's VMSClusterService checks icon sources in priority order:

1. **ManeuverIcon** (`Maneuver.setIcon()`) — explicit icon provided by the app
2. **ManeuverByteArray** — raw icon bytes from the nav app
3. **TurnType** — fallback to the generic enum (loses directionality)

Without `setIcon()`, the cluster may render the wrong arrow direction.

### Vector Drawable Format

All 18 icons are Android vector drawables (20x20dp viewport, white fill, single path). Source: [Mapbox directions-icons](https://github.com/mapbox/directions-icons) (CC0-1.0 public domain).

**Example — `ic_nav_turn_left.xml`:**
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="20"
    android:viewportHeight="20">
    <!-- Mapbox directions-icons: turn_left (CC0-1.0) -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M10,5.97986l0.011,0.00183A6.06019,..."/>
</vector>
```

**Example — `ic_nav_roundabout_ccw.xml`:**
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="20"
    android:viewportHeight="20">
    <!-- Mapbox directions-icons: roundabout (CC0-1.0) -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M5.5,10.002a0.17879,..."/>
</vector>
```

### Complete Icon Set (18 drawables)

| Drawable Resource | Used By CPManeuverType(s) |
|---|---|
| `ic_nav_straight` | 0 (noTurn), 3 (straight), 5 (followRoad) |
| `ic_nav_turn_left` | 1 (left), 20 (endOfRoadLeft) |
| `ic_nav_turn_right` | 2 (right), 21 (endOfRoadRight) |
| `ic_nav_sharp_left` | 47 (sharpLeft) |
| `ic_nav_sharp_right` | 48 (sharpRight) |
| `ic_nav_slight_left` | 49 (slightLeft) |
| `ic_nav_slight_right` | 50 (slightRight) |
| `ic_nav_uturn_left` | 4, 18, 26 (u-turn variants, RHD) |
| `ic_nav_uturn_right` | 4, 18, 26 (u-turn variants, LHD) |
| `ic_nav_roundabout_ccw` | 6, 7, 19, 28-46 (roundabout, RHD) |
| `ic_nav_roundabout_cw` | 6, 7, 19, 28-46 (roundabout, LHD) |
| `ic_nav_off_ramp_left` | 22 (rampOffLeft) |
| `ic_nav_off_ramp_right` | 8 (rampOff), 23 (rampOffRight) |
| `ic_nav_fork_left` | 13 (keepLeft), 52 (changeHighwayLeft) |
| `ic_nav_fork_right` | 9 (rampOn), 14 (keepRight), 51, 53 (changeHighway) |
| `ic_nav_depart` | 11 (proceedToRoute) |
| `ic_nav_destination` | 10, 12, 24, 25, 27 (arrive/destination variants) |
| `ic_nav_ferry` | 15, 16, 17 (ferry variants) |

### ManeuverMapper — Building Maneuver with Icon

```kotlin
object ManeuverMapper {

    fun buildManeuver(state: NavigationState, context: Context): Maneuver {
        val type = mapManeuverType(state.maneuverType, state.turnSide)
        val builder = Maneuver.Builder(type)

        // Roundabout exit number (types 28-46, exit = cpType - 27)
        getRoundaboutExitNumber(state.maneuverType)?.let {
            builder.setRoundaboutExitNumber(it)
        }

        // Explicit icon for cluster — overrides generic TurnType
        val iconRes = getIconResource(state.maneuverType, state.turnSide)
        val icon = CarIcon.Builder(
            IconCompat.createWithResource(context, iconRes),
        ).build()
        builder.setIcon(icon)

        return builder.build()
    }
}
```

### CPManeuverType → Icon + Maneuver Type Mapping

| CPType | CarPlay Name | AAOS Maneuver (RHD) | AAOS Maneuver (LHD) | Icon (RHD) | Icon (LHD) |
|--------|---|---|---|---|---|
| 0 | noTurn | TYPE_STRAIGHT | TYPE_STRAIGHT | straight | straight |
| 1 | left | TURN_NORMAL_LEFT | TURN_NORMAL_LEFT | turn_left | turn_left |
| 2 | right | TURN_NORMAL_RIGHT | TURN_NORMAL_RIGHT | turn_right | turn_right |
| 3 | straight | TYPE_STRAIGHT | TYPE_STRAIGHT | straight | straight |
| 4 | uTurn | U_TURN_LEFT | U_TURN_RIGHT | uturn_left | uturn_right |
| 5 | followRoad | TYPE_STRAIGHT | TYPE_STRAIGHT | straight | straight |
| 6 | enterRoundabout | ROUNDABOUT_ENTER_CCW | ROUNDABOUT_ENTER_CW | roundabout_ccw | roundabout_cw |
| 7 | exitRoundabout | ROUNDABOUT_EXIT_CCW | ROUNDABOUT_EXIT_CW | roundabout_ccw | roundabout_cw |
| 8 | rampOff | OFF_RAMP_NORMAL_RIGHT | OFF_RAMP_NORMAL_RIGHT | off_ramp_right | off_ramp_right |
| 9 | rampOn | ON_RAMP_NORMAL_RIGHT | ON_RAMP_NORMAL_RIGHT | fork_right | fork_right |
| 10 | endOfNavigation | DESTINATION | DESTINATION | destination | destination |
| 11 | proceedToRoute | DEPART | DEPART | depart | depart |
| 12 | arrived | DESTINATION | DESTINATION | destination | destination |
| 13 | keepLeft | KEEP_LEFT | KEEP_LEFT | fork_left | fork_left |
| 14 | keepRight | KEEP_RIGHT | KEEP_RIGHT | fork_right | fork_right |
| 15-17 | ferry variants | FERRY_BOAT | FERRY_BOAT | ferry | ferry |
| 18 | uTurnToRoute | U_TURN_LEFT | U_TURN_RIGHT | uturn_left | uturn_right |
| 19 | roundaboutUTurn | ROUNDABOUT_ENTER_CCW | ROUNDABOUT_ENTER_CW | roundabout_ccw | roundabout_cw |
| 20 | endOfRoadLeft | TURN_NORMAL_LEFT | TURN_NORMAL_LEFT | turn_left | turn_left |
| 21 | endOfRoadRight | TURN_NORMAL_RIGHT | TURN_NORMAL_RIGHT | turn_right | turn_right |
| 22 | rampOffLeft | OFF_RAMP_NORMAL_LEFT | OFF_RAMP_NORMAL_LEFT | off_ramp_left | off_ramp_left |
| 23 | rampOffRight | OFF_RAMP_NORMAL_RIGHT | OFF_RAMP_NORMAL_RIGHT | off_ramp_right | off_ramp_right |
| 24 | arrivedLeft | DESTINATION_LEFT | DESTINATION_LEFT | destination | destination |
| 25 | arrivedRight | DESTINATION_RIGHT | DESTINATION_RIGHT | destination | destination |
| 26 | uTurnWhenPossible | U_TURN_LEFT | U_TURN_RIGHT | uturn_left | uturn_right |
| 27 | endOfDirections | DESTINATION | DESTINATION | destination | destination |
| 28-46 | roundaboutExit N | ROUNDABOUT_ENTER_AND_EXIT_CCW (exit=N-27) | ROUNDABOUT_ENTER_AND_EXIT_CW (exit=N-27) | roundabout_ccw | roundabout_cw |
| 47 | sharpLeft | TURN_SHARP_LEFT | TURN_SHARP_LEFT | sharp_left | sharp_left |
| 48 | sharpRight | TURN_SHARP_RIGHT | TURN_SHARP_RIGHT | sharp_right | sharp_right |
| 49 | slightLeft | TURN_SLIGHT_LEFT | TURN_SLIGHT_LEFT | slight_left | slight_left |
| 50 | slightRight | TURN_SLIGHT_RIGHT | TURN_SLIGHT_RIGHT | slight_right | slight_right |
| 51 | changeHighway | KEEP_RIGHT | KEEP_RIGHT | fork_right | fork_right |
| 52 | changeHighwayLeft | KEEP_LEFT | KEEP_LEFT | fork_left | fork_left |
| 53 | changeHighwayRight | KEEP_RIGHT | KEEP_RIGHT | fork_right | fork_right |

**`turnSide` controls directionality**: In left-hand-drive countries (UK, Japan, Australia), U-turns go right instead of left, and roundabouts rotate clockwise instead of counter-clockwise. Without this parameter, half the world gets backwards arrows.

---

## 9. Step 8: Distance Conversion (Meters → Imperial/Metric)

iAP2 always sends distances in **meters**. The Car App Library `Distance.create()` requires you to specify the display unit — it does **not** auto-convert. GM's native CINEMO path provides pre-formatted strings, but third-party apps must convert manually.

```kotlin
object DistanceFormatter {
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_THRESHOLD = 305   // ~1000 feet
    private const val KM_THRESHOLD = 1000

    /** Countries using imperial units for road distances */
    private val IMPERIAL_COUNTRIES = setOf("US", "GB", "MM", "LR")

    fun toDistance(meters: Int): Distance {
        return if (isImperial()) toImperial(meters) else toMetric(meters)
    }

    private fun toImperial(meters: Int): Distance {
        return if (meters < FEET_THRESHOLD) {
            // < ~1000 ft → display in feet, rounded to nearest 50
            val feet = meters / METERS_PER_FOOT
            val rounded = (Math.round(feet / 50.0) * 50).coerceAtLeast(50).toDouble()
            Distance.create(rounded, Distance.UNIT_FEET)
        } else {
            // >= ~1000 ft → display in miles with 1 decimal place
            Distance.create(meters / METERS_PER_MILE, Distance.UNIT_MILES_P1)
        }
    }

    private fun toMetric(meters: Int): Distance {
        return if (meters < KM_THRESHOLD) {
            // < 1000m → display in meters, rounded to nearest 50
            val rounded = (Math.round(meters / 50.0) * 50).coerceAtLeast(50).toDouble()
            Distance.create(rounded, Distance.UNIT_METERS)
        } else {
            // >= 1000m → display in kilometers with 1 decimal place
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        }
    }

    private fun isImperial(): Boolean =
        Locale.getDefault().country in IMPERIAL_COUNTRIES
}
```

**Conversion rules:**

| Locale | Range | Display | Unit | Example (500m input) |
|--------|-------|---------|------|---------------------|
| Imperial | < 305m (~1000 ft) | Feet, rounded to 50 | `UNIT_FEET` | "1650 ft" |
| Imperial | >= 305m | Miles, 1 decimal | `UNIT_MILES_P1` | "0.3 mi" |
| Metric | < 1000m | Meters, rounded to 50 | `UNIT_METERS` | "500 m" |
| Metric | >= 1000m | Kilometers, 1 decimal | `UNIT_KILOMETERS_P1` | "0.5 km" |

---

## 10. GM AAOS: Templates Host and Cluster Architecture

### Templates Host — The Privileged Intermediary

GM Info 3.7 (and all AAOS vehicles) ships with **Google Templates Host** (`com.google.android.apps.automotive.templates.host`), a privileged system app that:

- Binds to your `CarAppService` and creates sessions for each display
- Manages surfaces for video rendering (main display)
- Routes `Trip` data from navigation apps to the cluster
- Holds signature-level permissions that third-party apps **cannot** request:

| Permission | Level | Purpose |
|---|---|---|
| `android.car.permission.CAR_NAVIGATION_MANAGER` | signature | Push navigation data |
| `android.car.permission.CAR_DISPLAY_IN_CLUSTER` | signature | Render on cluster display |
| `android.car.permission.TEMPLATE_RENDERER` | signature | Render templates |

**Third-party apps cannot access the cluster directly.** Templates Host acts as the intermediary — your app pushes data via the Car App Library API, and Templates Host (with its privileged permissions) forwards it to the cluster.

### GM's Native Cluster Pipeline (for reference)

GM's own CarPlay implementation uses a completely different path that does not go through Templates Host:

```
iPhone (CarPlay RGD metadata)
    ↓ iAP2 USB/WiFi
libNmeIAP.so (CINEMO framework, 2.9 MB)
    ├─ RouteGuidanceDisplayComponent
    └─ OnRouteGuidanceUpdate() / OnRouteGuidanceManeuverUpdate()
    ↓
com.gm.server.NavigationService [INavigationService]
    ↓ SELinux: gm_domain_service_nav
gm.onstar.OnStarTurnByTurnManager (GMOnStarTBT.apk)
    ↓ Central hub for all TBT sources
clusterService [gm.cluster.IClusterHmi] (ClusterService.apk, 2.9 MB)
    ├─ Framework: info3_cluster.jar
    └─ Renders using local PNG assets:
       /assets/Extended/C1/quick_turn_primary_maneuver.png
       /assets/Extended/C2/arrow_bg_enhanced.png
       /assets/Extended/C2/nav_distance_window.png
       /assets/Extended/C2/carplay_icon.png
    ↓
vehiclepanel (vendor.gm.panel@1.0::IPanel)
    ↓ Proprietary HAL
Instrument Cluster ECU (via CAN bus, ~500kbps)
```

**Key differences from Car App Library path:**
- GM uses **text metadata only** for the cluster — no video is sent (CAN bus bandwidth is too low)
- GM renders using **local PNG assets** in ClusterService.apk, not vector drawables from the nav app
- GM uses the **CINEMO** software framework (not Android MediaCodec) for CarPlay integration
- GM's cluster pipeline has **direct system access** via privileged SELinux domains

### What This Means for Third-Party Apps

1. You **must** use Car App Library + Templates Host — no direct cluster access
2. Your custom icons (vector drawables) are what the cluster renders, so they must be correct
3. Templates Host handles the privileged forwarding transparently
4. The `FEATURE_CLUSTER` manifest category is what triggers Templates Host to create a cluster session

---

## 11. Gotchas and Lessons Learned

### ActionStrip is Mandatory on NavigationTemplate

`NavigationTemplate.Builder().build()` crashes with `IllegalStateException: Action strip for this template must be set`. This applies to **all** code paths — empty state, active nav, and error fallback. Use:

```kotlin
private val actionStrip = ActionStrip.Builder()
    .addAction(Action.APP_ICON)
    .build()
```

### NavigationManagerCallback Before navigationStarted()

Templates Host requires `setNavigationManagerCallback()` **before** `navigationStarted()`. Wrong order throws. The callback handles `onStopNavigation()` (system-initiated nav stop).

### Debounce is Essential

NaviJSON arrives in bursts (1-5 fields, 100-500ms apart). Without debouncing, `updateTrip()` fires excessively, causing cluster UI flicker. 200ms works well.

### Session Creation Order is Non-Deterministic

Templates Host may create the cluster session before or after the main session. Use a singleton (`NavigationStateManager`) so both sessions observe the same state regardless of creation order.

### Emulator Cluster is Tiny

The AAOS emulator's cluster is 400x500 pixels — road names will be cut off. Real vehicles should have proper cluster dimensions. This is not an app bug.

### Car App Library Limitations (Why carlink_native Reverted to MainActivity)

#### 1. Video Rendering Blocked by System UI

Templates Host always provides a **full-screen surface** — the app cannot resize or reposition the `MediaCodec` output within it. In non-immersive mode, the video renders behind system bars (status bar, navigation dock). The video itself is correctly decoded at the full surface resolution (e.g., 2400x960), but system UI elements overlay on top, obscuring projection UI controls.

Attempts to fix this by passing the "stable area" (usable region excluding system bars) to `MediaCodec` failed — the decoder outputs at the smaller resolution, but Templates Host still composites the surface at full-screen size, causing a 43% vertical stretch. `MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT` also had no effect because Templates Host controls surface compositing, not the app.

**Workaround**: Force immersive/full-screen mode so system bars hide. But this creates new problems:
- **Electric Vehicle UI**: GM AAOS reserves screen areas for EV-specific elements (Climate Controls, charge status, range). Immersive mode hides these.
- **Non-rectangular displays**: Some vehicles have curved or asymmetric display cutouts. Immersive mode ignores these safe zones.
- **System bar access**: Users lose access to notifications, quick settings, and the app drawer without swiping from edges.

There is no clean solution within the Car App Library for video projection apps. The surface is meant for a map like display with UI elements poistiong where needed. Projection is a video feed. Without proper SafeArea support at the adapter level. You can't reposition Carplay/AA UI elements.

#### 2. Persistent ActionStrip Obstructs Projection UI

`NavigationTemplate` **requires** an `ActionStrip` — calling `.build()` without one crashes with `IllegalStateException: Action strip for this template must be set`. The ActionStrip renders as an overlay on top of the projection surface and cannot be hidden, removed, or made transparent.

Even with a minimal strip (`Action.APP_ICON` only), it appears as a floating button over the CarPlay UI. This obstructs CarPlay's own UI elements — particularly problematic when CarPlay's back button or media controls align with the ActionStrip position.

The `MapActionStrip` (with `Action.PAN`) adds a second floating button. Both fade after a timeout but reappear on any touch interaction.

#### 3. Touch Input: Working Workaround, But Limited

On API 30+ (AAOS), the Car App Library's `TemplateSurfaceView` uses `SurfaceControlViewHost.SurfacePackage` via `setChildSurfacePackage()`. This causes Android's window manager to route touch events directly to Templates Host's embedded window, **bypassing** any `OnTouchListener` set on the `SurfaceView`. A direct `View.setOnTouchListener()` approach (like `TouchInterceptor`) silently fails — the listener is registered but never receives events.

**Working workaround**: Use `SurfaceCallback` methods exclusively:

```kotlin
// SurfaceCallback provides these normalized touch callbacks:
override fun onClick(x: Float, y: Float)           // Tap (fires after gesture completes)
override fun onScroll(distanceX: Float, distanceY: Float)  // Drag deltas
override fun onFling(velocityX: Float, velocityY: Float)   // Swipe end velocity
override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) // Pinch zoom
```

**Scroll gesture implementation** — accumulate deltas into absolute position, convert to CarPlay touch events:

```kotlin
private var isScrolling = false
private var scrollX = 0f  // Absolute position 0.0-1.0
private var scrollY = 0f
private val scrollEndHandler = Handler(Looper.getMainLooper())
private val scrollEndRunnable = Runnable { endScrollGesture() }

override fun onScroll(distanceX: Float, distanceY: Float) {
    if (!isScrolling) {
        // Start from surface center
        isScrolling = true
        scrollX = 0.5f
        scrollY = 0.5f
        sendTouch(scrollX, scrollY, MultiTouchAction.DOWN)
    }

    // GestureDetector convention: distance = previous - current, so subtract
    scrollX -= distanceX / surfaceWidth
    scrollY -= distanceY / surfaceHeight
    scrollX = scrollX.coerceIn(0f, 1f)
    scrollY = scrollY.coerceIn(0f, 1f)

    sendTouch(scrollX, scrollY, MultiTouchAction.MOVE)

    // 100ms timeout detects end-of-scroll → send UP
    scrollEndHandler.removeCallbacks(scrollEndRunnable)
    scrollEndHandler.postDelayed(scrollEndRunnable, 100L)
}
```

**What's missing and cannot be fixed:**
- **No long-press**: `onClick` fires after the gesture completes (like `ACTION_UP`), not on initial touch. There is no `onLongPress` callback. CarPlay features requiring press-and-hold (e.g., Siri activation, map POI preview) are inaccessible.
- **No raw `MotionEvent`**: Cannot distinguish multi-finger gestures beyond scalar scale factor, or implement custom gesture recognizers.
- **`onScroll`/`onFling` may require pan mode**: The `Action.PAN` button in `MapActionStrip` may need to be toggled by the user before scroll events are forwarded. This is a Templates Host behavior that varies by vehicle.
- **No two-finger gestures**: `onScale` provides only a scalar `scaleFactor` — there is no way to reconstruct two-finger positions to simulate pinch-zoom on CarPlay's map.

#### Summary

The Car App Library's cluster navigation support works correctly — it's the **only** way for third-party apps to push turn-by-turn data to the AAOS instrument cluster. However, its video projection and touch input limitations make it unsuitable as a primary rendering path for CarPlay projection apps. The carlink_native project reverted to `MainActivity` with direct `SurfaceView` rendering for the main display, while exploring alternative approaches for cluster integration.

---
