package com.example.limbmotionrecoveryapp.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.dsd.s1.ble.SensorService
import com.dsd.s1.model.SensorConfig
import com.dsd.s1.model.ServiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SensorRepository {

    private const val NOTIFY_CHAR_UUID = "0000ffe4-0000-1000-8000-00805f9a34fb"
    private const val SCAN_TIMEOUT_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 12_000L

    private val MAC_WHITELIST = setOf(
        "D5:17:71:B2:B2:67",
        "C1:18:C7:C3:AA:49",
        "E1:B8:34:05:DE:E9",
        "D9:BC:B5:1E:39:35",
        "D7:27:2D:8F:6A:4C",
        "D2:26:08:77:94:1B"
    )

    enum class State { IDLE, SCANNING, FOUND, CONNECTING, CONNECTED, ERROR }

    data class FoundDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )

    val state = MutableLiveData(State.IDLE)
    val foundDevice = MutableLiveData<FoundDevice?>()
    val errorMessage = MutableLiveData<String?>()

    private var sensorService: SensorService? = null
    private var bleScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var scope: CoroutineScope? = null
    private var monitorJob: Job? = null

    var connectedAddress: String? = null
        private set

    fun startScan(context: Context) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            state.postValue(State.ERROR)
            errorMessage.postValue("Bluetooth is disabled. Enable it and try again.")
            return
        }

        cancelScope()
        foundDevice.postValue(null)
        errorMessage.postValue(null)
        state.postValue(State.SCANNING)

        bleScanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (state.value != State.SCANNING) return
                val device = result.device
                val address = device.address?.uppercase()
                if (address == null || address !in MAC_WHITELIST) return
                val name = try { device.name } catch (_: SecurityException) { null }
                    ?: "WitMotion Sensor"
                val found = FoundDevice(
                    name = name,
                    address = address,
                    rssi = result.rssi
                )
                stopScan()
                foundDevice.postValue(found)
                state.postValue(State.FOUND)
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                state.postValue(State.ERROR)
                errorMessage.postValue("Scan failed (code $errorCode). Check Bluetooth permissions.")
            }
        }
        activeScanCallback = cb

        try {
            bleScanner?.startScan(null, settings, cb)
        } catch (e: SecurityException) {
            state.postValue(State.ERROR)
            errorMessage.postValue("Bluetooth permission denied.")
            return
        }

        scope = CoroutineScope(Dispatchers.IO)
        scope!!.launch {
            delay(SCAN_TIMEOUT_MS)
            if (state.value == State.SCANNING) {
                stopScan()
                withContext(Dispatchers.Main) {
                    state.value = State.ERROR
                    errorMessage.value = "No WitMotion sensor found nearby. Make sure the sensor is on and nearby."
                }
            }
        }
    }

    fun stopScan() {
        try {
            activeScanCallback?.let { bleScanner?.stopScan(it) }
        } catch (_: SecurityException) {}
        activeScanCallback = null
    }

    fun connect(context: Context, address: String) {
        state.postValue(State.CONNECTING)
        errorMessage.postValue(null)

        val service = SensorService(context.applicationContext)
        service.initialize(
            listOf(SensorConfig(address, NOTIFY_CHAR_UUID)),
            ServiceConfig()
        )
        service.startSensors()
        sensorService = service
        connectedAddress = address

        cancelScope()
        scope = CoroutineScope(Dispatchers.IO)
        scope!!.launch {
            val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
            while (isActive) {
                val status = service.getStatus()
                if (status.connectedSensors > 0) {
                    withContext(Dispatchers.Main) { state.value = State.CONNECTED }
                    startMonitor(service)
                    return@launch
                }
                if (System.currentTimeMillis() > deadline) {
                    service.stopSensors()
                    sensorService = null
                    connectedAddress = null
                    withContext(Dispatchers.Main) {
                        state.value = State.ERROR
                        errorMessage.value = "Could not connect to sensor. Make sure it is on and nearby."
                    }
                    return@launch
                }
                delay(500)
            }
        }
    }

    private fun startMonitor(service: SensorService) {
        monitorJob = scope?.launch {
            while (isActive) {
                delay(2_000)
                val status = service.getStatus()
                if (!status.connected && state.value == State.CONNECTED) {
                    withContext(Dispatchers.Main) {
                        state.value = State.IDLE
                    }
                    sensorService = null
                    connectedAddress = null
                    break
                }
            }
        }
    }

    fun disconnect() {
        monitorJob?.cancel()
        cancelScope()
        sensorService?.stopSensors()
        sensorService = null
        connectedAddress = null
        state.postValue(State.IDLE)
        foundDevice.postValue(null)
        errorMessage.postValue(null)
    }

    fun retryFromScan() {
        monitorJob?.cancel()
        cancelScope()
        sensorService?.stopSensors()
        sensorService = null
        connectedAddress = null
        foundDevice.postValue(null)
        errorMessage.postValue(null)
        state.postValue(State.IDLE)
    }

    fun getService(): SensorService? = sensorService

    fun isConnected() = state.value == State.CONNECTED

    private fun cancelScope() {
        scope?.cancel()
        scope = null
    }
}
