package sh.mapme.mapper.data

/**
 * MeshCore over-the-air packet decoder.
 *
 * Wire format (the raw OTA bytes — BLE companion frames may have a small
 * wrapper before this, e.g. [code][snr][rssi]):
 *
 *   header           1 byte
 *   transport_codes  4 bytes — ONLY when route_type ∈ {TRANSPORT_FLOOD, TRANSPORT_DIRECT}
 *   path_len_byte    1 byte  — packed: low 6 bits = hop_count, high 2 bits = hash_size_code
 *   path             hop_count × hash_size bytes
 *   payload          remaining bytes
 *
 * Reference: https://github.com/meshcore-dev/MeshCore/blob/main/src/Packet.h
 */
object MeshCorePacket {
    private const val PH_ROUTE_MASK = 0x03
    private const val PH_TYPE_SHIFT = 2
    private const val PH_TYPE_MASK = 0x0F
    private const val PH_VER_SHIFT = 6
    private const val PH_VER_MASK = 0x03

    const val ROUTE_TYPE_TRANSPORT_FLOOD = 0x00
    const val ROUTE_TYPE_FLOOD = 0x01
    const val ROUTE_TYPE_DIRECT = 0x02
    const val ROUTE_TYPE_TRANSPORT_DIRECT = 0x03

    const val PAYLOAD_TYPE_REQ = 0x00
    const val PAYLOAD_TYPE_RESPONSE = 0x01
    const val PAYLOAD_TYPE_TXT_MSG = 0x02
    const val PAYLOAD_TYPE_ACK = 0x03
    const val PAYLOAD_TYPE_ADVERT = 0x04
    const val PAYLOAD_TYPE_GRP_TXT = 0x05
    const val PAYLOAD_TYPE_GRP_DATA = 0x06
    const val PAYLOAD_TYPE_ANON_REQ = 0x07
    const val PAYLOAD_TYPE_PATH = 0x08
    const val PAYLOAD_TYPE_TRACE = 0x09
    const val PAYLOAD_TYPE_MULTIPART = 0x0A
    const val PAYLOAD_TYPE_CONTROL = 0x0B
    const val PAYLOAD_TYPE_RAW_CUSTOM = 0x0F

    /** Firmware sentinel: header byte == 0xFF means "do not retransmit". */
    const val MARKER_DO_NOT_RETRANSMIT = 0xFF

    data class Decoded(
        val routeType: Int,
        val payloadType: Int,
        val payloadVer: Int,
        val hasTransportCodes: Boolean,
        /** 1, 2, or 3 — bytes per hop. */
        val hashSize: Int,
        /** Raw declared hop count (low 6 bits of path_len_byte). */
        val hopCount: Int,
        /** Hops as lowercase hex, each `hashSize * 2` chars wide. All-zero hops are dropped. */
        val hops: List<String>,
        /** Offset into the original buffer where payload bytes begin. */
        val payloadOffset: Int,
    )

    sealed class DecodeError(message: String) : RuntimeException(message) {
        class TooShort(needed: Int, available: Int) :
            DecodeError("packet too short: need $needed bytes, have $available")
        class ReservedHashSize :
            DecodeError("path_len_byte uses reserved hash_size code (3)")
        class DoNotRetransmit :
            DecodeError("header is 0xFF (do-not-retransmit marker)")
    }

    /**
     * Decode a MeshCore wire-format packet beginning at [headerOffset] in [data].
     * Throws [DecodeError] on malformed input. Callers should catch and drop the packet.
     */
    fun decode(data: ByteArray, headerOffset: Int = 0): Decoded {
        if (data.size <= headerOffset) {
            throw DecodeError.TooShort(needed = headerOffset + 1, available = data.size)
        }
        val header = data[headerOffset].toInt() and 0xFF
        if (header == MARKER_DO_NOT_RETRANSMIT) throw DecodeError.DoNotRetransmit()

        val routeType = header and PH_ROUTE_MASK
        val payloadType = (header shr PH_TYPE_SHIFT) and PH_TYPE_MASK
        val payloadVer = (header shr PH_VER_SHIFT) and PH_VER_MASK

        val hasTransportCodes = routeType == ROUTE_TYPE_TRANSPORT_FLOOD ||
                routeType == ROUTE_TYPE_TRANSPORT_DIRECT
        val transportLen = if (hasTransportCodes) 4 else 0

        val pathLenOffset = headerOffset + 1 + transportLen
        if (data.size <= pathLenOffset) {
            throw DecodeError.TooShort(needed = pathLenOffset + 1, available = data.size)
        }

        val pathLenByte = data[pathLenOffset].toInt() and 0xFF
        val hopCount = pathLenByte and 0x3F
        val hashSizeCode = (pathLenByte shr 6) and 0x03
        if (hashSizeCode == 3) throw DecodeError.ReservedHashSize()
        val hashSize = hashSizeCode + 1
        val pathBytes = hopCount * hashSize
        val payloadOffset = pathLenOffset + 1 + pathBytes
        if (data.size < payloadOffset) {
            throw DecodeError.TooShort(needed = payloadOffset, available = data.size)
        }

        val hopsStart = pathLenOffset + 1
        val hops = ArrayList<String>(hopCount)
        for (i in 0 until hopCount) {
            val off = hopsStart + i * hashSize
            var allZero = true
            for (j in 0 until hashSize) {
                if (data[off + j].toInt() != 0) { allZero = false; break }
            }
            if (allZero) continue
            val hex = StringBuilder(hashSize * 2)
            for (j in 0 until hashSize) hex.append("%02x".format(data[off + j]))
            hops.add(hex.toString())
        }

        return Decoded(
            routeType = routeType,
            payloadType = payloadType,
            payloadVer = payloadVer,
            hasTransportCodes = hasTransportCodes,
            hashSize = hashSize,
            hopCount = hopCount,
            hops = hops,
            payloadOffset = payloadOffset,
        )
    }
}
