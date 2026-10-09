package com.simoesctt.phaseflow.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import java.util.UUID

/**
 * Muse (Interaxon) BLE EEG driver.
 *
 * Supports:
 *  - Classic (Muse 1/2): 4 channels, one characteristic per channel,
 *    12-bit samples, 12 samples per packet, big-endian.
 *  - Athena (Muse S / 2 with newer firmware): single characteristic,
 *    14-bit little-endian samples, tagged packets.
 *
 * The driver auto-detects which characteristic is present after service discovery.
 */
class MuseDevice : BleEegDevice {

    companion object {
        private const val TAG = "MuseDevice"
        private const val MUSE_CLASSIC_SCALE = 0.48828125    // µV per LSB
        private const val MUSE_ATHENA_SCALE = 0.0885         // µV per LSB
        private const val MUSE_CLASSIC_MID = 2048            // 12-bit midpoint is 2^11
        private const val MUSE_ATHENA_MID = 8192             // 14-bit midpoint is 2^13
    }

    override val displayName = "Muse"
    override val channelCount = 4
    override val sampleRate = 256.0
    override val channelNames = listOf("TP9", "AF7", "AF8", "TP10")

    override var onSamples: ((Array<DoubleArray>) -> Unit)? = null

    private var mode: Mode = Mode.Unknown

    enum class Mode { Unknown, Classic, Athena }

    override fun onGattReady(gatt: BluetoothGatt) {
        // Prefer Athena multiplexed characteristic if present
        val athenaChar = gatt.getService(BleScanner.MUSE_SERVICE)
            ?.getCharacteristic(BleScanner.MUSE_ATHENA_EEG_CHARACTERISTIC)
        if (athenaChar != null) {
            mode = Mode.Athena
            enableNotifications(gatt, athenaChar)
            sendControl(gatt, "p21")   // Athena preset
            sendControl(gatt, "h")     // start streaming
            Log.i(TAG, "Muse Athena mode")
            return
        }

        // Fall back to Classic — 4 separate characteristics
        var subscribed = 0
        for (uuid in BleScanner.MUSE_EEG_CHARACTERISTICS) {
            val c = gatt.getService(BleScanner.MUSE_SERVICE)?.getCharacteristic(uuid)
            if (c != null) {
                enableNotifications(gatt, c)
                subscribed++
            }
        }
        if (subscribed > 0) {
            mode = Mode.Classic
            sendControl(gatt, "d")     // Classic preset (default)
            sendControl(gatt, "h")     // start
            Log.i(TAG, "Muse Classic mode, subscribed to $subscribed channels")
        } else {
            Log.e(TAG, "No Muse EEG characteristics found")
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        uuid: String,
        value: ByteArray
    ) {
        when (mode) {
            Mode.Classic -> parseClassic(uuid, value)
            Mode.Athena -> parseAthena(value)
            Mode.Unknown -> {}
        }
    }

    override fun onStop(gatt: BluetoothGatt?) {
        gatt?.let {
            sendControl(it, "h")   // toggle streaming off
        }
    }

    // ------------------------------------------------------------------
    // Classic parser
    // ------------------------------------------------------------------

    /**
     * Each Classic EEG characteristic emits 20-byte packets:
     *   byte 0-1: timestamp
     *   bytes 2..19: 12 samples of 12-bit values
     *
     * The 12-bit samples are packed two per 3 bytes:
     *   s1 = (b0 << 4) | (b1 >> 4)
     *   s2 = ((b1 & 0x0F) << 8) | b2
     */
    private fun parseClassic(uuid: String, data: ByteArray) {
        if (data.size < 20) return
        val channelIndex = BleScanner.MUSE_EEG_CHARACTERISTICS.indexOf(UUID.fromString(uuid))
        if (channelIndex < 0) return

        val samples = DoubleArray(12)
        var idx = 2   // skip timestamp
        for (i in 0 until 12 step 2) {
            val b0 = data[idx].toInt() and 0xFF
            val b1 = data[idx + 1].toInt() and 0xFF
            val b2 = data[idx + 2].toInt() and 0xFF
            idx += 3
            val s1 = (b0 shl 4) or (b1 shr 4)
            val s2 = ((b1 and 0x0F) shl 8) or b2
            samples[i] = (s1 - MUSE_CLASSIC_MID) * MUSE_CLASSIC_SCALE
            if (i + 1 < 12) {
                samples[i + 1] = (s2 - MUSE_CLASSIC_MID) * MUSE_CLASSIC_SCALE
            }
        }

        // Classic: one channel per characteristic. Deliver one channel at a time.
        // The caller aggregates across all four.
        onSamples?.invoke(Array(1) { samples }).also {
            // Also stash for aggregation
            stashClassic(channelIndex, samples)
        }
    }

    // ------------------------------------------------------------------
    // Athena parser
    // ------------------------------------------------------------------

    /**
     * Athena uses a single characteristic with tagged packets.
     * We only care about tag 0x11 (EEG) and 0x12 (EEG, older firmware).
     * Each EEG payload contains 2 or 4 samples per channel (14-bit LE),
     * interleaved as TP9 AF7 AF8 TP10 TP9 AF7 AF8 TP10 ...
     */
    private fun parseAthena(data: ByteArray) {
        var i = 0
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF
            i++

            // Skip non-EEG tags (0x22 = accelerometer, 0x33 = gyro, 0x44 = battery, etc.)
            if (tag != 0x11 && tag != 0x12) {
                // We don't know the length of every tag; abort parsing this packet.
                // Athena packets typically start with a known header, so skipping is safe
                // by looking at the tag prefix. To keep it simple: bail out.
                return
            }

            // Athena EEG packets: 2 bytes per sample per channel, 4 channels.
            // 0x11 -> 2 samples per channel = 16 bytes
            // 0x12 -> 4 samples per channel = 32 bytes
            val samplesPerChannel = if (tag == 0x11) 2 else 4
            val totalBytes = samplesPerChannel * 4 * 2
            if (i + totalBytes > data.size) return

            val channels = Array(4) { DoubleArray(samplesPerChannel) }
            for (s in 0 until samplesPerChannel) {
                for (c in 0 until 4) {
                    val lo = data[i].toInt() and 0xFF
                    val hi = data[i + 1].toInt() and 0xFF
                    i += 2
                    val raw = (hi shl 8) or lo      // 14-bit LE, but stored in 16 bits
                    channels[c][s] = (raw - MUSE_ATHENA_MID) * MUSE_ATHENA_SCALE
                }
            }

            // Deliver all four channels together
            onSamples?.invoke(channels)
        }
    }

    // ------------------------------------------------------------------
    // Classic channel aggregation
    // ------------------------------------------------------------------

    private val classicBuffers = Array(4) { ArrayList<Double>(12) }

    private fun stashClassic(channelIndex: Int, samples: DoubleArray) {
        if (channelIndex !in 0..3) return
        val buf = classicBuffers[channelIndex]
        for (v in samples) buf.add(v)

        // When all four buffers have 12 samples, emit a frame
        val minSize = classicBuffers.minOf { it.size }
        if (minSize >= 12) {
            val frame = Array(4) { DoubleArray(12) }
            for (c in 0..3) {
                for (i in 0 until 12) {
                    frame[c][i] = classicBuffers[c].removeAt(0)
                }
            }
            onSamples?.invoke(frame)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        ) ?: return
        cccd.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(cccd)
    }

    private fun sendControl(gatt: BluetoothGatt, command: String) {
        val service = gatt.getService(BleScanner.MUSE_SERVICE) ?: return
        val control = service.getCharacteristic(BleScanner.MUSE_CONTROL_CHARACTERISTIC) ?: return
        control.value = command.toByteArray(Charsets.US_ASCII)
        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(control)
    }
}
