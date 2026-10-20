package dev.m4s4t0.yawline.sensors

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the RaceBox Mini / Mini S / Micro data stream (25 Hz GNSS over BLE, Nordic UART service).
 * Packets are UBX-framed: B5 62, class FF, id 01, little-endian length 80, the payload, then a
 * two-byte Fletcher checksum. Field offsets follow RaceBox's published protocol description.
 */
class RaceBoxParser {
    data class Fix(
        val fixType: Int, val sats: Int,
        val lat: Double, val lon: Double, val altM: Double,
        val hAccM: Double, val speedMps: Double, val headingDeg: Double,
        val battery: Int,
    )

    private val buf = java.io.ByteArrayOutputStream()

    /** Feeds bytes as they arrive; returns the fixes completed by them. */
    fun feed(bytes: ByteArray): List<Fix> {
        buf.write(bytes)
        val data = buf.toByteArray()
        val out = ArrayList<Fix>()
        var i = 0
        while (i + 8 <= data.size) {
            if (data[i] != 0xB5.toByte() || data[i + 1] != 0x62.toByte()) { i++; continue }
            val len = (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8)
            if (len > 512) { i++; continue }
            val end = i + 6 + len + 2
            if (end > data.size) break
            var a = 0; var b = 0
            for (k in i + 2 until i + 6 + len) { a = (a + (data[k].toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF }
            val ok = a == (data[end - 2].toInt() and 0xFF) && b == (data[end - 1].toInt() and 0xFF)
            if (ok && data[i + 2] == 0xFF.toByte() && data[i + 3] == 0x01.toByte() && len >= 80) {
                out.add(decode(ByteBuffer.wrap(data, i + 6, len).slice().order(ByteOrder.LITTLE_ENDIAN)))
            }
            i = if (ok) end else i + 1
        }
        buf.reset()
        if (i < data.size) buf.write(data, i, data.size - i)
        return out
    }

    private fun decode(p: ByteBuffer) = Fix(
        fixType = p.get(20).toInt() and 0xFF,
        sats = p.get(23).toInt() and 0xFF,
        lon = p.getInt(24) / 1e7,
        lat = p.getInt(28) / 1e7,
        altM = p.getInt(36) / 1000.0,
        hAccM = (p.getInt(40).toLong() and 0xFFFFFFFFL) / 1000.0,
        speedMps = p.getInt(48) / 1000.0,
        headingDeg = p.getInt(52) / 1e5,
        battery = p.get(67).toInt() and 0x7F,
    )
}
