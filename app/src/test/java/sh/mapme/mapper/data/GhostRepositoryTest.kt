package sh.mapme.mapper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GhostRepositoryTest {

    private fun ghost(
        id: Long = 1, kind: GhostKind = GhostKind.IRRLICHT,
        lat: Double = 53.0, lon: Double = 10.0, h7: String = "871f000ffffffff"
    ) = Ghost(id, kind, lat, lon, 10, null, h7)

    // ---- Haversine -------------------------------------------------------------------

    @Test fun `haversine hamburg to hannover roughly 131 km`() {
        val d = GhostMath.haversineMeters(53.5511, 9.9937, 52.3759, 9.7320)
        assertTrue("was $d", d in 128_000.0..135_000.0)
    }

    @Test fun `haversine zero distance`() {
        assertEquals(0.0, GhostMath.haversineMeters(53.0, 10.0, 53.0, 10.0), 0.001)
    }

    // ---- Bearing / Kompass -----------------------------------------------------------

    @Test fun `bearing north and compass labels`() {
        val b = GhostMath.bearingDegrees(53.0, 10.0, 54.0, 10.0)
        assertEquals(0.0, b, 1.0)
        assertEquals("N", GhostMath.compass(b))
        assertEquals("E", GhostMath.compass(90.0))
        assertEquals("S", GhostMath.compass(180.0))
        assertEquals("NNE", GhostMath.compass(22.0))
        assertEquals("N", GhostMath.compass(359.0))
    }

    // ---- inCell über Interface (kein JNI im JVM-Test) --------------------------------

    @Test fun `inCell compares server h7 against own cell`() {
        val fake = H3Cells { _, _ -> "871f18d84ffffff" }
        assertTrue(GhostMath.inCell(53.0, 10.0, ghost(h7 = "871f18d84ffffff"), fake))
        assertFalse(GhostMath.inCell(53.0, 10.0, ghost(h7 = "871f18d85ffffff"), fake))
    }

    // ---- nearestByKind ---------------------------------------------------------------

    @Test fun `nearest picks closest per kind`() {
        val near = ghost(id = 1, lat = 53.01, lon = 10.0)
        val far = ghost(id = 2, lat = 53.5, lon = 10.0)
        val berg = ghost(id = 3, kind = GhostKind.BERGGEIST, lat = 52.0, lon = 9.0)
        val result = GhostMath.nearestByKind(53.0, 10.0, listOf(far, near, berg))
        assertEquals(1L, result[GhostKind.IRRLICHT]!!.ghost.id)
        assertEquals(3L, result[GhostKind.BERGGEIST]!!.ghost.id)
        assertNull(result[GhostKind.PENDLERGEIST])
        assertTrue(result[GhostKind.IRRLICHT]!!.distanceM < 2_000)
    }

    // ---- Modelle ---------------------------------------------------------------------

    @Test fun `rx-only kinds are pendler and bruecke`() {
        assertTrue(GhostKind.PENDLERGEIST.rxOnly)
        assertTrue(GhostKind.BRUECKENGEIST.rxOnly)
        assertFalse(GhostKind.IRRLICHT.rxOnly)
        assertFalse(GhostKind.BERGGEIST.rxOnly)
        assertFalse(GhostKind.WIEDERGAENGER.rxOnly)
    }

    @Test fun `unknown wire kind maps to null not crash`() {
        assertNull(GhostKind.fromWire("goldgeist"))
        assertNull(GhostKind.fromWire(null))
        assertEquals(GhostKind.IRRLICHT, GhostKind.fromWire("irrlicht"))
    }

    @Test fun `sprite index matches website formula id mod 9`() {
        assertEquals(0, ghost(id = 9).spriteIndex)
        assertEquals(4, ghost(id = 4945).spriteIndex)
        assertEquals("https://mapme.sh/ghosts/irrlicht/4.png", ghost(id = 4945).spriteUrl())
    }
}
