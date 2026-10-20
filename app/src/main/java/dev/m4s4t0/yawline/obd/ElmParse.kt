package dev.m4s4t0.yawline.obd

/**
 * Turning ELM327/STN text replies into bytes and values. Pure functions, so the tests can feed them the
 * exact text real adapters send (echo, "SEARCHING...", ISO-TP multi-frame "0:", "1:" lines and all).
 */
object ElmParse {
    private val NOISE = listOf("SEARCHING", "BUS INIT")
    private val ERRORS = listOf("NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "STOPPED",
        "ERROR", "BUFFER FULL", "FB ERROR", "DATA ERROR", "LV RESET", "ACT ALERT", "<RX ERROR")

    /** The reply's lines without the echo, the prompt, blank lines and "SEARCHING...". */
    fun lines(raw: String, cmd: String): List<String> =
        raw.replace(">", "").split('\r', '\n')
            .map { it.trim() }
            .filter { l -> l.isNotEmpty() && !l.equals(cmd, ignoreCase = true) && NOISE.none { l.startsWith(it) } }

    fun isError(lines: List<String>): Boolean =
        lines.isEmpty() || lines.any { l -> l == "?" || ERRORS.any { l.uppercase().contains(it) } }

    private fun hexBytes(s: String): IntArray? {
        val h = s.replace(" ", "")
        if (h.isEmpty() || h.length % 2 != 0 || !h.all { it.isHexDigit() }) return null
        return IntArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    /**
     * The reply as messages of bytes. An ISO-TP multi-frame reply ("00A", "0:...", "1:...") becomes one
     * message trimmed to its stated length; otherwise each line is its own message (one per ECU or frame).
     */
    fun messages(lines: List<String>): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].replace(" ", "")
            val isLength = l.length == 3 && l.all { it.isHexDigit() } && i + 1 < lines.size && lines[i + 1].replace(" ", "").startsWith("0:")
            if (isLength) {
                val len = l.toInt(16)
                val buf = ArrayList<Int>(len)
                i++
                while (i < lines.size) {
                    val f = lines[i].replace(" ", "")
                    val colon = f.indexOf(':')
                    if (colon !in 1..2) break
                    hexBytes(f.substring(colon + 1))?.forEach { buf.add(it) }
                    i++
                }
                out.add(buf.take(len).toIntArray())
            } else {
                hexBytes(l)?.let { out.add(it) }
                i++
            }
        }
        return out
    }

    /** Values for the [requested] PIDs out of a Mode 01 reply; single- and multi-PID replies alike. */
    fun mode01(msgs: List<IntArray>, requested: Collection<Int>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        for (m in msgs) {
            if (m.isEmpty() || m[0] != 0x41) continue
            var i = 1
            while (i < m.size) {
                val code = m[i]
                val p = Pids[code] ?: break
                if (i + p.bytes >= m.size) break
                if (code in requested && code !in out) {
                    val data = IntArray(p.bytes) { m[i + 1 + it] }
                    out[code] = p.decode(data)
                }
                i += 1 + p.bytes
            }
        }
        return out
    }

    /**
     * The PIDs a "01 00/20/40..." support query says the car has. Each reply is 41, the base PID, then
     * four bitmap bytes where the top bit of the first is base+1.
     */
    fun supported(msgs: List<IntArray>): Set<Int> {
        val out = HashSet<Int>()
        for (m in msgs) {
            if (m.size < 6 || m[0] != 0x41) continue
            val base = m[1]
            for (b in 0 until 4) for (bit in 0 until 8) {
                if (m[2 + b] and (0x80 shr bit) != 0) out.add(base + b * 8 + bit + 1)
            }
        }
        return out
    }

    /** "P0301" from the two bytes of a stored trouble code; null for the 0000 padding. */
    fun dtc(a: Int, b: Int): String? {
        if (a == 0 && b == 0) return null
        val letter = "PCBU"[a shr 6]
        return "%c%d%X%02X".format(letter, (a shr 4) and 0x3, a and 0xF, b)
    }

    /**
     * Trouble codes from a Mode 03/07/0A reply. CAN puts a count after the 43/47/4A byte; the older
     * protocols send fixed frames of three codes padded with zeros.
     */
    fun dtcs(msgs: List<IntArray>, mode: Int, isCan: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        val head = mode + 0x40
        for (m in msgs) {
            if (m.isEmpty() || m[0] != head) continue
            var i = if (isCan) 2 else 1
            while (i + 1 < m.size) {
                dtc(m[i], m[i + 1])?.let { out.add(it) }
                i += 2
            }
        }
        return out.toList()
    }

    /** The VIN from a Mode 09 PID 02 reply, in either the CAN (one ISO-TP message) or the older format. */
    fun vin(msgs: List<IntArray>): String? {
        val chars = StringBuilder()
        for (m in msgs) {
            if (m.size < 3 || m[0] != 0x49 || m[1] != 0x02) continue
            for (k in 3 until m.size) {
                val c = m[k]
                if (c in 0x30..0x5A) chars.append(c.toChar())
            }
        }
        val s = chars.toString()
        return if (s.length >= 17) s.takeLast(17) else s.ifEmpty { null }
    }
}
