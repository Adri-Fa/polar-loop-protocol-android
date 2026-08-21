package com.polarloopdebugger.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.polarloopdebugger.protocol.ControlPointResponse
import com.polarloopdebugger.protocol.DataFrame
import com.polarloopdebugger.protocol.HrMeasurement
import com.polarloopdebugger.protocol.MeasurementType

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
     * of this shared module depending on any app-specific logger. */
    var rawTraceHook: (kind: String, rawHex: String, path: String?) -> Unit = { _, _, _ -> }

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

    fun sendPftpInitializeSession() = client.sendPftpInitializeSession()
    fun sendPftpStartSync() = client.sendPftpStartSync()
    fun sendPftpStopSyncCompleted() = client.sendPftpStopSyncCompleted()
    fun sendPftpTerminateSession() = client.sendPftpTerminateSession()
    fun sendPftpGet(path: String, expectedSize: Long? = null) = client.sendPftpGet(path, expectedSize)
    fun sendPftpDiskSpaceQuery() = client.sendPftpDiskSpaceQuery()

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

    override fun onControlPointResponse(response: ControlPointResponse, rawHex: String) {
        rawTraceHook("control_point_response", rawHex, null)
        listeners.forEach { it.onControlPointResponse(response, rawHex) }
    }

    override fun onDataFrame(frame: DataFrame, rawHex: String) {
        rawTraceHook("data_notification", rawHex, null)
        listeners.forEach { it.onDataFrame(frame, rawHex) }
    }

    override fun onHrMeasurement(hr: HrMeasurement, rawHex: String) {
        rawTraceHook("hr_notification", rawHex, null)
        listeners.forEach { it.onHrMeasurement(hr, rawHex) }
    }

    override fun onPftpSessionControlSent(rawHex: String) {
        rawTraceHook("pftp_session_control", rawHex, null)
        listeners.forEach { it.onPftpSessionControlSent(rawHex) }
    }

    override fun onPftpRequestSent(path: String, rawHex: String) {
        rawTraceHook("pftp_request", rawHex, path)
        listeners.forEach { it.onPftpRequestSent(path, rawHex) }
    }

    override fun onPftpResponse(path: String, data: ByteArray, rawHex: String) {
        rawTraceHook("pftp_response", rawHex, path)
        listeners.forEach { it.onPftpResponse(path, data, rawHex) }
    }

    override fun onPftpTimeout(path: String) {
        listeners.forEach { it.onPftpTimeout(path) }
    }
}
