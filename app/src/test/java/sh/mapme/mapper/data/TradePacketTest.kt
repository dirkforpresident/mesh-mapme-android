package sh.mapme.mapper.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TradePacketTest {

    private fun frame(
        type: TradeType = TradeType.OFFER,
        ghostId: Long = 4945L,
        nonce: ByteArray = ByteArray(8) { it.toByte() },
        from: ByteArray = ByteArray(32) { 0xAA.toByte() },
        to: ByteArray = ByteArray(32),
        sig: ByteArray = ByteArray(64) { 0xEE.toByte() }
    ) = TradeFrame(type, ghostId, nonce, from, to, sig)

    // ---- Encode ----------------------------------------------------------------------

    @Test fun `encode is exactly 145 bytes`() {
        assertEquals(145, TradePacket.encode(frame()).size)
    }

    @Test fun `magic is MMG1 ascii at offset 0`() {
        val b = TradePacket.encode(frame())
        assertArrayEquals(byteArrayOf(0x4D, 0x4D, 0x47, 0x31), b.copyOfRange(0, 4))
    }

    @Test fun `ghost_id u32 little endian at offset 5`() {
        val b = TradePacket.encode(frame(ghostId = 0x01020304L))
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), b.copyOfRange(5, 9))
    }

    @Test fun `layout offsets match spec`() {
        val nonce = ByteArray(8) { (0x10 + it).toByte() }
        val from = ByteArray(32) { 0xBB.toByte() }
        val to = ByteArray(32) { 0xCC.toByte() }
        val sig = ByteArray(64) { 0xDD.toByte() }
        val b = TradePacket.encode(frame(TradeType.ACCEPT, 7, nonce, from, to, sig))
        assertEquals(0x02, b[4].toInt())
        assertArrayEquals(nonce, b.copyOfRange(9, 17))
        assertArrayEquals(from, b.copyOfRange(17, 49))
        assertArrayEquals(to, b.copyOfRange(49, 81))
        assertArrayEquals(sig, b.copyOfRange(81, 145))
    }

    // ---- Decode / Roundtrip ----------------------------------------------------------

    @Test fun `roundtrip preserves every field`() {
        val f = frame(TradeType.ACCEPT, 0xFFFFFFFFL,
            ByteArray(8) { 9 }, ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(64) { 3 })
        val d = TradePacket.decode(TradePacket.encode(f))
        assertEquals(f.type, d.type)
        assertEquals(f.ghostId, d.ghostId)
        assertArrayEquals(f.nonce, d.nonce)
        assertArrayEquals(f.fromPubkey, d.fromPubkey)
        assertArrayEquals(f.toPubkey, d.toPubkey)
        assertArrayEquals(f.signature, d.signature)
    }

    @Test fun `wrong magic throws`() {
        val b = TradePacket.encode(frame())
        b[0] = 0x58
        assertThrows(IllegalArgumentException::class.java) { TradePacket.decode(b) }
    }

    @Test fun `wrong length throws`() {
        assertThrows(IllegalArgumentException::class.java) { TradePacket.decode(ByteArray(144)) }
        assertThrows(IllegalArgumentException::class.java) { TradePacket.decode(ByteArray(163)) }
    }

    @Test fun `unknown type byte throws`() {
        val b = TradePacket.encode(frame())
        b[4] = 0x7F
        assertThrows(IllegalArgumentException::class.java) { TradePacket.decode(b) }
    }

    @Test fun `encode rejects wrong field sizes`() {
        assertThrows(IllegalArgumentException::class.java) {
            TradePacket.encode(frame(nonce = ByteArray(7)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TradePacket.encode(frame(from = ByteArray(31)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TradePacket.encode(frame(sig = ByteArray(63)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TradePacket.encode(frame(ghostId = -1L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TradePacket.encode(frame(ghostId = 0x1_0000_0000L))
        }
    }

    // ---- Signatur-Region -------------------------------------------------------------

    @Test fun `signed region is bytes 0 to 80 inclusive`() {
        val b = TradePacket.encode(frame())
        val region = TradePacket.signedRegion(b)
        assertEquals(81, region.size)
        assertArrayEquals(b.copyOfRange(0, 81), region)
    }

    @Test fun `changing signature does not change signed region`() {
        val b = TradePacket.encode(frame())
        val before = TradePacket.signedRegion(b)
        b[100] = 0x55
        assertArrayEquals(before, TradePacket.signedRegion(b))
    }

    // ---- Broadcast -------------------------------------------------------------------

    @Test fun `all-zero to_pubkey is broadcast`() {
        assertTrue(TradePacket.decode(TradePacket.encode(frame(to = ByteArray(32)))).isBroadcast)
        assertFalse(TradePacket.decode(TradePacket.encode(frame(to = ByteArray(32) { 1 }))).isBroadcast)
    }

    @Test fun `fits meshcore datagram limit`() {
        assertTrue(TradePacket.encode(frame()).size <= 163)
    }
}
