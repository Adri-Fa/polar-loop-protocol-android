package com.polarloopdebugger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.polarloopdebugger.protocol.CLIENT_CHARACTERISTIC_CONFIG_UUID
import com.polarloopdebugger.protocol.ControlPointResponse
import com.polarloopdebugger.protocol.DataFrame
import com.polarloopdebugger.protocol.HR_MEASUREMENT_UUID
import com.polarloopdebugger.protocol.HR_SERVICE_UUID
import com.polarloopdebugger.protocol.HrMeasurement
import com.polarloopdebugger.protocol.MeasurementType
import com.polarloopdebugger.protocol.PFTP_EVENT_UUID
import com.polarloopdebugger.protocol.PFTP_REQUEST_RESPONSE_UUID
import com.polarloopdebugger.protocol.PFTP_SESSION_CONTROL_UUID
import com.polarloopdebugger.protocol.PMD_CONTROL_POINT_UUID
import com.polarloopdebugger.protocol.PMD_DATA_UUID
import com.polarloopdebugger.protocol.PMD_SERVICE_UUID
import com.polarloopdebugger.protocol.PmdProtocolException
import com.polarloopdebugger.protocol.REQUIRED_ATT_MTU
import com.polarloopdebugger.protocol.DISK_SPACE_PSEUDO_PATH
import com.polarloopdebugger.protocol.buildDiskSpaceQuery
import com.polarloopdebugger.protocol.buildGetMeasurementSettings
import com.polarloopdebugger.protocol.buildGetRequest
import com.polarloopdebugger.protocol.buildInitializeSession
import com.polarloopdebugger.protocol.buildRequestMeasurementStart
import com.polarloopdebugger.protocol.buildStartSync
import com.polarloopdebugger.protocol.buildStopMeasurement
import com.polarloopdebugger.protocol.buildStopSyncCompleted
import com.polarloopdebugger.protocol.buildTerminateSession
import com.polarloopdebugger.protocol.decodeDataFrame
import com.polarloopdebugger.protocol.parseControlPointResponse
import com.polarloopdebugger.protocol.parseHrMeasurement
import com.polarloopdebugger.protocol.toHex
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Thin plain-AOSP BluetoothGatt layer: owns the connection, MTU/CCCD setup, and a simple
 * single-operation-at-a-time write queue (BluetoothGatt only allows one outstanding GATT
 * operation at a time -- this is the standard workaround). All protocol decoding is delegated to
 * the plain-Kotlin `protocol` package; this class never interprets bytes itself beyond dispatch.
 *
 * ponytail: the op queue is a plain ArrayDeque of lambdas, not a generic command-pattern
 * framework -- this is the whole problem it needs to solve (serialize writes across CCCD setup
 * and PMD commands) and nothing more.
 */
class PolarGattClient(
    private val context: Context,
    private val listener: Listener,
    // BluetoothGattCallback delivery (onCharacteristicChanged, onCharacteristicWrite, etc.)
    // defaults to the caller's main thread when connectGatt gets no Handler -- that forced
    // finishPftpResponse's synchronous hex/string formatting of full PFTP responses onto whatever
    // thread called connect(), with no way for a consumer to opt out (confirmed via a real
    // on-device ANR trace during sync). Default to our own background thread; pass a Handler here
    // to control delivery yourself instead (e.g. to share one thread across GATT clients).
    private val callbackHandler: Handler? = null,
) {
    // Only set while an owned background thread is actually alive -- dispose() nulls this out
    // the instant it quits the thread's Looper, so currentCallbackHandler() never has to guess
    // whether a previously-owned thread is still alive: non-null here means it is.
    private var ownedCallbackThread: HandlerThread? = null

    // Recomputed on every call rather than cached, so a client survives a dispose()-before-any-
    // connect() (e.g. a caller that reuses a single long-lived instance, like ConnectionManager's
    // process-lifetime singleton) instead of being left permanently wired to a dead Looper -- a
    // Handler posted to a quit Looper silently drops every message, no exception, no crash, just
    // BluetoothGattCallback delivery (and pftpHandler) going dark forever after the first dispose().
    private fun currentCallbackHandler(): Handler {
        callbackHandler?.let { return it }
        val thread = ownedCallbackThread ?: HandlerThread("PolarGattClient").apply { start() }.also {
            ownedCallbackThread = it
        }
        return Handler(thread.looper)
    }
    interface Listener {
        fun onLog(message: String)
        fun onConnectionStateChanged(connected: Boolean)
        fun onReadyForCommands()
        fun onControlPointResponse(response: ControlPointResponse, rawHex: String)
        fun onDataFrame(frame: DataFrame, rawHex: String)
        fun onHrMeasurement(hr: HrMeasurement, rawHex: String)

        // Historical Sync (PFTP) callbacks -- default no-ops so MainActivity's existing Listener
        // implementation doesn't need to change at all (source-compatible, per the brief's "don't
        // touch Live Measurement" constraint).
        fun onPftpSessionControlSent(rawHex: String) {}
        fun onPftpRequestSent(path: String, rawHex: String) {}
        fun onPftpResponse(path: String, data: ByteArray, rawHex: String) {}
        fun onPftpTimeout(path: String) {}
    }

    private var gatt: BluetoothGatt? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var hrChar: BluetoothGattCharacteristic? = null
    private var pftpRequestResponseChar: BluetoothGattCharacteristic? = null
    private var pftpSessionControlChar: BluetoothGattCharacteristic? = null

    // Reassembly state for the ONE in-flight PFTP GET (this engine is strictly serial, see
    // sendPftpGet). PROTOCOL.md's "Request/response wire framing" section confirms a length
    // prefix on the REQUEST envelope (`02 [len:u16 LE] ...`) AND that responses begin with that
    // same bare `02` marker byte (see PFTP_RESPONSE_ENVELOPE_MARKER) -- but no equivalent LENGTH
    // field on the response side, so there's no way to know from the bytes alone when a response
    // is done. The capture-analysis reconstruction PROTOCOL.md shows (pairing bytes up to the next
    // request write) isn't something available in real time to the app that's itself deciding when
    // to send that next request.
    //
    // Primary completion signal: when the caller already knows the file's byte size (from a prior
    // directory listing's PbPFtpEntry.size, see sendPftpGet), reassembly finishes the instant that
    // many bytes have arrived (each notification packet's own 1-byte RFC76 header already stripped
    // in handleNotification, so this buffer holds payload only) -- no need to guess from a quiet gap
    // when the answer is already known. The idle-timeout gap
    // (PFTP_IDLE_TIMEOUT_MS) is the fallback, used whenever size isn't known upfront (root paths,
    // and the directory listings themselves) or as a safety net if fewer bytes than expected ever
    // arrive.
    private var pftpPendingPath: String? = null
    private var pftpExpectedSize: Long? = null
    private val pftpResponseBuffer = ByteArrayOutputStream()
    private val pftpHandler: Handler get() = currentCallbackHandler()
    private val pftpFinishResponse = Runnable { finishPftpResponse() }

    private fun finishPftpResponse() {
        pftpHandler.removeCallbacks(pftpFinishResponse)
        val path = pftpPendingPath
        pftpPendingPath = null
        val expectedSize = pftpExpectedSize
        pftpExpectedSize = null
        if (path != null) {
            val data = pftpResponseBuffer.toByteArray()
            pftpResponseBuffer.reset()
            if (data.isEmpty()) {
                listener.onLog("ERROR: PFTP GET $path timed out with no response")
                listener.onPftpTimeout(path)
            } else {
                if (expectedSize != null && data.size.toLong() != expectedSize) {
                    listener.onLog(
                        "WARNING: PFTP response size mismatch for $path -- directory listing said " +
                            "$expectedSize bytes but received ${data.size}; reassembly may be " +
                            "truncated or the listing is stale"
                    )
                }
                listener.onLog("<- PFTP response for $path: ${data.size} bytes")
                listener.onPftpResponse(path, data, data.toHex())
            }
        }
        opDone()
    }

    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    // Posted through the same Handler that delivers GATT callbacks (and therefore calls opDone())
    // so opQueue/opInFlight are only ever touched from that one Looper, regardless of which thread
    // sendPftpGet/sendPftpDiskSpaceQuery/etc. are called from -- callers have no thread affinity
    // (main thread, a coroutine dispatcher, ...) but opDone() only ever fires on the callback
    // thread, and opInFlight is a plain, unsynchronized var with no cross-thread visibility
    // guarantee on its own.
    private fun enqueue(op: () -> Unit) {
        currentCallbackHandler().post {
            opQueue.addLast(op)
            runNext()
        }
    }

    private fun runNext() {
        if (opInFlight) return
        val op = opQueue.removeFirstOrNull() ?: return
        opInFlight = true
        op()
    }

    private fun opDone() {
        opInFlight = false
        runNext()
    }

    @SuppressLint("MissingPermission") // caller (MainActivity) checks BLUETOOTH_CONNECT first
    fun connect(device: BluetoothDevice) {
        listener.onLog("connecting to ${device.address}...")
        // transport=TRANSPORT_AUTO, phy=PHY_LE_1M_MASK are connectGatt's own defaults for the
        // handler-less overload -- passed explicitly here only because the Handler param requires
        // this longer overload, not to change connection behavior.
        gatt = device.connectGatt(
            context, false, gattCallback,
            BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.PHY_LE_1M_MASK, currentCallbackHandler(),
        )
    }

    @SuppressLint("MissingPermission")
    fun dispose() {
        gatt?.close()
        gatt = null
        controlPointChar = null
        dataChar = null
        hrChar = null
        pftpRequestResponseChar = null
        pftpSessionControlChar = null
        // Guarded: pftpHandler's getter lazily starts an owned thread if none exists yet, and a
        // dispose() that runs before any connect() (e.g. a caller tearing down a never-connected
        // client) must not spin one up just to immediately quit it below.
        if (callbackHandler != null || ownedCallbackThread != null) {
            pftpHandler.removeCallbacks(pftpFinishResponse)
            // Same cross-thread gap enqueue() closes: opQueue/opInFlight are only ever safe to
            // touch from the callback Looper, so clear them there too instead of on dispose()'s
            // caller thread. post() never blocks the caller -- even when dispose() itself runs on
            // this very Looper -- so this can't deadlock; quitSafely() below only stops the Looper
            // once already-queued messages (this one included, since post() enqueues synchronously
            // before quitSafely() runs) have been dispatched.
            pftpHandler.post {
                opQueue.clear()
                opInFlight = false
            }
        } else {
            // No handler was ever started (dispose() before any connect()) -- nothing could have
            // touched opQueue/opInFlight from another thread, so clear directly.
            opQueue.clear()
            opInFlight = false
        }
        pftpPendingPath = null
        pftpExpectedSize = null
        pftpResponseBuffer.reset()
        ownedCallbackThread?.quitSafely()
        ownedCallbackThread = null
    }

    fun sendGetMeasurementSettings(type: MeasurementType) = writeControlPoint(
        buildGetMeasurementSettings(type), "GET_MEASUREMENT_SETTINGS ${type.name}"
    )

    fun sendRequestMeasurementStart(type: MeasurementType, settingsTlv: ByteArray) = writeControlPoint(
        buildRequestMeasurementStart(type, settingsTlv), "REQUEST_MEASUREMENT_START ${type.name}"
    )

    fun sendStopMeasurement(type: MeasurementType) = writeControlPoint(
        buildStopMeasurement(type), "STOP_MEASUREMENT ${type.name}"
    )

    // -------------------------------------------------------------------
    // Historical Sync (PFTP)
    // -------------------------------------------------------------------

    fun sendPftpInitializeSession() = writeSessionControl(buildInitializeSession(), "INITIALIZE_SESSION")
    fun sendPftpStartSync() = writeSessionControl(buildStartSync(), "START_SYNC")
    fun sendPftpStopSyncCompleted() = writeSessionControl(buildStopSyncCompleted(), "STOP_SYNC(completed=true)")
    fun sendPftpTerminateSession() = writeSessionControl(buildTerminateSession(), "TERMINATE_SESSION")

    @SuppressLint("MissingPermission")
    private fun writeSessionControl(command: ByteArray, description: String) {
        val g = gatt
        val characteristic = pftpSessionControlChar
        if (g == null || characteristic == null) {
            listener.onLog("ERROR: not connected, cannot send PFTP $description")
            return
        }
        enqueue {
            listener.onLog("-> PFTP $description: ${command.toHex()}")
            listener.onPftpSessionControlSent(command.toHex())
            // fb005c53 is Write-only (no Notify property, PROTOCOL.md's characteristic-roles
            // table) -- it never notifies back, so the write ack below IS this op's completion
            // signal, same as every other write in this class.
            if (!writeCharacteristicCompat(g, characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                listener.onLog("ERROR: failed to write PFTP session control for $description")
                opDone()
            }
        }
    }

    /**
     * Sends one GET request and waits for its response to fully reassemble before this op
     * completes. [expectedSize] is the file's byte size from a prior directory listing's
     * PbPFtpEntry.size (e.g. an AUTOSNNN.BPB discovered while listing /U/0/AUTOS/) -- when known,
     * it's the primary completion signal (see the class-level PFTP reassembly comment); omitted
     * for paths with no such listing (root paths, and the listings themselves), which fall back to
     * the idle-timeout heuristic. Because everything in this class shares one [opQueue], calling
     * this again before the previous GET's response has been dispatched simply queues behind it --
     * the traversal in HistoricalSyncActivity relies on this to stay strictly serial (one GET in
     * flight at a time, per the task brief).
     */
    @SuppressLint("MissingPermission")
    fun sendPftpGet(path: String, expectedSize: Long? = null) {
        val g = gatt
        val characteristic = pftpRequestResponseChar
        if (g == null || characteristic == null) {
            listener.onLog("ERROR: not connected, cannot GET $path")
            listener.onPftpTimeout(path)
            return
        }
        enqueue {
            val request = buildGetRequest(path)
            listener.onLog("-> PFTP GET $path: ${request.toHex()}")
            listener.onPftpRequestSent(path, request.toHex())
            pftpPendingPath = path
            pftpExpectedSize = expectedSize
            pftpResponseBuffer.reset()
            pftpHandler.removeCallbacks(pftpFinishResponse)
            if (!writeCharacteristicCompat(g, characteristic, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                listener.onLog("ERROR: failed to write PFTP GET for $path")
                pftpPendingPath = null
                pftpExpectedSize = null
                listener.onPftpTimeout(path)
                opDone()
                return@enqueue
            }
            // Don't call opDone() here -- onCharacteristicWrite special-cases this characteristic
            // (see below) and defers completion to pftpFinishResponse instead.
            pftpHandler.postDelayed(pftpFinishResponse, PFTP_NO_RESPONSE_TIMEOUT_MS)
        }
    }

    /**
     * Sends the GET_DISK_SPACE query, reusing [sendPftpGet]'s exact op-queue/reassembly/timeout
     * machinery via the [DISK_SPACE_PSEUDO_PATH] sentinel -- there's no real file size to know
     * upfront, so this always falls back to the idle-timeout heuristic, same as a root/listing
     * GET. Folds into whatever PFTP session is already open; does not start a second one.
     */
    @SuppressLint("MissingPermission")
    fun sendPftpDiskSpaceQuery() {
        val g = gatt
        val characteristic = pftpRequestResponseChar
        if (g == null || characteristic == null) {
            listener.onLog("ERROR: not connected, cannot query disk space")
            listener.onPftpTimeout(DISK_SPACE_PSEUDO_PATH)
            return
        }
        enqueue {
            val request = buildDiskSpaceQuery()
            listener.onLog("-> PFTP GET_DISK_SPACE: ${request.toHex()}")
            listener.onPftpRequestSent(DISK_SPACE_PSEUDO_PATH, request.toHex())
            pftpPendingPath = DISK_SPACE_PSEUDO_PATH
            pftpExpectedSize = null
            pftpResponseBuffer.reset()
            pftpHandler.removeCallbacks(pftpFinishResponse)
            if (!writeCharacteristicCompat(g, characteristic, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                listener.onLog("ERROR: failed to write PFTP GET_DISK_SPACE query")
                pftpPendingPath = null
                listener.onPftpTimeout(DISK_SPACE_PSEUDO_PATH)
                opDone()
                return@enqueue
            }
            pftpHandler.postDelayed(pftpFinishResponse, PFTP_NO_RESPONSE_TIMEOUT_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeControlPoint(command: ByteArray, description: String) {
        val g = gatt
        val characteristic = controlPointChar
        if (g == null || characteristic == null) {
            listener.onLog("ERROR: not connected, cannot send $description")
            return
        }
        enqueue {
            listener.onLog("-> $description: ${command.toHex()}")
            if (!writeCharacteristicCompat(g, characteristic, command)) {
                listener.onLog("ERROR: failed to write control point for $description")
                opDone()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableAllNotifications(g: BluetoothGatt) {
        val pmdService = g.getService(UUID.fromString(PMD_SERVICE_UUID))
        val cp = pmdService?.getCharacteristic(UUID.fromString(PMD_CONTROL_POINT_UUID))
        val data = pmdService?.getCharacteristic(UUID.fromString(PMD_DATA_UUID))
        val hrService = g.getService(UUID.fromString(HR_SERVICE_UUID))
        val hr = hrService?.getCharacteristic(UUID.fromString(HR_MEASUREMENT_UUID))

        controlPointChar = cp
        dataChar = data
        hrChar = hr

        if (cp == null) listener.onLog("ERROR: PMD control point characteristic not found")
        if (data == null) listener.onLog("ERROR: PMD data characteristic not found")
        if (hr == null) listener.onLog("ERROR: HR measurement characteristic not found")

        // PFTP (Historical Sync): which service these three characteristics sit under is
        // unconfirmed (PROTOCOL.md Open Question 2, no fresh GATT discovery was ever captured) --
        // so look them up by characteristic UUID across ALL discovered services instead of
        // trusting a guessed parent service UUID, which would silently fail (null) if wrong.
        val pftpRequestResponse = findCharacteristic(g, UUID.fromString(PFTP_REQUEST_RESPONSE_UUID))
        val pftpEvent = findCharacteristic(g, UUID.fromString(PFTP_EVENT_UUID))
        val pftpSessionControl = findCharacteristic(g, UUID.fromString(PFTP_SESSION_CONTROL_UUID))

        pftpRequestResponseChar = pftpRequestResponse
        pftpSessionControlChar = pftpSessionControl

        if (pftpRequestResponse == null) listener.onLog("PFTP request/response characteristic (fb005c51) not found -- Historical Sync unavailable")
        if (pftpSessionControl == null) listener.onLog("PFTP session-control characteristic (fb005c53) not found -- Historical Sync unavailable")

        cp?.let { enqueueEnableCccd(g, it, indicate = true) }
        data?.let { enqueueEnableCccd(g, it, indicate = false) }
        hr?.let { enqueueEnableCccd(g, it, indicate = false) }
        // fb005c51: Notify + Write Without Response (request/response channel).
        pftpRequestResponse?.let { enqueueEnableCccd(g, it, indicate = false) }
        // fb005c52: Notify only, unsolicited/effectively unused -- enabled anyway since it's
        // trivial to (brief: "just log anything seen on it"); see handleNotification.
        // fb005c53 is Write-only (no Notify property) -- no CCCD to enable, it never notifies.
        pftpEvent?.let { enqueueEnableCccd(g, it, indicate = false) }
        enqueue {
            listener.onLog("all notifications/indications enabled, ready for commands")
            listener.onReadyForCommands()
            opDone()
        }
    }

    /** Searches every discovered service for [uuid], not just one assumed parent service. */
    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    @SuppressLint("MissingPermission")
    private fun enqueueEnableCccd(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, indicate: Boolean) {
        enqueue {
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID))
            if (descriptor == null) {
                listener.onLog("ERROR: no CCCD (0x2902) on ${characteristic.uuid}")
                opDone()
                return@enqueue
            }
            val value = if (indicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            if (!writeDescriptorCompat(g, descriptor, value)) {
                listener.onLog("ERROR: failed to write CCCD for ${characteristic.uuid}")
                opDone()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    // writeType defaults to WRITE_TYPE_DEFAULT (existing PMD control-point behavior, unchanged).
    // PFTP call sites pass WRITE_TYPE_NO_RESPONSE explicitly -- PROTOCOL.md documents fb005c51 as
    // "Notify + Write Without Response" and (in this doc's earlier characterization) fb005c53 as
    // "W/WriteWithoutResponse" too.
    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = writeType
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        val hex = value.toHex()
        when (uuid) {
            UUID.fromString(PMD_CONTROL_POINT_UUID) -> {
                try {
                    listener.onControlPointResponse(parseControlPointResponse(value), hex)
                } catch (e: PmdProtocolException) {
                    listener.onLog("ERROR parsing control point response: ${e.message} raw=$hex")
                }
            }
            UUID.fromString(PMD_DATA_UUID) -> {
                try {
                    listener.onDataFrame(decodeDataFrame(value), hex)
                } catch (e: Exception) {
                    listener.onLog("ERROR decoding data frame: ${e.message} raw=$hex")
                }
            }
            UUID.fromString(HR_MEASUREMENT_UUID) -> {
                try {
                    listener.onHrMeasurement(parseHrMeasurement(value), hex)
                } catch (e: Exception) {
                    listener.onLog("ERROR parsing HR measurement: ${e.message} raw=$hex")
                }
            }
            UUID.fromString(PFTP_REQUEST_RESPONSE_UUID) -> {
                if (pftpPendingPath == null) {
                    listener.onLog("PFTP notification with no pending request, ignoring: $hex")
                } else {
                    // Every PFTP notification packet carries its own 1-byte RFC76 framing header
                    // (see BlePsFtpUtils.processRfc76MessageFrameHeader in the official SDK) --
                    // strip it per packet before appending, not once on the whole response.
                    if (value.isNotEmpty()) pftpResponseBuffer.write(value, 1, value.size - 1)
                    val expectedSize = pftpExpectedSize
                    if (expectedSize != null && pftpResponseBuffer.size() >= expectedSize) {
                        // Primary signal: the directory listing already told us the exact byte
                        // count, so there's no need to wait for a quiet gap once we have it all.
                        finishPftpResponse()
                    } else {
                        // Fallback idle-timeout heuristic -- size unknown upfront, or more bytes
                        // are still expected than the listing said.
                        pftpHandler.removeCallbacks(pftpFinishResponse)
                        pftpHandler.postDelayed(pftpFinishResponse, PFTP_IDLE_TIMEOUT_MS)
                    }
                }
            }
            UUID.fromString(PFTP_EVENT_UUID) -> {
                // fb005c52: unsolicited device->app push, effectively unused (PROTOCOL.md: only
                // one notification ever seen, a sleep_recording_state push) -- just log it.
                listener.onLog("PFTP event (fb005c52, unsolicited): $hex")
            }
            else -> listener.onLog("notification from unknown characteristic $uuid: $hex")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            listener.onLog("connection state changed: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onConnectionStateChanged(true)
                    g.requestMtu(REQUIRED_ATT_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onConnectionStateChanged(false)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            listener.onLog("MTU changed: mtu=$mtu status=$status")
            if (mtu < REQUIRED_ATT_MTU) {
                listener.onLog(
                    "WARNING: negotiated MTU $mtu is below the required $REQUIRED_ATT_MTU; " +
                        "REQUEST_MEASUREMENT_START will likely fail with ERROR_INVALID_MTU"
                )
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            listener.onLog("services discovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("ERROR: service discovery failed with GATT status $status")
                return
            }
            enableAllNotifications(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            listener.onLog("descriptor write ${descriptor.characteristic.uuid}: status=$status")
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            listener.onLog("characteristic write ${characteristic.uuid}: status=$status")
            if (characteristic.uuid == UUID.fromString(PFTP_REQUEST_RESPONSE_UUID) && pftpPendingPath != null) {
                // The GET write ack is not this op's completion signal -- wait for the response
                // to reassemble instead (pftpFinishResponse), see sendPftpGet.
                return
            }
            opDone()
        }

        // API 33+ path.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic.uuid, value)
        }

        // Pre-API 33 path -- the framework does not invoke the 3-arg overload above below API 33,
        // so both must be implemented to receive notifications across minSdk 31 - compileSdk 34.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleNotification(characteristic.uuid, characteristic.value ?: return)
        }
    }

    companion object {
        // ponytail: no length-prefixed response envelope is confirmed for fb005c51 responses
        // (only the request side has one, see the class-level PFTP comment) -- a quiet gap is
        // used instead. 400ms comfortably exceeds the back-to-back notification bursts PROTOCOL.md
        // describes for one response (e.g. AUTOS000.BPB's 1081 chunks arriving as sustained
        // ordinary fragmentation, no multi-hundred-ms gaps reported mid-transfer). Bump this if a
        // slower/noisier link ever proves it too tight -- there's no way to verify against real
        // hardware in this sandbox (see PROTOCOL.md's own note that a fresh capture wasn't
        // possible here either).
        private const val PFTP_IDLE_TIMEOUT_MS = 400L

        // No notification at all within this window (e.g. a bad/nonexistent path) is treated as a
        // timeout so the traversal in HistoricalSyncActivity can skip it and move on rather than
        // hang the whole sync forever.
        private const val PFTP_NO_RESPONSE_TIMEOUT_MS = 8000L

    }
}
