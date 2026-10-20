# Changelog

## v1.4.0

### Changed
- Read-only, except deletion of day folders and 24/7 files, only when the app
  explicitly requests it. `PolarGattClient.sendPftpRemove(target)` (and
  `ConnectionManager.sendPftpRemove`) sends a PFTP REMOVE: command `08 03`,
  framed exactly like a GET, byte-identical in its header to Polar Flow's own
  REMOVEs in capture9 (a day folder, frame 5680; a 24/7 file, frame 5091). The
  Loop answers with a status-0 frame, delivered as `PftpResult.Empty`; a 103
  means the item was not there. Same one-in-flight queue and typed result as
  every other request.
- `PftpRemoveTarget`: the only input to a REMOVE. `PftpRemoveTarget.of(path)`
  returns a `DayFolder` for `/U/0/YYYYMMDD/` with a real calendar date, an
  `AutosFile` for `/U/0/AUTOS/AUTOSnnn.BPB` with exactly three digits, and null
  for anything else: `/U/0/S/…`, `/SYS/…`, `DEVICE.BPB`, `USERID.BPB`, `/U/0/`,
  `/`, `..`, lower case, trailing characters, 20261340. There is no REMOVE from
  a path string.
- `OutgoingFrame.PftpRemove(target)` joins the closed frame set.
  `OutgoingFrameAllowListTest` pins its bytes, proves PUT and MERGE still
  cannot be built and RESET still cannot be sent, rejects thousands of random
  and mutated paths, and has a source tripwire: the library never calls REMOVE
  on its own.

## v1.3.1

### Fixed
- Every PFTP request has its own no-response timer. A timer acts only while
  its request is current and is still that request's live timer, and it is
  cancelled when the request completes. v1.3.0 shared one timer `Runnable`
  across requests, so only a working `removeCallbacks` kept a stale timer
  from timing out a later request early and cancelling its stream. Replaying
  loopCompanion's v1.1.4 burst with `removeCallbacks` as a no-op, v1.3.0 timed
  out 19 of 30 requests early. It still never wrote while a response streamed.
  Now all 30 get their own answer.
- Log lines no longer carry a full Bluetooth address. `connect()` logged
  "connecting to <address>", and a Loop's address ends in part of its device
  ID. Every line `PolarGattClient` and its queue log now goes through one
  choke point that masks any address to `XX:XX:XX:XX:XX:<last byte>`.

### Added
- `Listener.onPftpDeviceNotification(PftpDeviceNotification)`: the Loop's own
  messages on fb005c52, reassembled from RFC76 frames as polar-ble-sdk's
  `waitForNotification` does. They have an `id`, a `name`
  (`PftpDeviceNotificationId`, pftp_notification.proto), protobuf `params`
  and `receivedAtMillis`. `ConnectionManager` fans them out and calls
  `rawTraceHook("pftp_device_notification", params, name)`. They never enter
  the request queue, so they can't end or extend a response in flight.
  Params decoders: `decodeFilesystemModified`, `decodeTrainingSessionStatus`,
  `decodeSyncRequired`, `decodeAutoSyncStatus`,
  `decodeExerciseStatusNotification`.
- `Listener.onPftpFrame(PftpFrameTrace)`: every incoming fb005c51/fb005c52
  frame's RFC76 header (status, sequence, next bit, length, error code),
  never its payload.
- One log line per characteristic before its CCCD write:
  `CCCD <uuid> handle=0xNNNN value=01 00` (`02 00` for the PMD control
  point's indications). The handle is the characteristic's `instanceId`, so
  a wire trace can match the CCCD write (usually handle N+1) to it.
- `GattQueueBurstReplayTest`: loopCompanion's v1.1.4 burst
  (`captures/lc-v114-burst.txt`: framing only, synthetic paths, synthetic
  timings) replayed through the queue. One request in flight, no cut-offs,
  one result per request, also with a leaky `removeCallbacks`. Also new:
  `CccdLogLineTest`, `LogAddressMaskingTest` (scans every log call in
  src/main), `PftpFrameTraceTest`, `PftpDeviceNotificationTest`, and
  `GattQueueTest` pins for late timers and for error frames with nothing in
  flight (logged, dropped, never the next request's answer).

### Changed
- A complete fb005c52 message logs `<- fb005c52 PftpDeviceNotification(<id>
  <NAME>, <n> B)` instead of `PFTP event (fb005c52, unsolicited): <n> bytes`
  per frame.

### Known limit (not changed)
- A device that sends more answers than requests: a stray status-0 frame that
  arrives while the next request is in flight is taken as that request's
  answer, since PFTP answers carry no request id. With nothing in flight it is
  dropped. `GattQueueBurstReplayTest` documents this.

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
