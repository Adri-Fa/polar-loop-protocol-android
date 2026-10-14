# Changelog

## v1.3.0

### Added
- `requestSynchronization()` (`PolarGattClient` and `ConnectionManager`): sends
  PbPFtpQuery REQUEST_SYNCHRONIZATION (13) as `02 0D 80`, byte-identical to
  Polar Flow's request in capture9 (frame 424), and delivers one `PftpResult`
  like every other request. It is an explicit opt-in. Polar documents the call
  that sends it as making the device "flush the latest data to files", so
  nothing in the library calls it. `OutgoingFrame.RequestSynchronization` is
  part of the closed frame type and the allow-list. A source tripwire test
  fails if any other code path can send it.

### Changed
- `OutgoingFrameAllowListTest` used a real dated path from a capture as test
  input. It now uses a synthetic one. The v1.2.0 commit still contains the old
  path in git history.

### Known issue (not changed)
- Since v1.2.0, loopCompanion's links end with reason 0x22 (LL response
  timeout) instead of 0x16. Teardown is the same in v1.1.4 and v1.2.0:
  `dispose()` calls `gatt.close()` without `gatt.disconnect()`, and the Android
  stack disconnects the idle link 4 s later. The 0x22 runs closed sooner after
  the Loop's own slow-parameter request, but HCI logs can't show the cause. No
  fix until one is clear.

## v1.2.0

Breaking: the PFTP API changed. See "Changed" below.

### Fixed
- PFTP responses are parsed by their RFC76 frame header on every packet, as
  Polar's SDK does (`BlePsFtpUtils.processRfc76MessageFrameHeader`,
  `BlePsFtpClient.readResponse`). A response ends only on LAST or a status-0
  frame. The 400 ms quiet gap no longer counts as finished: it cut a real
  AUTOS001.BPB transfer short (capture12, 57,558 of 57,710 bytes).
- A sequence-number or next-bit mismatch sends the stream cancel `00 00 00`
  and reports `Cancelled`. A timeout sends the same cancel once the request's
  own write is acked.
- One request is in flight at a time across GETs, queries and session-control
  notifications. Nothing is written to fb005c53 (or anywhere else) while a
  response is still streaming on fb005c51.
- The per-packet no-response timeout is 10 s. It was 8 s to the first packet,
  then a 400 ms quiet gap. See `PolarGattClient.PFTP_PACKET_TIMEOUT_MS` for the
  measurements behind the value and why it isn't the SDK's 90 s.
- GET path length is encoded as a protobuf varint. Paths over 127 bytes used
  to get a corrupt one-byte length.
- Requests longer than the negotiated MTU allows end as `Cancelled` with the
  sizes in the reason, instead of being sent anyway.
- `postDelayed`/`removeCallbacks` share one cached `Handler`. A fresh one per
  call meant `removeCallbacks` never removed anything.
- Hex formatting is off the hot path. Callbacks get raw bytes, and log lines
  never hex-dump responses (the old main-thread ANR).

### Changed
- Every PFTP request takes a callback and delivers exactly one `PftpResult`:
  `Content(bytes)`, `Empty`, `DeviceError(code, name)`, `Timeout`, or
  `Cancelled(reason)`. `Listener.onPftpResponse`/`onPftpTimeout` are replaced
  by `onPftpResult(label, result)`. `sendPftpGet` lost its `expectedSize`
  parameter.
- `Listener` callbacks pass `raw: ByteArray` instead of `rawHex: String`, and
  so does `ConnectionManager.rawTraceHook`.
- The library writes only `OutgoingFrame`s, a closed set.

### Added
- `sendPftpExerciseStatusQuery` (PbPFtpQuery GET_EXERCISE_STATUS = 25),
  `decodeExerciseState`, and `checkSyncGate` returning `Ready`,
  `ExerciseRunning`, or `Busy(code)`.
- `readBatteryLevel` (Battery Level 0x2A19) over the same connection.
- `OutgoingFrameAllowListTest`, an exact per-characteristic allow-list of
  every byte string the library can emit. It replaces the reflection guard.
- `GattQueueStressTest`, a multi-threaded proof of the one-in-flight rule.
- Capture fixtures (redacted) in `src/test/resources/captures/`.

### Removed
- The literal `67 00` "no data" special case (`decodePftpResponse`,
  `PftpDecodeResult`). That stub was error 103 with its RFC76 header stripped,
  and it now arrives as `DeviceError(103, "NO_SUCH_FILE_OR_DIRECTORY")`.

### Not added
- REQUEST_SYNCHRONIZATION (query 13). Polar's only documented effect of the
  call that sends it is that it "will cause the device to flush the latest
  data to files" (polar-ble-sdk `PolarBleApi.sendInitializationAndStartSyncNotifications`,
  `SyncImplementationGuideline.md`), which is a change to stored data.

Earlier releases: see the git tags.
