package com.simoesctt.phaseflow.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Scans for known EEG BLE devices (Muse, OpenBCI Ganglion).
 */
class BleScanner(
    private val context: Context,
    private val onFound: (name: String, address: String, uuid: String, rssi: Int) -> Unit
) {

    companion object {
        private const val TAG = "BleScanner"

        val MUSE_SERVICE: UUID = UUID.fromString("0000fe8d-0000-1000-8000-00805f9b34fb")
        val GANGLION_SERVICE: UUID = UUID.fromString("0000fe84-0000-1000-8000-00805f9b34fb")

        // Muse Classic characteristics (one per channel)
        val MUSE_EEG_CHARACTERISTICS: List<UUID> = listOf(
            UUID.fromString("273e0003-4c4d-454d-96be-f03bac821358"),
            UUID.fromString("273e0004-4c4d-454d-96be-f03bac821358"),
            UUID.fromString("273e0005-4c4d-454d-96be-f03bac821358"),
            UUID.fromString("273e0006-4c4d-454d-96be-f03bac821358")
        )

        // Muse Athena single multiplexed characteristic
        val MUSE_ATHENA_EEG_CHARACTERISTIC: UUID =
            UUID.fromString("273e0013-4c4d-454d-96be-f03bac821358")

        // Muse control characteristic (writes ASCII commands)
        val MUSE_CONTROL_CHARACTERISTIC: UUID =
            UUID.fromString("273e0001-4c4d-454d-96be-f03bac821358")

        // Ganglion
        val GANGLION_RECEIVE: UUID = UUID.fromString("2d30c082-f39f-4ce6-923f-3484ea480596")
        val GANGLION_SEND: UUID = UUID.fromString("2d30c083-f39f-4ce6-923f-3484ea480596")
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: return
            val matchedUuid = when {
                uuids.contains(MUSE_SERVICE) -> "Muse"
                uuids.contains(GANGLION_SERVICE) -> "Ganglion"
                else -> return
            }
            onFound(name, result.device.address, matchedUuid, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    fun start(timeoutMs: Long = 15000) {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        scanning = true

        handler.postDelayed({
            if (scanning) stop()
        }, timeoutMs)
    }

    fun stop() {
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {}
        scanning = false
    }
}
