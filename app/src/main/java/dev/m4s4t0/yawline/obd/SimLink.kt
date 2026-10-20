package dev.m4s4t0.yawline.obd

import dev.m4s4t0.yawline.sensors.SimDrive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay

/**
 * A pretend STN adapter on a CAN car, answering from [SimDrive]. It speaks the same text an OBDLink
 * does (multi-PID replies as ISO-TP frames, trouble codes, a VIN) so the real protocol code runs.
 */
class SimLink : Link {
    override val label = "Simulator"
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> = inbox
    private val buf = StringBuilder()
    private var codes = mutableListOf("P0301", "P0171", "C0035")
    private val supported = intArrayOf(0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1F, 0x24,
        0x2F, 0x33, 0x42, 0x44, 0x46, 0x49, 0x5C)

    override suspend fun open() { SimDrive.acquire() }

    override fun close() {
        SimDrive.release()
        inbox.close()
    }

    override suspend fun write(data: ByteArray) {
        buf.append(String(data))
        while (true) {
            val cr = buf.indexOf("\r")
            if (cr < 0) break
            val cmd = buf.substring(0, cr).trim().uppercase().replace(" ", "")
            buf.delete(0, cr + 1)
            delay(if (cmd.startsWith("AT") || cmd.startsWith("ST")) 5 else 12)
            inbox.trySend((reply(cmd) + "\r\r>").toByteArray())
        }
    }

    private fun reply(cmd: String): String = when {
        cmd == "ATZ" || cmd == "ATWS" -> "\rELM327 v1.4b"
        cmd == "ATI" -> "ELM327 v1.4b"
        cmd == "STI" -> "STN2255 v5.10.3"
        cmd == "ATDPN" -> "A6"
        cmd == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        cmd == "ATRV" -> "%.1fV".format(13.8 + Math.random() * 0.3)
        cmd.startsWith("AT") || cmd.startsWith("ST") -> "OK"
        cmd == "03" -> frames(listOf(0x43, codes.size) + codes.flatMap { dtcBytes(it) })
        cmd == "07" -> frames(listOf(0x47, 1) + dtcBytes("P0420"))
        cmd == "0A" -> frames(listOf(0x4A, 0))
        cmd == "04" -> { codes.clear(); "44" }
        cmd == "0902" -> frames(listOf(0x49, 0x02, 0x01) + "JF1GR8H67AL123456".map { it.code })
        cmd.startsWith("01") -> mode01(cmd.substring(2))
        else -> "NO DATA"
    }

    private fun mode01(rest: String): String {
        // An optional trailing digit is the "expected replies" count; drop it.
        val hex = if (rest.length % 2 == 1) rest.dropLast(1) else rest
        val pids = hex.chunked(2).map { it.toInt(16) }
        val out = mutableListOf(0x41)
        for (p in pids) {
            if (p % 0x20 == 0) {
                val mask = LongArray(1)
                supported.filter { it in (p + 1)..(p + 32) }.forEach { mask[0] = mask[0] or (1L shl (32 - (it - p))) }
                if (p + 0x20 <= 0x5C) mask[0] = mask[0] or 1L
                out += p
                out += listOf((mask[0] shr 24).toInt() and 0xFF, (mask[0] shr 16).toInt() and 0xFF, (mask[0] shr 8).toInt() and 0xFF, mask[0].toInt() and 0xFF)
                continue
            }
            if (p !in supported) continue
            out += p
            out += value(p)
        }
        if (out.size == 1) return "NO DATA"
        return frames(out)
    }

    private fun value(p: Int): List<Int> {
        val s = SimDrive.state
        fun b(v: Double) = v.toInt().coerceIn(0, 255)
        fun w(v: Double) = v.toInt().coerceIn(0, 65535).let { listOf(it shr 8, it and 0xFF) }
        return when (p) {
            0x04 -> listOf(b(s.throttle * 2.55 * 0.9 + 20))
            0x05 -> listOf(b(s.coolant + 40))
            0x06 -> listOf(b(128 + (Math.random() - 0.5) * 8))
            0x07 -> listOf(b(131.0))
            0x0B -> listOf(b(s.mapKpa))
            0x0C -> w(s.rpm * 4)
            0x0D -> listOf(b(s.speed * 3.6))
            0x0E -> listOf(b((14 + s.rpm / 600 + 64) * 2))
            0x0F -> listOf(b(s.iat + 40))
            0x10 -> w(s.rpm * s.mapKpa / 100 * 0.02 * 100)
            0x11 -> listOf(b(s.throttle * 2.55))
            0x1F -> w(s.tMs / 1000.0)
            0x24 -> w((if (s.throttle > 70) 0.82 else 1.0) * 32768) + listOf(0x80, 0x00)
            0x2F -> listOf(b(178.0))
            0x33 -> listOf(99)
            0x42 -> w(13900 + Math.random() * 200)
            0x44 -> w((if (s.throttle > 70) 0.82 else 1.0) * 32768)
            0x46 -> listOf(b(14.0 + 40))
            0x49 -> listOf(b(s.throttle * 2.55))
            0x5C -> listOf(b(s.oil + 40))
            else -> listOf(0)
        }
    }

    private fun dtcBytes(code: String): List<Int> {
        val letter = "PCBU".indexOf(code[0])
        val a = (letter shl 6) or (code[1].digitToInt() shl 4) or code[2].digitToInt(16)
        return listOf(a, code.substring(3).toInt(16))
    }

    /** Bytes as the adapter prints them on CAN: one line if they fit a single frame, else ISO-TP frames. */
    private fun frames(bytes: List<Int>): String {
        fun hex(l: List<Int>) = l.joinToString("") { "%02X".format(it) }
        if (bytes.size <= 7) return hex(bytes)
        val sb = StringBuilder("%03X".format(bytes.size))
        sb.append("\r0:").append(hex(bytes.take(6)))
        var i = 6
        var n = 1
        while (i < bytes.size) {
            sb.append("\r${n % 16}:").append(hex(bytes.subList(i, minOf(i + 7, bytes.size))))
            i += 7; n++
        }
        return sb.toString()
    }
}
