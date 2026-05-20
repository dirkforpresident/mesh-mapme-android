package sh.mapme.mapper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCorePacketTest {

    private fun header(routeType: Int, payloadType: Int = 0, payloadVer: Int = 0): Byte {
        require(routeType in 0..3 && payloadType in 0..0x0F && payloadVer in 0..3)
        return ((payloadVer shl 6) or (payloadType shl 2) or routeType).toByte()
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ---- Path-len packing (Marcel's spec) ------------------------------------------------

    @Test fun `hash_size 1, 3 hops — packed byte 0x03`() {
        // route=FLOOD (no transport_codes), 3 hops of 1 byte each
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x03,                       // 3 hops, hash_size_code = 0 → 1B
            0xAA, 0xBB, 0xCC,
            0xDE, 0xAD                  // payload
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(1, d.hashSize)
        assertEquals(3, d.hopCount)
        assertEquals(listOf("aa", "bb", "cc"), d.hops)
        assertFalse(d.hasTransportCodes)
        assertEquals(5, d.payloadOffset)   // 1 header + 1 pathLen + 3 path
    }

    @Test fun `hash_size 2, 3 hops — packed byte 0x43`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x43,                       // (1<<6)|3 = hash_size=2, 3 hops
            0xAA, 0xBB,
            0xCC, 0xDD,
            0xEE, 0xFF,
            0xDE, 0xAD                  // payload
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(2, d.hashSize)
        assertEquals(3, d.hopCount)
        assertEquals(listOf("aabb", "ccdd", "eeff"), d.hops)
        assertEquals(8, d.payloadOffset)   // 1 + 1 + 3*2
    }

    @Test fun `hash_size 3, 5 hops — packed byte 0x85`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x85,                       // (2<<6)|5 = hash_size=3, 5 hops
            0x01, 0x02, 0x03,
            0x04, 0x05, 0x06,
            0x07, 0x08, 0x09,
            0x0A, 0x0B, 0x0C,
            0x0D, 0x0E, 0x0F
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(3, d.hashSize)
        assertEquals(5, d.hopCount)
        assertEquals(
            listOf("010203", "040506", "070809", "0a0b0c", "0d0e0f"),
            d.hops
        )
        assertEquals(17, d.payloadOffset)  // 1 + 1 + 5*3
    }

    @Test fun `reserved hash_size code (3) throws ReservedHashSize`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0xC0,                       // (3<<6)|0 = reserved
        )
        assertThrows(MeshCorePacket.DecodeError.ReservedHashSize::class.java) {
            MeshCorePacket.decode(data)
        }
    }

    @Test fun `0xFF header throws DoNotRetransmit`() {
        val data = bytes(0xFF, 0x00)
        assertThrows(MeshCorePacket.DecodeError.DoNotRetransmit::class.java) {
            MeshCorePacket.decode(data)
        }
    }

    // ---- Route types and transport_codes -------------------------------------------------

    @Test fun `ROUTE_TYPE_FLOOD has no transport_codes`() {
        val data = bytes(header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF, 0x00)
        val d = MeshCorePacket.decode(data)
        assertEquals(MeshCorePacket.ROUTE_TYPE_FLOOD, d.routeType)
        assertFalse(d.hasTransportCodes)
        assertEquals(2, d.payloadOffset)
    }

    @Test fun `ROUTE_TYPE_DIRECT has no transport_codes`() {
        val data = bytes(header(MeshCorePacket.ROUTE_TYPE_DIRECT).toInt() and 0xFF, 0x00)
        val d = MeshCorePacket.decode(data)
        assertEquals(MeshCorePacket.ROUTE_TYPE_DIRECT, d.routeType)
        assertFalse(d.hasTransportCodes)
        assertEquals(2, d.payloadOffset)
    }

    @Test fun `ROUTE_TYPE_TRANSPORT_FLOOD prepends 4 transport_codes bytes`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_TRANSPORT_FLOOD).toInt() and 0xFF,
            0x11, 0x22, 0x33, 0x44,     // 4B transport_codes
            0x02,                        // 2 hops, 1B each
            0xAA, 0xBB,
            0xCA, 0xFE                   // payload
        )
        val d = MeshCorePacket.decode(data)
        assertTrue(d.hasTransportCodes)
        assertEquals(listOf("aa", "bb"), d.hops)
        assertEquals(8, d.payloadOffset)   // 1 + 4 + 1 + 2
    }

    @Test fun `ROUTE_TYPE_TRANSPORT_DIRECT prepends 4 transport_codes bytes`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_TRANSPORT_DIRECT).toInt() and 0xFF,
            0x11, 0x22, 0x33, 0x44,
            0x41,                        // hash_size=2, 1 hop
            0xAA, 0xBB,
            0xCA, 0xFE
        )
        val d = MeshCorePacket.decode(data)
        assertTrue(d.hasTransportCodes)
        assertEquals(2, d.hashSize)
        assertEquals(listOf("aabb"), d.hops)
        assertEquals(8, d.payloadOffset)
    }

    // ---- Header bit-field accessors ------------------------------------------------------

    @Test fun `header bit fields decoded correctly`() {
        // payload_type = ADVERT (0x04), payload_ver = 0, route = FLOOD
        val data = bytes(header(MeshCorePacket.ROUTE_TYPE_FLOOD, MeshCorePacket.PAYLOAD_TYPE_ADVERT).toInt() and 0xFF, 0x00)
        val d = MeshCorePacket.decode(data)
        assertEquals(MeshCorePacket.PAYLOAD_TYPE_ADVERT, d.payloadType)
        assertEquals(0, d.payloadVer)
    }

    @Test fun `header payload_ver decoded from top 2 bits`() {
        val data = bytes(header(MeshCorePacket.ROUTE_TYPE_FLOOD, payloadType = 0, payloadVer = 2).toInt() and 0xFF, 0x00)
        val d = MeshCorePacket.decode(data)
        assertEquals(2, d.payloadVer)
    }

    // ---- Edge cases ----------------------------------------------------------------------

    @Test fun `empty path (hopCount 0) is valid`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x00,                        // 0 hops
            0xDE, 0xAD, 0xBE, 0xEF       // pure payload
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(0, d.hopCount)
        assertTrue(d.hops.isEmpty())
        assertEquals(2, d.payloadOffset)
    }

    @Test fun `all-zero hops are filtered out`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x03,                        // 3 hops, 1B
            0xAA, 0x00, 0xCC             // middle hop is zero → dropped
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(3, d.hopCount)              // declared count unchanged
        assertEquals(listOf("aa", "cc"), d.hops) // filtered list
    }

    @Test fun `all-zero multi-byte hop is filtered out`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x42,                        // hash_size=2, 2 hops
            0x00, 0x00,
            0xAB, 0xCD
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(listOf("abcd"), d.hops)
    }

    @Test fun `decode honours headerOffset for BLE companion wrapper`() {
        // simulate [0x88][SNR][RSSI][header][pathLen][path...]
        val data = bytes(
            0x88, 0xFC, 0xC8,            // wrapper: code, snr, rssi
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x02,                        // 2 hops, 1B
            0xAA, 0xBB,
            0xDE, 0xAD
        )
        val d = MeshCorePacket.decode(data, headerOffset = 3)
        assertEquals(listOf("aa", "bb"), d.hops)
        assertEquals(7, d.payloadOffset)
    }

    @Test fun `truncated packet (missing path bytes) throws TooShort`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x03,                        // 3 hops, but only 2 path bytes follow
            0xAA, 0xBB
        )
        assertThrows(MeshCorePacket.DecodeError.TooShort::class.java) {
            MeshCorePacket.decode(data)
        }
    }

    @Test fun `truncated packet (missing path_len byte) throws TooShort`() {
        val data = bytes(header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF)
        assertThrows(MeshCorePacket.DecodeError.TooShort::class.java) {
            MeshCorePacket.decode(data)
        }
    }

    @Test fun `truncated packet (missing path_len after transport_codes) throws TooShort`() {
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_TRANSPORT_FLOOD).toInt() and 0xFF,
            0x11, 0x22, 0x33, 0x44       // 4B transport_codes but no path_len_byte
        )
        assertThrows(MeshCorePacket.DecodeError.TooShort::class.java) {
            MeshCorePacket.decode(data)
        }
    }

    // ---- Regression: the original "every-byte-is-a-hop" bug --------------------------------

    @Test fun `regression — 2-byte hash 3 hops is NOT decoded as 6 single-byte hops`() {
        // The old code would have seen pathLen=0x43 (67) and tried to read 67 single-byte hops.
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_FLOOD).toInt() and 0xFF,
            0x43,
            0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(3, d.hops.size)                     // not 6
        assertEquals("1234", d.hops[0])
        assertEquals("5678", d.hops[1])
        assertEquals("9abc", d.hops[2])
    }

    @Test fun `regression — transport_codes do not get parsed as path bytes`() {
        // Old code would treat byte 1 (which is actually first transport_code byte)
        // as the path_len byte and produce nonsense.
        val data = bytes(
            header(MeshCorePacket.ROUTE_TYPE_TRANSPORT_FLOOD).toInt() and 0xFF,
            0xFE, 0xED, 0xFA, 0xCE,      // these MUST be skipped
            0x01,
            0xAA
        )
        val d = MeshCorePacket.decode(data)
        assertEquals(listOf("aa"), d.hops)
    }
}
