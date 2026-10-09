package com.simoesctt.phaseflow.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Manages a GATT connection to an EEG device and dispatches to the correct driver.
 */
class BleEegConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val kind: Kind,   // "Muse" or "Ganglion"
    private val onStateChange: (state: State) -> Unit,
    private val onSamples: (Array<DoubleArray>) -> Unit
) {

    enum class Kind { Muse, Ganglion }

    enum class State { Connecting, Connected, Streaming, Disconnected, Failed }

    private var gatt: BluetoothGatt? = null
    private var driver: BleEegDevice? = when (kind) {
        Kind.Muse -> MuseDevice()
        Kind.Ganglion -> GanglionDevice()
    }

    init {
        driver?.onSamples = onSamples
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i("BleEegConnection", "state: $newState status: $status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStateChange(State.Connected)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onStateChange(State.Disconnected)
                    g.close()
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStateChange(State.Failed)
                return
            }
            driver?.onGattReady(g)
            onStateChange(State.Streaming)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            driver?.onCharacteristicChanged(
                g,
                characteristic.uuid.toString(),
                value
            )
        }

        // Legacy signature (API 32 and below)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            driver?.onCharacteristicChanged(g, characteristic.uuid.toString(), value)
        }
    }

    fun connect() {
        onStateChange(State.Connecting)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        driver?.onStop(gatt)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        onStateChange(State.Disconnected)
    }
}
