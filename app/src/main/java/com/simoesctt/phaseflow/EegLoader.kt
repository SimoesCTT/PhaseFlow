package com.simoesctt.phaseflow

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader

object EegLoader {
    data class Eeg(
        val channels: Array<DoubleArray>,
        val sampleRate: Double,
        val channelNames: List<String>
    )

    fun loadCsv(ctx: Context, uri: Uri): Eeg {
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open file" }
            val reader = BufferedReader(InputStreamReader(input))
            val headerLine = reader.readLine() ?: error("Empty file")
            val header = headerLine.split(",", ";", "\t").map { it.trim() }

            val rows = mutableListOf<List<Double>>()
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val nums = line.split(",", ";", "\t").mapNotNull { it.trim().toDoubleOrNull() }
                    if (nums.size >= 2) rows.add(nums)
                }
                line = reader.readLine()
            }
            require(rows.isNotEmpty()) { "No data rows" }

            val cols = rows.minOf { it.size }
            val firstIsTime = header.firstOrNull()?.lowercase()?.let {
                it.contains("time") || it.contains("sec")
            } ?: false

            val channelStart = if (firstIsTime) 1 else 0
            val nCh = cols - channelStart
            val channelNames = if (firstIsTime) header.drop(1).take(nCh)
                               else header.take(nCh)

            val channels = Array(nCh) { DoubleArray(rows.size) }
            for (r in rows.indices) {
                for (c in 0 until nCh) {
                    channels[c][r] = rows[r][channelStart + c]
                }
            }

            val sampleRate = if (firstIsTime && rows.size > 1) {
                val dt = (rows.last()[0] - rows[0][0]) / (rows.size - 1)
                if (dt > 0) 1.0 / dt else 256.0
            } else 256.0

            return Eeg(channels, sampleRate, channelNames)
        }
    }
}
