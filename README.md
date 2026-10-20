# polar-loop-protocol-android

Reverse-engineered BLE/PMD/PFTP protocol layer for the Polar Loop Gen 2:
decoders for its historical data files (activity, sleep, daily summary,
nightly recharge, skin temperature, PPI/HR autosamples), PMD live-streaming
frame decoding, PFTP command construction, and a Health Connect mapping
layer. Extracted from [Polar Loop Debugger](../debugging-polarloop), a
standalone Android app that uses this library directly.

No UI/Activity/Compose dependencies -- pure Kotlin + Android BLE/Health
Connect APIs, usable from any Android app.

**Read-only by design, except deletion of day folders and 24/7 files, only
when the app explicitly requests it**: the library writes only
`OutgoingFrame`s, a closed set of byte strings. `OutgoingFrameAllowListTest`
lists every one of them per characteristic and fails if anything else becomes
buildable. In particular, nothing can produce a PFTP PUT/MERGE, RESET (`02 02`
on fb005c53), or a PMD opcode outside 1-3. This library can read a Polar Loop
Gen 2's data; it cannot write to it or reconfigure it.

The one deletion (v1.4.0): `sendPftpRemove(target)` sends a PFTP REMOVE
(`08 03`, framed like a GET, the same frames Polar Flow sends after its own
sync). `target` is a `PftpRemoveTarget`, a type that can hold exactly two path
shapes and nothing else: a day folder `/U/0/YYYYMMDD/` with a real calendar
date, or a 24/7 file `/U/0/AUTOS/AUTOSnnn.BPB`. There is no REMOVE from a path
string, and the library never sends one on its own; a source tripwire fails if
any code path besides that method can.

One opt-in exception: `requestSynchronization()` sends REQUEST_SYNCHRONIZATION
(`02 0D 80`, the request Polar Flow sends before it syncs). Polar documents it as
making the device "flush the latest data to files", so the device writes its own
buffered data out. The library never sends it on its own; a test fails if any
code path besides that method can.

## Using it

Every PFTP request delivers exactly one `PftpResult` to the callback passed
with it, on the client's callback thread:

```kotlin
client.sendPftpGet("/U/0/AUTOS/") { result ->
    when (result) {
        is PftpResult.Content -> decodeDirectoryListing(result.bytes)
        PftpResult.Empty -> {}                         // success, no body
        is PftpResult.DeviceError -> {}                // e.g. 103 NO_SUCH_FILE_OR_DIRECTORY
        PftpResult.Timeout -> {}                       // no packet for 10 s
        is PftpResult.Cancelled -> {}                  // disconnect, dispose, framing error, MTU
    }
}
client.checkSyncGate { gate -> /* Ready, ExerciseRunning or Busy(code) */ }
client.requestSynchronization { result -> }           // opt-in, see above
client.readBatteryLevel { percent -> }
```

The Loop's own messages on fb005c52 arrive on
`Listener.onPftpDeviceNotification`, reassembled, outside any request; decode
their params with `decodeExerciseStatusNotification`, `decodeSyncRequired` and
the other `decode*` functions. `Listener.onPftpFrame` reports every incoming
PFTP frame's RFC76 header (status, sequence, next bit, length, error code),
never its payload. Log lines mask Bluetooth addresses to their last byte.

Responses end on their RFC76 headers (LAST or a status-0 frame), never on a
quiet gap. One request is in flight at a time across GETs, queries,
session-control notifications, PMD commands and the battery read, so nothing
is written while a response is still streaming. Callbacks get raw bytes, never
hex strings: format them yourself, off the callback thread, if you need to.

## Install

Via [JitPack](https://jitpack.io/#Adri-Fa/polar-loop-protocol-android):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.Adri-Fa:polar-loop-protocol-android:v1.4.0")
}
```

`v1.4.0` is the latest released tag. v1.2.0 broke the 1.1.x PFTP API, see
[CHANGELOG.md](CHANGELOG.md). Check the
[JitPack build log](https://jitpack.io/#Adri-Fa/polar-loop-protocol-android) if you pin a
different one to confirm it actually built.

## Testing

Decoder fixtures are synthetic but structurally valid: byte-accurate to each
decoder's real wire format, but the values (dates, device ids, health
numbers) are not from any real device or person.

The PFTP response parser also runs over real Loop traffic in
`src/test/resources/captures/`: 332 responses from a Polar Flow sync and 20
from Polar Loop Debugger exports, including a stream cut off mid-file. These
are redacted to their framing. RFC76 headers, packet lengths and status-0
frames are verbatim; payload bytes are replaced by their count, and paths are
dropped. `GattQueueBurstReplayTest` replays 30 more, the burst of overlapping
requests a v1.1.4 build sent, through the request queue. Their paths and
timings are synthetic. Run the suite with:

```
./gradlew test
```
