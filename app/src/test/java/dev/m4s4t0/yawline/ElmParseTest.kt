package dev.m4s4t0.yawline

import dev.m4s4t0.yawline.obd.ElmParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmParseTest {
    private fun msgs(raw: String, cmd: String) = ElmParse.messages(ElmParse.lines(raw, cmd))

    @Test fun dropsEchoPromptAndSearching() {
        assertEquals(listOf("41 0C 0F A0"), ElmParse.lines("010C\rSEARCHING...\r41 0C 0F A0\r\r>", "010C"))
    }

    @Test fun singlePid() {
        val v = ElmParse.mode01(msgs("41 0C 0F A0\r>", "010C1"), listOf(0x0C))
        assertEquals(1000.0, v[0x0C]!!, 0.01)
    }

    @Test fun multiPidIsoTp() {
        val raw = "00A\r0: 41 0C 1A F8 0D 32\r1: 05 7B 00 00 00 00 00\r>"
        val v = ElmParse.mode01(msgs(raw, "010C0D05"), listOf(0x0C, 0x0D, 0x05))
        assertEquals(1726.0, v[0x0C]!!, 0.01)
        assertEquals(50.0, v[0x0D]!!, 0.01)
        assertEquals(83.0, v[0x05]!!, 0.01)
    }

    @Test fun supportBitmap() {
        val s = ElmParse.supported(msgs("41 00 BE 1F A8 13", "0100"))
        assertTrue(0x01 in s); assertFalse(0x02 in s); assertTrue(0x05 in s); assertTrue(0x0C in s); assertTrue(0x20 in s)
    }

    @Test fun troubleCodes() {
        assertEquals("P0301", ElmParse.dtc(0x03, 0x01))
        assertEquals("C0035", ElmParse.dtc(0x40, 0x35))
        assertEquals("U1234", ElmParse.dtc(0xD2, 0x34))
        assertEquals(listOf("P0301", "P0171"), ElmParse.dtcs(msgs("43 02 03 01 01 71", "03"), 0x03, isCan = true))
        assertEquals(listOf("P0301"), ElmParse.dtcs(msgs("43 03 01 00 00 00 00", "03"), 0x03, isCan = false))
    }

    @Test fun vin() {
        val raw = "014\r0: 49 02 01 4A 46 31\r1: 47 52 38 48 36 37 41\r2: 4C 31 32 33 34 35 36\r>"
        assertEquals("JF1GR8H67AL123456", ElmParse.vin(msgs(raw, "0902")))
    }

    @Test fun errors() {
        assertTrue(ElmParse.isError(ElmParse.lines("NO DATA\r>", "0146")))
        assertTrue(ElmParse.isError(ElmParse.lines("?\r>", "XX")))
        assertFalse(ElmParse.isError(ElmParse.lines("41 0C 0F A0\r>", "010C")))
    }
}
