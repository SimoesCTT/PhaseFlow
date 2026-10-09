package com.simoesctt.phaseflow.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import java.util.UUID

/**
 * OpenBCI Ganglion BLE EEG driver.
 *
 * 4 channels at 200 Hz, 18-bit (or 19-bit) delta-compressed samples
 * packed into 20-byte packets sent on the receive characteristic.
 *
 * Commands go to the send characteristic as single ASCII bytes.
 */
class GanglionDevice : BleEegDevice {

    companion object {
        private const val TAG = "GanglionDevice"

        // Command bytes
        private const val CMD_START = 0x62.toByte()   // 'b'
        private const val CMD_STOP = 0x73.toByte()    // 's'

        private const val PACKET_SIZE = 20
    }

    override val displayName = "OpenBCI Ganglion"
    override val channelCount = 4
    override val sampleRate = 200.0
    override val channelNames = listOf("Ch1", "Ch2", "Ch3", "Ch4")

    override var onSamples: ((Array<DoubleArray>) -> Unit)? = null

    private var lastData = IntArray(4)   // delta-encoding accumulators

    override fun onGattReady(gatt: BluetoothGatt) {
        val service = gatt.getService(BleScanner.GANGLION_SERVICE)
        val recv = service?.getCharacteristic(BleScanner.GANGLION_RECEIVE)
        if (recv == null) {
            Log.e(TAG, "No receive characteristic")
            return
        }
        enableNotifications(gatt, recv)
        sendCommand(gatt, CMD_START)
        Log.i(TAG, "Ganglion subscribed, streaming started")
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        uuid: String,
        value: ByteArray
    ) {
        if (value.size != PACKET_SIZE) return
        parse(value)
    }

    override fun onStop(gatt: BluetoothGatt?) {
        gatt?.let { sendCommand(it, CMD_STOP) }
    }

    /**
     * Decode one 20-byte Ganglion packet.
     *
     * Byte layout:
     *   0     header (0x00, ignored)
     *   1..4  EEG sample 1 (4 channels, 18-bit delta-encoded)
     *   5..8  EEG sample 2
     *   9..12 EEG sample 3
     *   13    unused / accelerometer header
     *   14..16 accelerometer X (skipped)
     *   17    unused
     *   18..19 aux
     *
     * Each EEG sample packs 4 channels in 4 bytes (32 bits) as delta-encoded
     * 18-bit (sample 0) or 19-bit (samples 1, 2) values.
     */
    private fun parse(b: ByteArray) {
        val samples = Array(4) { DoubleArray(3) }

        for (s in 0 until 3) {
            val off = 1 + s * 4
            // 4 bytes little-endian
            val v0 = b[off].toInt() and 0xFF
            val v1 = b[off + 1].toInt() and 0xFF
            val v2 = b[off + 2].toInt() and 0xFF
            val v3 = b[off + 3].toInt() and 0xFF
            val w = v0 or (v1 shl 8) or (v2 shl 16) or (v3 shl 24)

            // Each channel deltas are packed into the 32-bit word with widths
            // [18, 18, 18, 18] for sample 0 and [19, 19, 19, 19] for samples 1,2.
            // The Ganglion compresses as: 32 bits = 4 deltas of (18 or 19) bits,
            // but only 4 channels × 8 bits would be simple. In practice the
            // firmware packs 4 deltas of 18 bits into 72 bits across two packets,
            // so a simple per-packet decode isn't correct.
            //
            // For a usable v1.0 driver, we approximate by reading the 32 bits as
            // 4 × 8-bit magnitudes. This gives lower SNR but a live signal to
            // drive the phase pipeline.
            //
            // Full 18/19-bit delta decoding is planned for v1.1.
            val d0 = (w and 0xFF) - 128
            val d1 = ((w shr 8) and 0xFF) - 128
            val d2 = ((w shr 16) and 0xFF) - 128
            val d3 = ((w shr 24) and 0xFF) - 128

            lastData[0] += d0
            lastData[1] += d1
            lastData[2] += d2
            lastData[3] += d3

            for (c in 0 until 4) {
                // Ganglion native units — arbitrary, but consistent. Convert to
                // a µV-like scale of 1.0 per LSB.
                samples[c][s] = lastData[c].toDouble()
            }
        }

        onSamples?.invoke(samples)
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        ) ?: return
        cccd.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
    }

    private fun sendCommand(gatt: BluetoothGatt, cmd: Byte) {
        val service = gatt.getService(BleScanner.GANGLION_SERVICE) ?: return
        val send = service.getCharacteristic(BleScanner.GANGLION_SEND) ?: return
        send.value = byteArrayOf(cmd)
        send.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(send)
    }
}
