package com.simoesctt.phaseflow

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase-domain analysis for multi-channel EEG.
 * Same math as PhaseScope, focused on Kuramoto order parameter r.
 */
object CoherenceEngine {

    /**
     * Compute Kuramoto order parameter r over a sliding window.
     * Returns the average r across the window and the timeline.
     */
    fun kuramotoTimeline(
        channels: Array<DoubleArray>,
        sampleRate: Double,
        bandLow: Double,
        bandHigh: Double,
        windowSec: Double = 1.0,
        hopSec: Double = 0.25
    ): Pair<Double, DoubleArray> {
        val n = channels.size
        require(n >= 2)
        val length = channels[0].size

        // Bandpass filter
        val filtered = Array(n) { filter(channels[it], sampleRate, bandLow, bandHigh) }

        // Hilbert → instantaneous phase
        val phases = Array(n) { hilbertPhase(filtered[it]) }

        // Sliding window order parameter
        val winSize = (windowSec * sampleRate).toInt().coerceAtLeast(4)
        val hopSize = (hopSec * sampleRate).toInt().coerceAtLeast(1)
        val nFrames = ((length - winSize) / hopSize) + 1
        val timeline = DoubleArray(nFrames.coerceAtLeast(1))

        for (f in 0 until nFrames) {
            val s = f * hopSize
            val e = s + winSize
            var sumR = 0.0
            for (t in s until e) {
                var reT = 0.0
                var imT = 0.0
                for (i in 0 until n) {
                    reT += cos(phases[i][t])
                    imT += sin(phases[i][t])
                }
                sumR += sqrt(reT * reT + imT * imT) / n
            }
            timeline[f] = if (e > s) sumR / (e - s) else 0.0
        }

        val meanR = if (timeline.isNotEmpty()) timeline.average() else 0.0
        return meanR to timeline
    }

    private fun filter(x: DoubleArray, fs: Double, lo: Double, hi: Double): DoubleArray {
        // Two cascaded biquad sections (4th-order Butterworth bandpass)
        val stages = butter4Bandpass(lo, hi, fs)
        var y = x
        for (s in stages) y = biquad(y, s)
        // Forward-backward for zero phase
        val rev = y.reversedArray()
        var yr = rev
        for (s in stages) yr = biquad(yr, s)
        return yr.reversedArray()
    }

    private fun butter4Bandpass(lo: Double, hi: Double, fs: Double): Array<Biquad> {
        val n = 4
        val wLo = 2.0 * fs * kotlin.math.tan(PI * lo / fs)
        val wHi = 2.0 * fs * kotlin.math.tan(PI * hi / fs)
        val bw = wHi - wLo
        val w0 = kotlin.math.sqrt(wLo * wHi)
        val stages = mutableListOf<Biquad>()
        for (k in 0 until n / 2) {
            val theta = (2.0 * k + 1.0) * PI / (2.0 * n)
            val qButter = 1.0 / (2.0 * kotlin.math.cos(theta))
            val q = (w0 / bw) * qButter
            stages.add(rbjBandpass(lo, hi, fs, q))
        }
        return stages.toTypedArray()
    }

    private fun rbjBandpass(lo: Double, hi: Double, fs: Double, q: Double): Biquad {
        val f0 = kotlin.math.sqrt(lo * hi)
        val w0 = 2.0 * PI * f0 / fs
        val alpha = kotlin.math.sin(w0) / (2.0 * q)
        val cosw = kotlin.math.cos(w0)
        val a0 = 1.0 + alpha
        return Biquad(
            b0 = alpha / a0, b1 = 0.0, b2 = -alpha / a0,
            a1 = -2.0 * cosw / a0, a2 = (1.0 - alpha) / a0
        )
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double,
                              val a1: Double, val a2: Double)

    private fun biquad(x: DoubleArray, c: Biquad): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val out = c.b0 * x[i] + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
            y[i] = out
            x2 = x1; x1 = x[i]
            y2 = y1; y1 = out
        }
        return y
    }

    private fun hilbertPhase(x: DoubleArray): DoubleArray {
        val n = x.size
        val data = DoubleArray(n * 2)
        for (i in 0 until n) data[2 * i] = x[i]
        val fft = DoubleFFT_1D(n.toLong())
        fft.complexForward(data)
        for (k in 1 until n / 2) {
            data[2 * k] *= 2.0
            data[2 * k + 1] *= 2.0
        }
        for (k in n / 2 + 1 until n) {
            data[2 * k] = 0.0
            data[2 * k + 1] = 0.0
        }
        fft.complexInverse(data, false)
        val phase = DoubleArray(n)
        for (i in 0 until n) {
            phase[i] = atan2(data[2 * i + 1], data[2 * i])
        }
        return phase
    }
}
