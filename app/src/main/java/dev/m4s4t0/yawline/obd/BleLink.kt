package dev.m4s4t0.yawline.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * A serial pipe over Bluetooth Low Energy. Adapters disagree on the service, so this looks for the
 * well-known ones (FFF0 used by vLinker and most BLE ELM327s, FFE0 "HM-10" style, Nordic UART used by
 * OBDLink CX and RaceBox) and otherwise takes the first service with both a notify and a write
 * characteristic.
 */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val device: BluetoothDevice) : Link {
    override val label: String = device.name ?: device.address
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> = inbox
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val writeLock = Mutex()
    private var pending: CompletableDeferred<Unit>? = null
    private val ready = CompletableDeferred<Unit>()

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready.completeExceptionally(IOException("BLE connection failed ($status)"))
                inbox.close(IOException("BLE link lost"))
                pending?.completeExceptionally(IOException("BLE link lost"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val (notify, write) = pickPipe(g) ?: run {
                ready.completeExceptionally(IOException("No serial service on this BLE device"))
                return
            }
            tx = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                ready.complete(Unit)
                return
            }
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value)
            else @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(Unit)
            else pending?.completeExceptionally(IOException("BLE write failed ($status)"))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            inbox.trySend(value.copyOf())
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // Android 12 and older only call this one.
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") c.value?.let { inbox.trySend(it.copyOf()) }
        }
    }

    override suspend fun open() {
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        withTimeout(15_000) { ready.await() }
    }

    override suspend fun write(data: ByteArray) = writeLock.withLock {
        val g = gatt ?: throw IOException("Not connected")
        val c = tx ?: throw IOException("Not connected")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        for (chunk in data.toList().chunked(mtu - 3)) {
            val bytes = chunk.toByteArray()
            val done = CompletableDeferred<Unit>()
            pending = done
            val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            else @Suppress("DEPRECATION") run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
            if (!ok) throw IOException("BLE write refused")
            withTimeout(2_000) { done.await() }
        }
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private fun uuid16(s: String) = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
        val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val KNOWN = listOf(uuid16("fff0"), uuid16("ffe0"), NUS, uuid16("18f0"))

        /** The (notify, write) characteristic pair to talk through. */
        fun pickPipe(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
            val services = g.services.sortedBy { s -> KNOWN.indexOf(s.uuid).let { if (it < 0) 99 else it } }
            for (s in services) {
                val notify = s.characteristics.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 } ?: continue
                val write = s.characteristics.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                } ?: continue
                return notify to write
            }
            return null
        }
    }
}
