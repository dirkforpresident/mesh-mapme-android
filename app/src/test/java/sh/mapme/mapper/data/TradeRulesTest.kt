package sh.mapme.mapper.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 10: Tausch-Regeln — 24-h-Soulbound und Karten-Quelle. */
class TradeRulesTest {

    private fun card(source: String, ageMs: Long, now: Long = 1_000_000_000_000L) = AlbumCard(
        ghostId = 1, kind = GhostKind.IRRLICHT, name = null, points = 10,
        lat = 53.0, lon = 10.0, source = source,
        caughtAt = now - ageMs, fromPubkey = null, spriteIndex = 0)

    private val now = 1_000_000_000_000L
    private val h = 60 * 60 * 1000L

    @Test fun `caught card older than 24h is tradeable`() {
        assertTrue(card("caught", 25 * h).tradeable(now))
    }

    @Test fun `caught card younger than 24h is soulbound`() {
        assertFalse(card("caught", 23 * h).tradeable(now))
    }

    @Test fun `exactly 24h is tradeable`() {
        assertTrue(card("caught", 24 * h).tradeable(now))
    }

    @Test fun `traded cards are never re-tradeable`() {
        assertFalse(card("traded", 100 * h).tradeable(now))
    }
}
