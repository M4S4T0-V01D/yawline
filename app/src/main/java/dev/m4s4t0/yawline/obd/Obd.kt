package dev.m4s4t0.yawline.obd

import android.os.SystemClock
import dev.m4s4t0.yawline.YawApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ObdState {
    data object Off : ObdState
    data class Connecting(val adapter: String, val step: String) : ObdState
    data class Live(val adapter: String, val info: String, val vin: String?, val supported: Set<Int>) : ObdState
    data class Failed(val adapter: String, val reason: String) : ObdState
}

data class Codes(val stored: List<String>, val pending: List<String>, val permanent: List<String>, val readAtMs: Long)

/**
 * The car connection: connects a [Link], then polls every PID the car supports, fastest-changing first
 * (see [Tier]), into [values]. Screens and the recorder only read [values]; nothing else talks to the car
 * except the trouble-code actions, which share the same adapter between poll cycles.
 */
object Obd {
    private val _state = MutableStateFlow<ObdState>(ObdState.Off)
    val state: StateFlow<ObdState> = _state
    private val _values = MutableStateFlow<Map<Int, Double>>(emptyMap())
    /** Latest value of every PID read so far (metric), plus the derived ones like boost and AFR. */
    val values: StateFlow<Map<Int, Double>> = _values
    private val _rate = MutableStateFlow(0f)
    /** PID readings per second, the adapter's real speed. */
    val rate: StateFlow<Float> = _rate

    private var link: Link? = null
    private var elm: Elm? = null
    private var job: Job? = null

    fun connect(l: Link) {
        disconnect()
        link = l
        _state.value = ObdState.Connecting(l.label, "Opening link")
        job = YawApp.scope.launch {
            try {
                l.open()
                val e = Elm(l)
                elm = e
                val info = e.init { step -> _state.value = ObdState.Connecting(l.label, step) }
                _state.value = ObdState.Connecting(l.label, "Reading supported sensors")
                val supported = e.supportedPids()
                val vin = runCatching { e.vin() }.getOrNull()
                _state.value = ObdState.Live(l.label, info, vin, supported)
                poll(e, supported)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = ObdState.Failed(l.label, t.message ?: t.javaClass.simpleName)
                runCatching { l.close() }
                link = null; elm = null
            }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.poll(e: Elm, supported: Set<Int>) {
        val wanted = Pids.ALL.filter { !it.derived && it.code in supported }.sortedBy { it.tier.ordinal }
        if (wanted.isEmpty()) return
        var cycle = 0L
        var count = 0
        var windowStart = SystemClock.elapsedRealtime()
        var misses = 0
        while (isActive) {
            val due = wanted.filter { cycle % it.tier.every == 0L }.map { it.code }
            val got = e.read(due)
            if (got.isEmpty()) {
                if (++misses >= 8) throw java.io.IOException("Car stopped answering")
                delay(250)
            } else {
                misses = 0
                val merged = HashMap(_values.value)
                merged.putAll(got)
                merged.putAll(Pids.derive(merged))
                _values.value = merged
                count += got.size
            }
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 1000) {
                _rate.value = count * 1000f / (now - windowStart)
                count = 0
                windowStart = now
            }
            cycle++
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        runCatching { link?.close() }
        link = null
        elm = null
        _values.value = emptyMap()
        _rate.value = 0f
        _state.value = ObdState.Off
    }

    /** Stored, pending and permanent trouble codes. Null when not connected. */
    suspend fun readCodes(): Codes? {
        val e = elm ?: return null
        return Codes(e.dtcs(0x03), e.dtcs(0x07), e.dtcs(0x0A), System.currentTimeMillis())
    }

    /** Clears codes and the check-engine light (Mode 04). The engine should be off, ignition on. */
    suspend fun clearCodes(): Boolean = elm?.clearDtcs() ?: false

    val isLive get() = _state.value is ObdState.Live
}
