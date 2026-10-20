package dev.m4s4t0.yawline.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** A byte pipe to an OBD adapter, whatever carries it (Bluetooth, BLE, USB or the simulator). */
interface Link {
    val label: String
    suspend fun open()
    suspend fun write(data: ByteArray)
    /** Bytes as they arrive from the adapter. */
    val input: ReceiveChannel<ByteArray>
    fun close()
}

/**
 * Bluetooth Classic serial (SPP): OBDLink MX+/LX, vLinker MC+ and most ELM327 dongles. The adapter must
 * be paired in Android's Bluetooth settings first.
 */
@SuppressLint("MissingPermission")
class BtClassicLink(private val device: BluetoothDevice, private val adapter: BluetoothAdapter) : Link {
    override val label: String = device.name ?: device.address
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> = inbox
    private var socket: BluetoothSocket? = null
    private var reader: Job? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        adapter.cancelDiscovery()
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: IOException) {
            // Some clones only accept an insecure socket.
            device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }
        socket = s
        reader = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = s.inputStream.read(buf)
                    if (n < 0) break
                    if (n > 0) inbox.trySend(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            inbox.close(IOException("Bluetooth link lost"))
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("Not connected")
        s.outputStream.write(data)
        s.outputStream.flush()
    }

    override fun close() {
        reader?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
