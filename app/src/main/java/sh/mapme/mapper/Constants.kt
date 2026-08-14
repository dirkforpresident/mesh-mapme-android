package sh.mapme.mapper

import java.util.UUID

/**
 * Constants.kt - Direct port of Constants.swift
 * All BLE UUIDs, timing values, and protocol codes
 */
object Constants {
    // MARK: - BLE UUIDs (Nordic UART Service)
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX_CHARACTERISTIC: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")  // App writes here
    val TX_CHARACTERISTIC: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")  // App reads from here

    // MARK: - H3
    const val H3_RESOLUTION = 10

    // MARK: - App Version
    const val APP_VERSION = "1.0.0"

    // MARK: - Server
    const val API_BASE_URL = "https://mapme.sh"

    // MARK: - Timing (in milliseconds for Android)
    const val UPLOAD_INTERVAL = 30_000L          // 30 seconds between uploads
    const val TX_TIMEOUT = 5_000L                // 5 seconds to wait for TX Response
    const val DISCOVER_INTERVAL = 30_000L        // 30 seconds without RX before discover
    const val COVERAGE_TX_DELAY = 180_000L       // 3 min without RX before coverage TX
    const val COVERAGE_TX_MIN_INTERVAL = 23_000L // Min ms between coverage messages
    const val COVERAGE_TX_MAX_INTERVAL = 42_000L // Max ms between coverage messages
    const val MANUAL_PING_COOLDOWN = 120_000L    // 2 min cooldown for manual ping
    const val MANUAL_DISCOVER_COOLDOWN = 30_000L // 30s cooldown for manual discover

    // MARK: - MeshMonstis Tausch
    // Entwicklungsbereich FF00–FFFE (keine Registrierung); vor Play-Release
    // PR auf docs.meshcore.io/number_allocations (z.B. 0x0140 mapme.sh)
    const val DATA_TYPE_MAPME_TRADE = 0xFF01
    const val TRADE_OFFER_INTERVAL = 8_000L
    const val TRADE_TIMEOUT = 90_000L
    const val TRADE_MAX_PER_DAY = 5
    const val TRADE_MIN_CARD_AGE = 24 * 60 * 60 * 1000L
}

// MARK: - Command Codes (App -> Companion)
enum class CommandCode(val value: Byte) {
    APP_START(0x01),
    SEND_CHANNEL_TXT_MSG(0x03),
    REPEATERS_REQUEST(0x05),      // Discover nearby repeaters (legacy)
    GET_CONTACTS(0x04),           // 4 - request all contacts from companion
    GET_DEVICE_TIME(0x08),
    SYNC_NEXT_MESSAGE(0x0A),      // Fetch next waiting message
    GET_BATTERY_AND_STORAGE(0x14), // Get battery and storage info
    DEVICE_QUERY(0x16),           // Get device info (model, version)
    GET_CHANNELS(0x1F),
    SET_CHANNEL(0x20),
    GET_CONTACT_BY_KEY(0x1E),     // 30 - request contact details by pubkey
    SEND_CHANNEL_DATA(0x3E),      // 62 - Channel-Datagram (MeshMonstis-Tausch, direct zero-hop)
    SIGN_START(0x21),
    SIGN_DATA(0x22),
    SIGN_FINISH(0x23),
    SEND_CONTROL_DATA(0x37);      // 55 - for discover req

    companion object {
        fun fromByte(value: Byte) = entries.find { it.value == value }
    }
}

// MARK: - Response Codes (Companion -> App)
enum class ResponseCode(val value: Byte) {
    OK(0x00),
    ERR(0x01),
    CONTACTS_START(0x02),         // Start of contacts list (contains count)
    CONTACT(0x03),                // Single contact record
    CONTACTS_END(0x04),           // End of contacts list
    SELF_INFO(0x05),
    CONTACTS(0x06),
    CHANNEL_MSG_RECV(0x08),       // Channel message (basic)
    BATTERY_AND_STORAGE(0x0C),    // Battery and storage info
    DEVICE_INFO(0x0D),            // Response to deviceQuery
    CHANNEL_MSG_RECV_V3(0x11),    // Channel message with SNR
    CHANNEL_INFO(0x12),
    SIGNATURE_READY(0x13),
    SIGNATURE(0x14),
    CHANNEL_DATA_RECV(0x1B);      // 27 - eingehendes Channel-Datagram

    companion object {
        fun fromByte(value: Byte) = entries.find { it.value == value }
    }
}

// MARK: - Push Codes (Companion -> App, unsolicited)
enum class PushCode(val value: Byte) {
    ADVERT(0x80.toByte()),
    MSG_WAITING(0x83.toByte()),   // New message waiting
    LOG_RX_DATA(0x88.toByte()),
    NEW_ADVERT(0x8A.toByte()),
    DISCOVER_REPLY(0x8E.toByte()); // Response to discover repeaters (142)

    companion object {
        fun fromByte(value: Byte) = entries.find { it.value == value }
    }
}
