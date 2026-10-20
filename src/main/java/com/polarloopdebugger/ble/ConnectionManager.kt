package com.polarloopdebugger.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.polarloopdebugger.protocol.ControlPointResponse
import com.polarloopdebugger.protocol.DataFrame
import com.polarloopdebugger.protocol.HrMeasurement
import com.polarloopdebugger.protocol.MeasurementType
import com.polarloopdebugger.protocol.PftpDeviceNotification
import com.polarloopdebugger.protocol.PftpFrameTrace
import com.polarloopdebugger.protocol.PftpRemoveTarget
import com.polarloopdebugger.protocol.PftpResult
import com.polarloopdebugger.protocol.SyncGate

/**
 * App-level singleton owning the one BLE connection to the Loop. Home is the only screen that
 * scans/connects/disconnects; Live Measurement, Historical Sync, and Workout each just register
 * as a [PolarGattClient.Listener] to observe whatever connection already exists, instead of
 * opening their own (previously: each of those three created its own PolarGattClient and
 * reconnected independently -- see the Noir rebrand brief's connection-architecture assessment).
 *
 * ponytail: a plain singleton object, not a bound Service -- the connection still dies if the
 * process is backgrounded/killed outside of a workout, same risk profile Live Measurement and
 * Historical Sync already had before this change (both foreground-only flows). Workout mode's
 * existing foreground service is unaffected: it still keeps the process alive during a workout,
 * it just no longer owns a second PolarGattClient to do it.
 */
object ConnectionManager : PolarGattClient.Listener {
    private lateinit var appContext: Context
    private var initialized = false
    private val listeners = mutableSetOf<PolarGattClient.Listener>()

    private val client: PolarGattClient by lazy { PolarGattClient(appContext, this) }

    var connectedDevice: BluetoothDevice? = null
        private set
    var isConnected: Boolean = false
        private set
    var isReadyForCommands: Boolean = false
        private set

    /** Optional raw-trace hook: this module knows nothing about logging concerns (e.g. the
     * debugger app's RawTraceLog/Logs screen) -- an app that wants one wires it up here instead
     * of this shared module depending on any app-specific logger. Gets raw bytes on the GATT
     * callback thread: format them (e.g. toHex()) on your own thread, not inline here. */
    var rawTraceHook: (kind: String, raw: ByteArray, path: String?) -> Unit = { _, _, _ -> }

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
    }

    /**
     * Replays current state to a newly-registered listener: a screen opened after the device is
     * already connected (the normal case -- Home connects, then the user navigates to a mode)
     * would otherwise never see [PolarGattClient.Listener.onConnectionStateChanged] /
     * [PolarGattClient.Listener.onReadyForCommands], since those only fire on the transition.
     */
    fun addListener(listener: PolarGattClient.Listener) {
        listeners.add(listener)
        if (isConnected) listener.onConnectionStateChanged(true)
        if (isReadyForCommands) listener.onReadyForCommands()
    }

    fun removeListener(listener: PolarGattClient.Listener) {
        listeners.remove(listener)
    }

    fun connect(device: BluetoothDevice) {
        connectedDevice = device
        client.connect(device)
    }

    fun disconnect() {
        client.dispose()
        isConnected = false
        isReadyForCommands = false
        connectedDevice = null
    }

    fun sendGetMeasurementSettings(type: MeasurementType) = client.sendGetMeasurementSettings(type)
    fun sendRequestMeasurementStart(type: MeasurementType, settingsTlv: ByteArray) =
        client.sendRequestMeasurementStart(type, settingsTlv)
    fun sendStopMeasurement(type: MeasurementType) = client.sendStopMeasurement(type)

    fun readBatteryLevel(onResult: (Int?) -> Unit) = client.readBatteryLevel(onResult)

    fun sendPftpInitializeSession(onResult: (PftpResult) -> Unit = {}) = client.sendPftpInitializeSession(onResult)
    fun sendPftpStartSync(onResult: (PftpResult) -> Unit = {}) = client.sendPftpStartSync(onResult)
    fun sendPftpStopSyncCompleted(onResult: (PftpResult) -> Unit = {}) = client.sendPftpStopSyncCompleted(onResult)
    fun sendPftpTerminateSession(onResult: (PftpResult) -> Unit = {}) = client.sendPftpTerminateSession(onResult)
    fun sendPftpGet(path: String, onResult: (PftpResult) -> Unit) = client.sendPftpGet(path, onResult)
    fun sendPftpDiskSpaceQuery(onResult: (PftpResult) -> Unit) = client.sendPftpDiskSpaceQuery(onResult)
    fun sendPftpExerciseStatusQuery(onResult: (PftpResult) -> Unit) = client.sendPftpExerciseStatusQuery(onResult)
    fun checkSyncGate(onResult: (SyncGate) -> Unit) = client.checkSyncGate(onResult)

    /** Opt-in REQUEST_SYNCHRONIZATION; see PolarGattClient.requestSynchronization. */
    fun requestSynchronization(onResult: (PftpResult) -> Unit) = client.requestSynchronization(onResult)

    /** Opt-in deletion of one day folder or one 24/7 file; see PolarGattClient.sendPftpRemove. */
    fun sendPftpRemove(target: PftpRemoveTarget, onResult: (PftpResult) -> Unit) = client.sendPftpRemove(target, onResult)

    // -- PolarGattClient.Listener: fan out to every registered observer --

    override fun onLog(message: String) {
        listeners.forEach { it.onLog(message) }
    }

    override fun onConnectionStateChanged(connected: Boolean) {
        isConnected = connected
        if (!connected) {
            connectedDevice = null
            isReadyForCommands = false
        }
        listeners.forEach { it.onConnectionStateChanged(connected) }
    }

    override fun onReadyForCommands() {
        isReadyForCommands = true
        listeners.forEach { it.onReadyForCommands() }
    }

    override fun onControlPointResponse(response: ControlPointResponse, raw: ByteArray) {
        rawTraceHook("control_point_response", raw, null)
        listeners.forEach { it.onControlPointResponse(response, raw) }
    }

    override fun onDataFrame(frame: DataFrame, raw: ByteArray) {
        rawTraceHook("data_notification", raw, null)
        listeners.forEach { it.onDataFrame(frame, raw) }
    }

    override fun onHrMeasurement(hr: HrMeasurement, raw: ByteArray) {
        rawTraceHook("hr_notification", raw, null)
        listeners.forEach { it.onHrMeasurement(hr, raw) }
    }

    override fun onPftpSessionControlSent(raw: ByteArray) {
        rawTraceHook("pftp_session_control", raw, null)
        listeners.forEach { it.onPftpSessionControlSent(raw) }
    }

    override fun onPftpRequestSent(label: String, raw: ByteArray) {
        rawTraceHook("pftp_request", raw, label)
        listeners.forEach { it.onPftpRequestSent(label, raw) }
    }

    override fun onPftpResult(label: String, result: PftpResult) {
        if (result is PftpResult.Content) rawTraceHook("pftp_response", result.bytes, label)
        listeners.forEach { it.onPftpResult(label, result) }
    }

    override fun onPftpFrame(frame: PftpFrameTrace) {
        listeners.forEach { it.onPftpFrame(frame) }
    }

    override fun onPftpDeviceNotification(notification: PftpDeviceNotification) {
        rawTraceHook("pftp_device_notification", notification.params, notification.name)
        listeners.forEach { it.onPftpDeviceNotification(notification) }
    }
}
