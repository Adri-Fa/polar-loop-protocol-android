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
import com.polarloopdebugger.protocol.BATTERY_LEVEL_UUID
import com.polarloopdebugger.protocol.CLIENT_CHARACTERISTIC_CONFIG_UUID
import com.polarloopdebugger.protocol.ControlPointResponse
import com.polarloopdebugger.protocol.DataFrame
import com.polarloopdebugger.protocol.HR_MEASUREMENT_UUID
import com.polarloopdebugger.protocol.HrMeasurement
import com.polarloopdebugger.protocol.MeasurementType
import com.polarloopdebugger.protocol.NotifyTarget
import com.polarloopdebugger.protocol.OutgoingFrame
import com.polarloopdebugger.protocol.PFTP_EVENT_UUID
import com.polarloopdebugger.protocol.PFTP_REQUEST_RESPONSE_UUID
import com.polarloopdebugger.protocol.PFTP_SESSION_CONTROL_UUID
import com.polarloopdebugger.protocol.PMD_CONTROL_POINT_UUID
import com.polarloopdebugger.protocol.PMD_DATA_UUID
import com.polarloopdebugger.protocol.PftpResult
import com.polarloopdebugger.protocol.PmdProtocolException
import com.polarloopdebugger.protocol.REQUIRED_ATT_MTU
import com.polarloopdebugger.protocol.SyncGate
import com.polarloopdebugger.protocol.decodeDataFrame
import com.polarloopdebugger.protocol.parseControlPointResponse
import com.polarloopdebugger.protocol.parseHrMeasurement
import com.polarloopdebugger.protocol.syncGateOf
import com.polarloopdebugger.protocol.toHex
import java.util.UUID

/**
 * Thin plain-AOSP BluetoothGatt layer: owns the connection and MTU setup, and adapts [GattQueue] --
 * the one serial queue every write and read goes through -- to BluetoothGatt. All protocol decoding
 * is delegated to the plain-Kotlin `protocol` package.
 *
 * Every PFTP request (GET, query, session-control notification) delivers exactly one [PftpResult]
 * to the callback passed with it, on the callback thread. Callbacks never get hex strings -- raw
 * bytes only, formatted (if at all) by whoever wants them, on their own thread.
 */
class PolarGattClient(
    private val context: Context,
    private val listener: Listener,
    // BluetoothGattCallback delivery (onCharacteristicChanged, onCharacteristicWrite, etc.)
    // defaults to the caller's main thread when connectGatt gets no Handler -- confirmed via a real
    // on-device ANR trace during sync. Default to our own background thread; pass a Handler here
    // to control delivery yourself instead (e.g. to share one thread across GATT clients).
    private val callbackHandler: Handler? = null,
    // How long a PFTP request may go without a packet before it ends as Timeout. See
    // PFTP_PACKET_TIMEOUT_MS for why the default is 10s and not the SDK's 90s.
    pftpPacketTimeoutMs: Long = PFTP_PACKET_TIMEOUT_MS,
) {
    interface Listener {
        fun onLog(message: String)
        fun onConnectionStateChanged(connected: Boolean)
        fun onReadyForCommands()
        fun onControlPointResponse(response: ControlPointResponse, raw: ByteArray)
        fun onDataFrame(frame: DataFrame, raw: ByteArray)
        fun onHrMeasurement(hr: HrMeasurement, raw: ByteArray)

        // Historical Sync (PFTP) observer taps, default no-ops. A request's result also goes to the
        // callback passed with it -- these let an observer (ConnectionManager's fan-out, a raw-trace
        // log) see every one. [label] is the GET path, or a `$NAME` for queries.
        fun onPftpSessionControlSent(raw: ByteArray) {}
        fun onPftpRequestSent(label: String, raw: ByteArray) {}
        fun onPftpResult(label: String, result: PftpResult) {}
    }

    // Only set while an owned background thread is alive. The Handler is cached with it: every
    // postDelayed/removeCallbacks pair must go through the same Handler instance, since
    // removeCallbacks only removes messages whose target is that exact Handler.
    private var ownedCallbackThread: HandlerThread? = null
    private var ownedHandler: Handler? = null

    // Recreated on demand after dispose(), so a long-lived instance (ConnectionManager's
    // process-lifetime singleton) isn't left wired to a dead Looper, which silently drops messages.
    // Callers hold the lock: see post().
    private fun currentCallbackHandler(): Handler {
        callbackHandler?.let { return it }
        ownedHandler?.let { return it }
        val thread = HandlerThread("PolarGattClient").apply { start() }
        ownedCallbackThread = thread
        return Handler(thread.looper).also { ownedHandler = it }
    }

    // Locked with dispose(), so a post can't land on a Looper that dispose() is quitting.
    @Synchronized
    private fun post(r: Runnable, delayMs: Long = 0) {
        currentCallbackHandler().postDelayed(r, delayMs)
    }

    @Volatile private var gatt: BluetoothGatt? = null

    // Touched only on the callback thread. ATT's default until onMtuChanged reports otherwise.
    private var mtu = DEFAULT_ATT_MTU

    private val queue = GattQueue(
        object : GattQueue.Port {
            override fun post(r: Runnable) = this@PolarGattClient.post(r)
            override fun postDelayed(r: Runnable, delayMs: Long) = this@PolarGattClient.post(r, delayMs)
            // Never starts a thread: after dispose() there's nothing left to remove anyway.
            override fun removeCallbacks(r: Runnable) = synchronized(this@PolarGattClient) {
                (callbackHandler ?: ownedHandler)?.removeCallbacks(r) ?: Unit
            }
            override fun write(frame: OutgoingFrame) = writeFrame(frame)
            override fun read(characteristic: String) = readCharacteristic(characteristic)
            override fun maxWriteSize() = mtu - 3
            override fun log(message: String) = listener.onLog(message)
        },
        pftpPacketTimeoutMs,
    )

    @SuppressLint("MissingPermission") // caller checks BLUETOOTH_CONNECT first
    fun connect(device: BluetoothDevice) {
        listener.onLog("connecting to ${device.address}...")
        val handler = synchronized(this) { currentCallbackHandler() }
        // transport=TRANSPORT_AUTO, phy=PHY_LE_1M_MASK are connectGatt's own defaults for the
        // handler-less overload -- passed explicitly only because the Handler param requires this
        // longer overload, not to change connection behavior.
        gatt = device.connectGatt(
            context, false, gattCallback,
            BluetoothDevice.TRANSPORT_AUTO, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun dispose() {
        gatt?.close()
        gatt = null
        // Nothing can be queued unless a handler was started, so there's nothing to cancel otherwise.
        // quitSafely() below still dispatches this already-posted cancelAll before stopping.
        if (callbackHandler != null || ownedHandler != null) queue.cancelAll("client disposed")
        ownedCallbackThread?.quitSafely()
        ownedCallbackThread = null
        ownedHandler = null
    }

    fun sendGetMeasurementSettings(type: MeasurementType) = queue.write(OutgoingFrame.PmdGetSettings(type)) {}
    fun sendRequestMeasurementStart(type: MeasurementType, settingsTlv: ByteArray) =
        queue.write(OutgoingFrame.PmdStart(type, settingsTlv)) {}
    fun sendStopMeasurement(type: MeasurementType) = queue.write(OutgoingFrame.PmdStop(type)) {}

    /** Battery Level (0x2A19) in percent, or null if the read failed -- over this same connection. */
    fun readBatteryLevel(onResult: (Int?) -> Unit) =
        queue.read(BATTERY_LEVEL_UUID) { value -> onResult(value?.firstOrNull()?.let { it.toInt() and 0xFF }) }

    // -------------------------------------------------------------------
    // Historical Sync (PFTP)
    // -------------------------------------------------------------------

    // Session-control notifications (fb005c53): Empty once written, Cancelled if the write failed.
    fun sendPftpInitializeSession(onResult: (PftpResult) -> Unit = {}) = notify(OutgoingFrame.InitializeSession, onResult)
    fun sendPftpStartSync(onResult: (PftpResult) -> Unit = {}) = notify(OutgoingFrame.StartSync, onResult)
    fun sendPftpStopSyncCompleted(onResult: (PftpResult) -> Unit = {}) = notify(OutgoingFrame.StopSyncCompleted, onResult)
    fun sendPftpTerminateSession(onResult: (PftpResult) -> Unit = {}) = notify(OutgoingFrame.TerminateSession, onResult)

    /** GETs [path]; queues behind any request already in flight. */
    fun sendPftpGet(path: String, onResult: (PftpResult) -> Unit) = request(OutgoingFrame.PftpGet(path), onResult)

    /** GET_DISK_SPACE; decode a Content result with decodeDiskSpaceResult. */
    fun sendPftpDiskSpaceQuery(onResult: (PftpResult) -> Unit) = request(OutgoingFrame.DiskSpaceQuery, onResult)

    /** GET_EXERCISE_STATUS (query 25); decode a Content result with decodeExerciseState. */
    fun sendPftpExerciseStatusQuery(onResult: (PftpResult) -> Unit) = request(OutgoingFrame.ExerciseStatusQuery, onResult)

    /** Asks GET_EXERCISE_STATUS whether a sync can start now. Read-only: it writes nothing but query 25. */
    fun checkSyncGate(onResult: (SyncGate) -> Unit) = sendPftpExerciseStatusQuery { onResult(syncGateOf(it)) }

    private fun request(frame: OutgoingFrame, onResult: (PftpResult) -> Unit) =
        queue.request(frame) { result -> deliver(frame, result, onResult) }

    private fun notify(frame: OutgoingFrame, onResult: (PftpResult) -> Unit) =
        queue.write(frame) { ok ->
            deliver(frame, if (ok) PftpResult.Empty else PftpResult.Cancelled("write of $frame failed"), onResult)
        }

    private fun deliver(frame: OutgoingFrame, result: PftpResult, onResult: (PftpResult) -> Unit) {
        listener.onLog("<- PFTP $frame: $result")
        listener.onPftpResult(frame.toString(), result)
        onResult(result)
    }

    // -------------------------------------------------------------------
    // GattQueue.Port: the only places this class touches the device.
    // -------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun writeFrame(frame: OutgoingFrame): Boolean {
        val g = gatt ?: return false
        val characteristic = findCharacteristic(g, frame.characteristic) ?: run {
            listener.onLog("ERROR: ${frame.characteristic} not found, cannot send $frame")
            return false
        }
        val ok = if (frame is OutgoingFrame.EnableNotifications) {
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD) ?: run {
                listener.onLog("ERROR: no CCCD (0x2902) on ${characteristic.uuid}")
                return false
            }
            writeDescriptorCompat(g, descriptor, frame.bytes)
        } else {
            // PMD control point keeps its write-with-response; fb005c51/53 are Write Without Response.
            val writeType = if (frame.characteristic == PMD_CONTROL_POINT_UUID) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            writeCharacteristicCompat(g, characteristic, frame.bytes, writeType)
        }
        if (ok) {
            listener.onLog("-> $frame")
            when {
                frame is OutgoingFrame.EnableNotifications -> Unit
                frame.characteristic == PFTP_SESSION_CONTROL_UUID -> listener.onPftpSessionControlSent(frame.bytes)
                frame.characteristic == PFTP_REQUEST_RESPONSE_UUID -> listener.onPftpRequestSent(frame.toString(), frame.bytes)
            }
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristic(uuid: String): Boolean {
        val g = gatt ?: return false
        val characteristic = findCharacteristic(g, uuid) ?: return false
        return g.readCharacteristic(characteristic)
    }

    /**
     * Searches every discovered service for [uuid], not just one assumed parent service -- which
     * service fb005c51/52/53 sit under is unconfirmed (PROTOCOL.md Open Question 2), so a guessed
     * parent UUID could silently miss them.
     */
    private fun findCharacteristic(g: BluetoothGatt, uuid: String): BluetoothGattCharacteristic? {
        val id = UUID.fromString(uuid)
        return g.services.firstNotNullOfOrNull { it.getCharacteristic(id) }
    }

    private fun enableAllNotifications(g: BluetoothGatt) {
        for (target in NotifyTarget.entries) {
            if (findCharacteristic(g, target.uuid) == null) {
                listener.onLog("${target.name} characteristic (${target.uuid}) not found")
                continue
            }
            queue.write(OutgoingFrame.EnableNotifications(target)) {}
        }
        if (findCharacteristic(g, PFTP_SESSION_CONTROL_UUID) == null) {
            listener.onLog("PFTP session-control characteristic (fb005c53) not found -- Historical Sync unavailable")
        }
        queue.run {
            listener.onLog("all notifications/indications enabled, ready for commands")
            listener.onReadyForCommands()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
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
        // Hex is only ever built on these error paths, never per packet.
        when (uuid) {
            PMD_CONTROL_POINT -> try {
                listener.onControlPointResponse(parseControlPointResponse(value), value)
            } catch (e: PmdProtocolException) {
                listener.onLog("ERROR parsing control point response: ${e.message} raw=${value.toHex()}")
            }
            PMD_DATA -> try {
                listener.onDataFrame(decodeDataFrame(value), value)
            } catch (e: Exception) {
                listener.onLog("ERROR decoding data frame: ${e.message} raw=${value.toHex()}")
            }
            HR_MEASUREMENT -> try {
                listener.onHrMeasurement(parseHrMeasurement(value), value)
            } catch (e: Exception) {
                listener.onLog("ERROR parsing HR measurement: ${e.message} raw=${value.toHex()}")
            }
            PFTP_REQUEST_RESPONSE -> queue.onPacket(value)
            // fb005c52: unsolicited device->app push, effectively unused (PROTOCOL.md: only one
            // notification ever seen, a sleep_recording_state push) -- just log it.
            PFTP_EVENT -> listener.onLog("PFTP event (fb005c52, unsolicited): ${value.size} bytes")
            else -> listener.onLog("notification from unknown characteristic $uuid: ${value.size} bytes")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            listener.onLog("connection state changed: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mtu = DEFAULT_ATT_MTU
                    listener.onConnectionStateChanged(true)
                    g.requestMtu(REQUIRED_ATT_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.cancelAll("disconnected")
                    listener.onConnectionStateChanged(false)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            listener.onLog("MTU changed: mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) this@PolarGattClient.mtu = mtu
            if (this@PolarGattClient.mtu < REQUIRED_ATT_MTU) {
                listener.onLog(
                    "WARNING: negotiated MTU ${this@PolarGattClient.mtu} is below the required $REQUIRED_ATT_MTU; " +
                        "REQUEST_MEASUREMENT_START will likely fail with ERROR_INVALID_MTU, and PFTP " +
                        "requests longer than ${this@PolarGattClient.mtu - 3} bytes end as Cancelled",
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
            queue.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            queue.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ path.
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            queue.onReadComplete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        // Pre-API 33 path -- see onCharacteristicChanged below for why both exist.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            queue.onReadComplete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
        }

        // API 33+ path.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic.uuid, value)
        }

        // Pre-API 33 path -- the framework does not invoke the 3-arg overload above below API 33,
        // so both must be implemented to receive notifications across minSdk 31 - compileSdk 36.
        // Copied: the queue reads the packet after a thread hop, and this array is the
        // characteristic's own, reused for the next notification.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleNotification(characteristic.uuid, characteristic.value?.copyOf() ?: return)
        }
    }

    companion object {
        /**
         * Per-packet no-response timeout for PFTP requests: the longest a request may wait for its
         * first packet, or for the next one mid-stream. Measured on capture9 (Polar Flow syncing
         * this Loop, 2567 inter-packet gaps): first packet after a GET median 104ms / p95 459ms /
         * max 795ms; slowest response of any kind 1.28s (a directory REMOVE); gap between packets
         * median 2.9ms / max 19.2ms. 10s is ~8x the slowest response and ~500x the largest gap.
         *
         * The SDK uses 90s (BlePsFtpClient.PROTOCOL_TIMEOUT_SECONDS), shared by every Polar device
         * and also used for its write-ack waits. Here a timeout holds the single in-flight slot, so
         * every queued op (PMD commands, the battery read) waits on it -- 90s of a silent-but-
         * connected device would freeze the whole client for 90s. A dropped link doesn't need this
         * timer at all: the BLE supervision timeout disconnects, and disconnect cancels everything.
         */
        const val PFTP_PACKET_TIMEOUT_MS = 10_000L

        private const val DEFAULT_ATT_MTU = 23

        private val CCCD: UUID = UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        private val PMD_CONTROL_POINT: UUID = UUID.fromString(PMD_CONTROL_POINT_UUID)
        private val PMD_DATA: UUID = UUID.fromString(PMD_DATA_UUID)
        private val HR_MEASUREMENT: UUID = UUID.fromString(HR_MEASUREMENT_UUID)
        private val PFTP_REQUEST_RESPONSE: UUID = UUID.fromString(PFTP_REQUEST_RESPONSE_UUID)
        private val PFTP_EVENT: UUID = UUID.fromString(PFTP_EVENT_UUID)
    }
}
