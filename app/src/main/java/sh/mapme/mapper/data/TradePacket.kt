package sh.mapme.mapper.data

/**
 * TradePacket.kt — MMG1 Geister-Tausch-Payload (Channel-Datagram 0x3E).
 *
 * 145 Byte, little-endian, signiert werden Bytes 0–80 (alles vor sig):
 *
 *   0   4  Magic "MMG1"
 *   4   1  0x01 OFFER / 0x02 ACCEPT / 0x03 ABORT
 *   5   4  ghost_id u32
 *   9   8  nonce (bindet OFFER+ACCEPT)
 *  17  32  from_pubkey Ed25519
 *  49  32  to_pubkey (OFFER: 32x00 = jeder in Rufweite)
 *  81  64  Ed25519-Sig des Companion über Bytes 0–80
 *
 * Kein BLE, keine Crypto — reiner Codec (Sig sind 64 Rohbytes).
 */

enum class TradeType(val value: Int) {
    OFFER(0x01), ACCEPT(0x02), ABORT(0x03);

    companion object {
        fun fromByte(b: Byte): TradeType? = entries.find { it.value == (b.toInt() and 0xFF) }
    }
}

data class TradeFrame(
    val type: TradeType,
    val ghostId: Long,          // u32 — Long, damit 0xFFFFFFFF nicht negativ wird
    val nonce: ByteArray,       // 8
    val fromPubkey: ByteArray,  // 32
    val toPubkey: ByteArray,    // 32
    val signature: ByteArray    // 64
) {
    val isBroadcast: Boolean get() = toPubkey.all { it == 0.toByte() }

    override fun equals(other: Any?): Boolean {
        if (other !is TradeFrame) return false
        return type == other.type && ghostId == other.ghostId &&
            nonce.contentEquals(other.nonce) &&
            fromPubkey.contentEquals(other.fromPubkey) &&
            toPubkey.contentEquals(other.toPubkey) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int = 31 * ghostId.toInt() + type.value
}

object TradePacket {
    const val SIZE = 145
    const val SIGNED_REGION_END = 81   // exklusiv: Bytes 0..80 werden signiert

    private val MAGIC = byteArrayOf(0x4D, 0x4D, 0x47, 0x31) // "MMG1"

    fun encode(f: TradeFrame): ByteArray {
        require(f.ghostId in 0..0xFFFFFFFFL) { "ghost_id must fit u32" }
        require(f.nonce.size == 8) { "nonce must be 8 bytes" }
        require(f.fromPubkey.size == 32) { "from_pubkey must be 32 bytes" }
        require(f.toPubkey.size == 32) { "to_pubkey must be 32 bytes" }
        require(f.signature.size == 64) { "signature must be 64 bytes" }

        val b = ByteArray(SIZE)
        MAGIC.copyInto(b, 0)
        b[4] = f.type.value.toByte()
        b[5] = (f.ghostId and 0xFF).toByte()
        b[6] = ((f.ghostId shr 8) and 0xFF).toByte()
        b[7] = ((f.ghostId shr 16) and 0xFF).toByte()
        b[8] = ((f.ghostId shr 24) and 0xFF).toByte()
        f.nonce.copyInto(b, 9)
        f.fromPubkey.copyInto(b, 17)
        f.toPubkey.copyInto(b, 49)
        f.signature.copyInto(b, 81)
        return b
    }

    fun decode(b: ByteArray): TradeFrame {
        require(b.size == SIZE) { "MMG1 frame must be $SIZE bytes, got ${b.size}" }
        require(b.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad magic" }
        val type = TradeType.fromByte(b[4])
            ?: throw IllegalArgumentException("unknown trade type ${b[4]}")
        val ghostId = (b[5].toLong() and 0xFF) or
            ((b[6].toLong() and 0xFF) shl 8) or
            ((b[7].toLong() and 0xFF) shl 16) or
            ((b[8].toLong() and 0xFF) shl 24)
        return TradeFrame(
            type = type,
            ghostId = ghostId,
            nonce = b.copyOfRange(9, 17),
            fromPubkey = b.copyOfRange(17, 49),
            toPubkey = b.copyOfRange(49, 81),
            signature = b.copyOfRange(81, SIZE)
        )
    }

    /** Die Bytes, die der Companion signiert / der Empfänger verifiziert. */
    fun signedRegion(b: ByteArray): ByteArray {
        require(b.size >= SIGNED_REGION_END) { "frame too short" }
        return b.copyOfRange(0, SIGNED_REGION_END)
    }
}
