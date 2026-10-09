package com.simoesctt.phaseflow

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simoesctt.phaseflow.ble.BleEegConnection
import com.simoesctt.phaseflow.ble.BleScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtR: TextView
    private lateinit var txtBaseline: TextView
    private lateinit var txtDevices: TextView
    private lateinit var rBar: RBarView
    private lateinit var btnScan: Button
    private lateinit var btnStop: Button

    private var scanner: BleScanner? = null
    private var connection: BleEegConnection? = null
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private var rJob: Job? = null

    private val recentSamples = Array(4) { ArrayDeque<Double>() }
    private var sampleRate = 256.0
    private var baseline = -1.0
    private var currentR = 0.0
    private var channelsInUse = 4

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (granted) startScan()
        else Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtR = findViewById(R.id.txtR)
        txtBaseline = findViewById(R.id.txtBaseline)
        txtDevices = findViewById(R.id.txtDevices)
        rBar = findViewById(R.id.rBar)
        btnScan = findViewById(R.id.btnScan)
        btnStop = findViewById(R.id.btnStop)

        btnScan.setOnClickListener { requestPermissionsAndScan() }
        btnStop.setOnClickListener { stopAll() }

        txtStatus.text = "Tap Scan to find an EEG device"
    }

    private fun requestPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and below: BLE scanning requires runtime location permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isEmpty()) startScan()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startScan() {
        txtStatus.text = "Scanning for Muse / Ganglion (15 s)…"
        txtDevices.text = ""
        btnScan.isEnabled = false

        val found = mutableListOf<String>()
        val handler = Handler(Looper.getMainLooper())

        scanner = BleScanner(this) { name, address, uuid, rssi ->
            if (found.none { it.contains(address) }) {
                found.add("$name  $address  ($uuid, ${rssi} dBm)")
                txtDevices.text = found.joinToString("\n")
                // Auto-connect to the first device found
                if (connection == null) {
                    connectTo(address, uuid)
                }
            }
        }
        scanner?.start(timeoutMs = 15000)

        handler.postDelayed({
            btnScan.isEnabled = true
            if (connection == null) {
                txtStatus.text = "No device found. Ensure it's powered on and in range."
            }
        }, 16000)
    }

    private fun connectTo(address: String, kind: String) {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = btManager.adapter ?: return
        val device: BluetoothDevice = adapter.getRemoteDevice(address)

        val kindEnum = if (kind == "Muse") BleEegConnection.Kind.Muse
                       else BleEegConnection.Kind.Ganglion

        txtStatus.text = "Connecting to $address…"

        connection = BleEegConnection(
            context = this,
            device = device,
            kind = kindEnum,
            onStateChange = { state ->
                runOnUiThread {
                    txtStatus.text = "State: $state"
                    when (state) {
                        BleEegConnection.State.Streaming -> {
                            startAudio()
                            startRComputation()
                            btnStop.isEnabled = true
                        }
                        BleEegConnection.State.Failed,
                        BleEegConnection.State.Disconnected -> {
                            stopAudio()
                            rJob?.cancel()
                        }
                        else -> {}
                    }
                }
            },
            onSamples = { samples ->
                synchronized(recentSamples) {
                    for (c in 0 until minOf(samples.size, recentSamples.size)) {
                        val buf = recentSamples[c]
                        for (v in samples[c]) {
                            buf.addLast(v)
                            if (buf.size > 512) buf.removeFirst()
                        }
                    }
                }
            }
        )
        connection?.connect()
    }

    private fun startRComputation() {
        rJob?.cancel()
        rJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val window: Array<DoubleArray>
                synchronized(recentSamples) {
                    val minSize = recentSamples.minOf { it.size }
                    if (minSize < 256) {
                        window = emptyArray()
                    } else {
                        window = Array(channelsInUse) { c ->
                            val buf = recentSamples[c]
                            val n = minOf(512, buf.size)
                            buf.toList().takeLast(n).toDoubleArray()
                        }
                    }
                }
                if (window.isNotEmpty()) {
                    val (meanR, _) = CoherenceEngine.kuramotoTimeline(
                        window, sampleRate, 8.0, 12.0, windowSec = 1.0, hopSec = 1.0
                    )
                    currentR = meanR
                    runOnUiThread {
                        txtR.text = "%.3f".format(meanR)
                        rBar.value = meanR
                        if (baseline < 0) {
                            baseline = meanR
                            txtBaseline.text = "baseline r = ${"%.3f".format(baseline)}"
                            rBar.target = baseline
                        }
                    }
                }
                delay(250)
            }
        }
    }

    private fun startAudio() {
        val sr = 44100
        val bufSize = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sr, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize, AudioTrack.MODE_STREAM
        ).apply { play() }

        audioJob = CoroutineScope(Dispatchers.Default).launch {
            val buf = ShortArray(1024)
            var phase = 0.0
            while (isActive) {
                val freq = 200.0 + currentR * 600.0
                val inc = 2.0 * Math.PI * freq / sr
                for (i in buf.indices) {
                    buf[i] = (sin(phase) * 4000).toInt().toShort()
                    phase += inc
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI
                }
                audioTrack?.write(buf, 0, buf.size)
            }
        }
    }

    private fun stopAudio() {
        audioJob?.cancel()
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun stopAll() {
        rJob?.cancel()
        stopAudio()
        connection?.disconnect()
        connection = null
        btnStop.isEnabled = false
        btnScan.isEnabled = true
        txtStatus.text = "Stopped."
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
    }
}
