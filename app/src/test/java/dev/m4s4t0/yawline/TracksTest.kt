package dev.m4s4t0.yawline

import dev.m4s4t0.yawline.track.Geo
import dev.m4s4t0.yawline.track.Guide
import dev.m4s4t0.yawline.track.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TracksTest {
    private val lat0 = 60.0
    private val k = 111_320.0
    /** A position [east] / [north] metres from a fixed origin. */
    private fun at(east: Double, north: Double) = (lat0 + north / k) to (25.0 + east / (k * cos(Math.toRadians(lat0))))

    /** 300 m north, a right-angle right, 200 m east, a hairpin left, 200 m west. */
    private val drawn = listOf(at(0.0, 0.0), at(0.0, 300.0), at(200.0, 300.0), at(200.0, 320.0), at(0.0, 320.0))

    @Test fun measuresDistance() {
        val pts = Geo.measure(drawn)
        assertEquals(720.0, pts.last().d, 2.0)
        assertEquals(300.0, pts[1].d, 1.0)
    }

    @Test fun findsTurns() {
        val turns = Guide.findTurns(Geo.measure(drawn))
        assertEquals(2, turns.size)
        val (a, b) = turns
        assertEquals("right", a.call)
        assertEquals(90.0, a.deg, 10.0)
        assertEquals(300.0, a.atM, 15.0)
        assertEquals("hairpin left", b.call)
        assertTrue(b.atM in 490.0..530.0)
    }

    @Test fun straightLineHasNoTurns() {
        assertTrue(Guide.findTurns(Geo.measure(listOf(at(0.0, 0.0), at(10.0, 500.0), at(0.0, 1000.0)))).isEmpty())
    }

    @Test fun locatesAlongAndOff() {
        val g = Guide(Geo.measure(drawn))
        val (lat, lon) = at(15.0, 120.0) // 15 m east of the first leg, 120 m up it
        val on = g.locate(lat, lon)
        assertEquals(120.0, on.alongM, 1.0)
        assertEquals(15.0, on.offM, 1.0)
        assertEquals(">=300 m to the right", 300.0 - 120.0, g.nextTurn(on.alongM)!!.atM - on.alongM, 15.0)
    }

    @Test fun hintKeepsOutAndBackOnTheRightLeg() {
        // Up 500 m and back down the same road: halfway up on the way back must read as the return leg.
        val g = Guide(Geo.measure(listOf(at(0.0, 0.0), at(0.0, 500.0), at(3.0, 0.0))))
        val (lat, lon) = at(1.5, 250.0)
        assertEquals(750.0, g.locate(lat, lon, hintM = 700.0).alongM, 5.0)
        assertEquals(250.0, g.locate(lat, lon, hintM = 200.0).alongM, 5.0)
    }

    @Test fun loopStartsAtItsStart() {
        val loop = Geo.measure(listOf(at(0.0, 0.0), at(0.0, 200.0), at(200.0, 200.0), at(200.0, 0.0), at(0.0, 0.0)))
        val (lat, lon) = at(2.0, 1.0)
        assertTrue(Guide(loop).locate(lat, lon).alongM < 10)
    }

    @Test fun reversesAndThins() {
        val dense = (0..100).map { at(0.0, it * 1.0) }
        val thin = Geo.thin(dense, 5.0)
        assertTrue(thin.size in 17..21)
        assertEquals(dense.first(), thin.first())
        assertEquals(dense.last(), thin.last())
        assertEquals(100.0, Geo.dist(thin.first().first, thin.first().second, thin.last().first, thin.last().second), 0.5)
    }

    @Test fun parsesGpx() {
        val gpx = """<?xml version="1.0"?>
            <gpx version="1.1" creator="x"><metadata><name>Col de Turini &amp; back</name></metadata>
            <wpt lat="1" lon="1"><name>Parking</name></wpt>
            <trk><trkseg>
              <trkpt lat="43.9770" lon="7.3900"><ele>1600</ele></trkpt>
              <trkpt lon='7.3910' lat='43.9780'/>
              <trkpt lat="43.9790" lon="7.3920"></trkpt>
            </trkseg></trk></gpx>"""
        val (name, pts) = Tracks.parseGpx(gpx)
        assertEquals("Col de Turini & back", name)
        assertEquals(3, pts.size)
        assertEquals(43.978 to 7.391, pts[1])
    }

    @Test fun gpxRoutesAndWaypointsAsFallback() {
        assertEquals(2, Tracks.parseGpx("""<gpx><rte><rtept lat="1" lon="2"/><rtept lat="1.1" lon="2"/></rte></gpx>""").second.size)
        val (name, pts) = Tracks.parseGpx("""<gpx><wpt lat="1" lon="2"/><wpt lat="1.1" lon="2.1"/><wpt lat="99" lon="2"/></gpx>""")
        assertNull(name)
        assertEquals(2, pts.size)
    }
}
