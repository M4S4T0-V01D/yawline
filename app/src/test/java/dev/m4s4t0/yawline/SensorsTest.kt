package dev.m4s4t0.yawline

import dev.m4s4t0.yawline.obd.UsbLink
import dev.m4s4t0.yawline.sensors.DriftEstimator
import dev.m4s4t0.yawline.sensors.RaceBoxParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SensorsTest {
    @Test fun slipFromYawWithoutCourseChange() {
        val d = DriftEstimator()
        d.onGps(90.0, 20.0)
        // Nose swings 20° left (CCW) while the path stays straight.
        repeat(100) { d.onImu(0.01, 20.0, 0.0) }
        assertEquals(-20.0, d.slip, 0.5)
        assertEquals(70.0, d.heading, 0.5)
    }

    @Test fun gripTurnHasNoSlip() {
        val d = DriftEstimator()
        d.onGps(0.0, 20.0)
        // Turning left at 20°/s on rails: lateral acceleration matches v·ω.
        val w = Math.toRadians(20.0)
        repeat(100) { d.onImu(0.01, 20.0, 20.0 * w) }
        assertEquals(0.0, d.slip, 0.5)
    }

    @Test fun learnsGyroBiasAtRest() {
        val d = DriftEstimator()
        d.onGps(null, 0.0)
        repeat(3000) { d.onImu(0.01, 0.8, 0.0) }
        assertEquals(0.8, d.bias, 0.05)
    }

    @Test fun signedAngles() {
        assertEquals(-10.0, DriftEstimator.signed(350.0), 1e-9)
        assertEquals(10.0, DriftEstimator.signed(-350.0), 1e-9)
        assertEquals(5.0, DriftEstimator.blend(355.0, 15.0, 0.5), 1e-9)
    }

    private fun raceBoxPacket(lat: Double, lon: Double, speedMps: Double, heading: Double): ByteArray {
        val p = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        p.put(20, 3); p.put(23, 14)
        p.putInt(24, Math.round(lon * 1e7).toInt()); p.putInt(28, Math.round(lat * 1e7).toInt())
        p.putInt(36, 120_500); p.putInt(40, 800)
        p.putInt(48, (speedMps * 1000).toInt()); p.putInt(52, Math.round(heading * 1e5).toInt()); p.put(67, 77)
        val body = byteArrayOf(0xFF.toByte(), 0x01, 80, 0) + p.array()
        var a = 0; var b = 0
        for (x in body) { a = (a + (x.toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF }
        return byteArrayOf(0xB5.toByte(), 0x62) + body + byteArrayOf(a.toByte(), b.toByte())
    }

    @Test fun raceBoxSplitAcrossNotifications() {
        val pkt = raceBoxPacket(62.2426, 25.7473, 27.5, 183.25)
        val r = RaceBoxParser()
        assertEquals(0, r.feed(byteArrayOf(1, 2, 3) + pkt.copyOfRange(0, 40)).size)
        val fixes = r.feed(pkt.copyOfRange(40, pkt.size) + pkt)
        assertEquals(2, fixes.size)
        val f = fixes[0]
        assertEquals(62.2426, f.lat, 1e-6); assertEquals(25.7473, f.lon, 1e-6)
        assertEquals(27.5, f.speedMps, 1e-3); assertEquals(183.25, f.headingDeg, 1e-4)
        assertEquals(14, f.sats); assertEquals(3, f.fixType); assertEquals(0.8, f.hAccM, 1e-9); assertEquals(77, f.battery)
    }

    @Test fun raceBoxRejectsBadChecksum() {
        val pkt = raceBoxPacket(1.0, 2.0, 3.0, 4.0)
        pkt[pkt.size - 1] = (pkt[pkt.size - 1] + 1).toByte()
        assertEquals(0, RaceBoxParser().feed(pkt).size)
    }

    @Test fun ftdiBaud() {
        assertEquals(0x1A to 0, UsbLink.ftdiDivisor(115200))
        assertEquals(0x4138 to 0, UsbLink.ftdiDivisor(9600))
        assertEquals(0xC034 to 0, UsbLink.ftdiDivisor(57600)) // 52.125: the 1/8 fraction is code 3
    }
}
