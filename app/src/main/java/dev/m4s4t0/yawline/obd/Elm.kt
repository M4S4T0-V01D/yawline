package dev.m4s4t0.yawline.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * One conversation with an ELM327-compatible adapter: setup, then request/reply. STN chips (OBDLink,
 * vLinker) are detected and used the same way; what makes them fast is that they take multi-PID
 * requests and answer without waiting out the bus timeout.
 */
class Elm(private val link: Link) {
    var version = ""; private set
    var stn = ""; private set
    var protocol = ""; private set
    var isCan = false; private set
    /** Ask up to six PIDs per request (CAN only, and only if the adapter copes). */
    var multiPid = false; private set
    private val lock = Mutex()
    private val rx = StringBuilder()

    /** Sends [cmd] and returns the reply lines (echo and prompt removed); empty on timeout. */
    suspend fun send(cmd: String, timeoutMs: Long = 1500): List<String> = lock.withLock {
        // Drop anything left over from an earlier, timed-out reply.
        while (true) { link.input.tryReceive().getOrNull() ?: break }
        rx.setLength(0)
        link.write("$cmd\r".toByteArray())
        val got = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val chunk = link.input.receiveCatching()
                if (chunk.isClosed) throw IOException("Adapter disconnected")
                rx.append(String(chunk.getOrThrow(), Charsets.ISO_8859_1))
                if (rx.indexOf(">") >= 0) break
            }
            true
        }
        if (got == null) return@withLock emptyList()
        ElmParse.lines(rx.toString(), cmd)
    }

    /** Resets and configures the adapter, finds the car's protocol and returns a one-line description. */
    suspend fun init(onStep: (String) -> Unit): String {
        onStep("Resetting adapter")
        version = send("ATZ", 4000).lastOrNull { it.contains("ELM") || it.contains("v") } ?: ""
        if (version.isEmpty()) {
            // Some adapters ignore the first command after connecting.
            version = send("ATZ", 4000).lastOrNull() ?: throw IOException("Adapter not answering")
        }
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) send(c)
        stn = send("STI").firstOrNull()?.takeIf { it.startsWith("STN") } ?: ""
        onStep("Finding the car's protocol")
        val probe = send("0100", 12_000)
        if (ElmParse.isError(probe)) throw IOException("Car not answering — ignition on? (${probe.firstOrNull() ?: "timeout"})")
        val dpn = send("ATDPN").firstOrNull()?.removePrefix("A") ?: ""
        protocol = send("ATDP").firstOrNull()?.removePrefix("AUTO, ") ?: "Protocol $dpn"
        isCan = dpn.firstOrNull() in listOf('6', '7', '8', '9')
        // Talk to the engine ECU only on 11-bit CAN, so one reply comes back and multi-PID stays clean.
        if (dpn == "6" || dpn == "8") send("ATSH7E0")
        if (isCan) {
            val test = ElmParse.mode01(ElmParse.messages(send("010C0D")), listOf(0x0C, 0x0D))
            multiPid = test.size == 2
        }
        return listOfNotNull(stn.ifEmpty { version }, protocol, if (multiPid) "multi-PID" else null).joinToString(" · ")
    }

    suspend fun supportedPids(): Set<Int> {
        val out = HashSet<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val s = ElmParse.supported(ElmParse.messages(send("01%02X".format(base), 3000)))
            if (s.isEmpty()) break
            out += s
            if ((base + 0x20) !in s) break
            base += 0x20
        }
        return out
    }

    /**
     * Reads [pids] in as few requests as the adapter allows. On single-frame replies a trailing "1"
     * tells the adapter to stop listening after the first answer instead of waiting for the bus timeout.
     */
    suspend fun read(pids: List<Int>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        val groups = if (multiPid) pids.chunked(6) else pids.map { listOf(it) }
        for (g in groups) {
            val replyBytes = 1 + g.sumOf { 1 + (Pids[it]?.bytes ?: 4) }
            val suffix = if (isCan && replyBytes <= 7) "1" else ""
            val cmd = "01" + g.joinToString("") { "%02X".format(it) } + suffix
            out += ElmParse.mode01(ElmParse.messages(send(cmd, 800)), g)
        }
        return out
    }

    suspend fun dtcs(mode: Int): List<String> {
        val lines = send("%02X".format(mode), 5000)
        if (ElmParse.isError(lines)) return emptyList()
        return ElmParse.dtcs(ElmParse.messages(lines), mode, isCan)
    }

    suspend fun clearDtcs(): Boolean = send("04", 5000).any { it.startsWith("44") }

    suspend fun vin(): String? = ElmParse.vin(ElmParse.messages(send("0902", 5000)))

    suspend fun batteryVolts(): Double? = send("ATRV").firstOrNull()?.removeSuffix("V")?.toDoubleOrNull()
}
