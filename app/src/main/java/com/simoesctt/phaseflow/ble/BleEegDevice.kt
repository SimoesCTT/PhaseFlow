package com.simoesctt.phaseflow.ble

import android.bluetooth.BluetoothGatt

/**
 * Common interface for BLE EEG devices.
 *
 * A driver connects to a specific device, starts streaming, and delivers
 * samples to the callback at the device's native rate.
 */
interface BleEegDevice {

    /** Human-readable name, e.g. "Muse Classic", "Muse Athena", "Ganglion". */
    val displayName: String

    /** Number of EEG channels this device produces. */
    val channelCount: Int

    /** Native sample rate (Hz). */
    val sampleRate: Double

    /** Channel names in order. */
    val channelNames: List<String>

    /**
     * Called when GATT connects and services are ready.
     * The driver should subscribe to the relevant characteristics here.
     */
    fun onGattReady(gatt: BluetoothGatt)

    /**
     * Called when a characteristic value changes.
     * The driver parses the bytes and may invoke [onSamples].
     */
    fun onCharacteristicChanged(gatt: BluetoothGatt, uuid: String, value: ByteArray)

    /**
     * Called when the session should stop.
     */
    fun onStop(gatt: BluetoothGatt?)

    /**
     * Callback invoked with decoded samples.
     * Each entry is a full frame of [channelCount] values (in µV).
     */
    var onSamples: ((samples: Array<DoubleArray>) -> Unit)?
}
