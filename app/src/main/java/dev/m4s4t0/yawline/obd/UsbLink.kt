package dev.m4s4t0.yawline.obd

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A wired adapter on the phone's USB-C port (through an OTG cable). Talks to the three USB-serial chips
 * OBD cables use: FTDI (OBDLink SX/EX), Silicon Labs CP210x, and plain CDC-ACM (STN-based and many
 * newer cables), at 115200 baud 8N1. CH340 cables are not supported.
 */
class UsbLink(private val usb: UsbManager, private val device: UsbDevice, private val baud: Int = 115200) : Link {
    override val label: String = device.productName ?: "USB adapter"
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> = inbox
    private var conn: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var reader: Job? = null
    private val chip = chipOf(device)

    override suspend fun open() = withContext(Dispatchers.IO) {
        if (chip == Chip.UNKNOWN) throw IOException("Unsupported USB adapter (needs FTDI, CP210x or CDC)")
        val c = usb.openDevice(device) ?: throw IOException("USB permission missing")
        conn = c
        val dataIface = if (chip == Chip.CDC) {
            (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                ?: device.getInterface(device.interfaceCount - 1)
        } else device.getInterface(0)
        iface = dataIface
        if (!c.claimInterface(dataIface, true)) throw IOException("Could not claim the USB interface")
        for (i in 0 until dataIface.endpointCount) {
            val ep = dataIface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) throw IOException("USB adapter has no bulk endpoints")
        configure(c)
        reader = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(epIn!!.maxPacketSize.coerceAtLeast(64))
            while (isActive) {
                val n = c.bulkTransfer(epIn, buf, buf.size, 200)
                if (n < 0) continue
                val data = if (chip == Chip.FTDI) stripFtdiStatus(buf, n, epIn!!.maxPacketSize) else buf.copyOf(n)
                if (data.isNotEmpty()) inbox.trySend(data)
            }
        }
    }

    private fun configure(c: UsbDeviceConnection) {
        when (chip) {
            Chip.FTDI -> {
                val out = 0x40
                c.controlTransfer(out, 0, 0, 0, null, 0, 1000)              // reset
                c.controlTransfer(out, 4, 8, 0, null, 0, 1000)              // 8 data bits, no parity, 1 stop
                c.controlTransfer(out, 2, 0, 0, null, 0, 1000)              // no flow control
                val (value, index) = ftdiDivisor(baud)
                c.controlTransfer(out, 3, value, index, null, 0, 1000)      // baud rate
                c.controlTransfer(out, 1, 0x0303, 0, null, 0, 1000)         // DTR + RTS on
            }
            Chip.CP210X -> {
                val out = 0x41
                c.controlTransfer(out, 0x00, 0x0001, 0, null, 0, 1000)      // enable UART
                val b = byteArrayOf((baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(), (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte())
                c.controlTransfer(out, 0x1E, 0, 0, b, 4, 1000)              // baud rate
                c.controlTransfer(out, 0x03, 0x0800, 0, null, 0, 1000)      // 8N1
                c.controlTransfer(out, 0x07, 0x0303, 0, null, 0, 1000)      // DTR + RTS on
            }
            Chip.CDC -> {
                val line = byteArrayOf((baud and 0xFF).toByte(), (baud shr 8 and 0xFF).toByte(), (baud shr 16 and 0xFF).toByte(), (baud shr 24 and 0xFF).toByte(), 0, 0, 8)
                c.controlTransfer(0x21, 0x20, 0, 0, line, line.size, 1000) // SET_LINE_CODING
                c.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 1000)      // SET_CONTROL_LINE_STATE: DTR + RTS
            }
            Chip.UNKNOWN -> {}
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val c = conn ?: throw IOException("Not connected")
        if (c.bulkTransfer(epOut, data, data.size, 1000) < 0) throw IOException("USB write failed")
    }

    override fun close() {
        reader?.cancel()
        runCatching { iface?.let { conn?.releaseInterface(it) } }
        runCatching { conn?.close() }
        conn = null
        inbox.close()
    }

    enum class Chip { FTDI, CP210X, CDC, UNKNOWN }

    companion object {
        fun chipOf(d: UsbDevice): Chip = when {
            d.vendorId == 0x0403 -> Chip.FTDI
            d.vendorId == 0x10C4 -> Chip.CP210X
            d.deviceClass == UsbConstants.USB_CLASS_COMM ||
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } -> Chip.CDC
            else -> Chip.UNKNOWN
        }

        /** FTDI prefixes every packet with two modem-status bytes; drop them. */
        fun stripFtdiStatus(buf: ByteArray, n: Int, packet: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(n)
            var i = 0
            while (i < n) {
                val end = minOf(i + packet, n)
                if (end - i > 2) out.write(buf, i + 2, end - i - 2)
                i = end
            }
            return out.toByteArray()
        }

        /** FT232R baud divisor (3 MHz base clock, eighths), split into the wValue/wIndex pair. */
        fun ftdiDivisor(baud: Int): Pair<Int, Int> {
            val frac = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)
            val div8 = (3_000_000 * 8 + baud / 2) / baud
            var whole = div8 shr 3
            val enc = (whole and 0x3FFF) or (frac[div8 and 7] shl 14)
            whole = enc
            return (whole and 0xFFFF) to (whole shr 16 and 0xFFFF)
        }
    }
}
