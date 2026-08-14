package sh.mapme.mapper.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.mapme.mapper.Constants
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * TradeManager — Geister-Tausch über Channel-Datagram (Task 9).
 *
 * NUR Direktfunk (zero-hop): zwei Mapper auf einem Parkplatz, ohne Internet,
 * ohne Repeater. Geflutete Frames verwirft schon der BleManager.
 *
 * Handshake:  A OFFER (alle 8 s, max 90 s, to=broadcast) →
 *             B prüft Sig + zeigt Prompt → B ACCEPT (gleiche nonce, to=A) →
 *             A prüft ACCEPT → A entfernt Karte, B legt sie an.
 *
 * Regeln: nur source=caught und ≥ 24 h alt, max 5 Tausche/UTC-Tag,
 * anonym tauscht nicht, Gipfelbuch ist nicht tauschbar (ist nie eine Karte).
 */
class TradeManager(
    private val bleManager: BleManager,
    private val albumStore: AlbumStore
) {
    companion object { private const val TAG = "TradeManager" }

    sealed class State {
        object Idle : State()
        data class Offering(val card: AlbumCard, val secondsLeft: Int) : State()
        data class IncomingOffer(val frame: TradeFrame, val meta: TradeMeta?, val snrX4: Int) : State()
        data class Accepting(val ghostId: Long) : State()
        data class Done(val gave: Boolean, val card: AlbumCard?) : State()
        data class Failed(val reason: String) : State()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val rng = SecureRandom()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var offerJob: Job? = null
    private var activeNonce: ByteArray? = null
    private var activeCard: AlbumCard? = null
    private val seenNonces = mutableSetOf<String>()

    init {
        scope.launch {
            bleManager.channelDatagrams.collect { dg ->
                if (dg.dataType == Constants.DATA_TYPE_MAPME_TRADE) handleFrame(dg)
            }
        }
    }

    private fun myPubkey(): ByteArray? = bleManager.selfInfo.value?.publicKey

    private fun utcDay(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun tradesUsedToday(): Int {
        val (day, count) = albumStore.tradesToday
        return if (day == utcDay()) count else 0
    }

    private fun bumpTradeCount() {
        albumStore.tradesToday = Pair(utcDay(), tradesUsedToday() + 1)
    }

    /** Verfügbarkeit fürs UI: null = ok, sonst Grund fürs Ausgrauen. */
    fun tradeBlocker(card: AlbumCard, privacyMode: String): String? = when {
        privacyMode == "anonym" -> "Anonym-Modus: kein Tausch"
        !bleManager.isConnected.value -> "Kein Companion verbunden"
        !bleManager.supportsChannelData.value -> "Firmware zu alt (braucht v1.12+)"
        !card.tradeable() -> "Karte noch keine 24 h alt oder getauscht"
        tradesUsedToday() >= Constants.TRADE_MAX_PER_DAY -> "Tageslimit erreicht (5 Tausche)"
        else -> null
    }

    // MARK: - A-Seite: anbieten

    fun startOffer(card: AlbumCard, privacyMode: String) {
        tradeBlocker(card, privacyMode)?.let { _state.value = State.Failed(it); return }
        val me = myPubkey() ?: run { _state.value = State.Failed("Kein Geräte-Key"); return }

        offerJob?.cancel()
        offerJob = scope.launch {
            val nonce = ByteArray(8).also { rng.nextBytes(it) }
            activeNonce = nonce
            activeCard = card

            val unsigned = TradeFrame(TradeType.OFFER, card.ghostId, nonce, me,
                ByteArray(32), ByteArray(64))
            val sig = bleManager.signBytes(TradePacket.signedRegion(TradePacket.encode(unsigned)))
                ?: run { _state.value = State.Failed("Companion signiert nicht"); return@launch }
            val frame = TradePacket.encodeWithMeta(
                unsigned.copy(signature = sig),
                TradeMeta(card.kind, card.points, card.spriteIndex))

            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < Constants.TRADE_TIMEOUT) {
                if (!bleManager.supportsChannelData.value) {
                    _state.value = State.Failed("Firmware kann kein 0x3E — Update nötig")
                    return@launch
                }
                bleManager.sendChannelDatagram(0, Constants.DATA_TYPE_MAPME_TRADE, frame)
                val left = ((Constants.TRADE_TIMEOUT -
                    (System.currentTimeMillis() - started)) / 1000).toInt()
                _state.value = State.Offering(card, left)
                delay(Constants.TRADE_OFFER_INTERVAL)
            }
            // Timeout: ABORT hinterher (best effort), fertig
            sendAbort(card.ghostId, nonce, me)
            activeNonce = null; activeCard = null
            _state.value = State.Failed("Niemand in Rufweite angenommen (90 s)")
        }
    }

    fun cancelOffer() {
        val card = activeCard; val nonce = activeNonce
        offerJob?.cancel(); offerJob = null
        val me = myPubkey()
        if (card != null && nonce != null && me != null) sendAbort(card.ghostId, nonce, me)
        activeNonce = null; activeCard = null
        _state.value = State.Idle
    }

    private fun sendAbort(ghostId: Long, nonce: ByteArray, me: ByteArray) {
        scope.launch {
            try {
                val unsigned = TradeFrame(TradeType.ABORT, ghostId, nonce, me,
                    ByteArray(32), ByteArray(64))
                val sig = bleManager.signBytes(
                    TradePacket.signedRegion(TradePacket.encode(unsigned))) ?: return@launch
                bleManager.sendChannelDatagram(0, Constants.DATA_TYPE_MAPME_TRADE,
                    TradePacket.encode(unsigned.copy(signature = sig)))
            } catch (e: Exception) { Log.d(TAG, "abort send failed", e) }
        }
    }

    // MARK: - B-Seite: annehmen / ablehnen

    fun acceptIncoming(privacyMode: String) {
        val incoming = _state.value as? State.IncomingOffer ?: return
        if (privacyMode == "anonym") { _state.value = State.Failed("Anonym-Modus: kein Tausch"); return }
        if (tradesUsedToday() >= Constants.TRADE_MAX_PER_DAY) {
            _state.value = State.Failed("Tageslimit erreicht (5 Tausche)"); return
        }
        val me = myPubkey() ?: return
        val offer = incoming.frame

        scope.launch {
            _state.value = State.Accepting(offer.ghostId)
            val unsigned = TradeFrame(TradeType.ACCEPT, offer.ghostId, offer.nonce, me,
                offer.fromPubkey, ByteArray(64))
            val sig = bleManager.signBytes(TradePacket.signedRegion(TradePacket.encode(unsigned)))
                ?: run { _state.value = State.Failed("Companion signiert nicht"); return@launch }
            val frame = TradePacket.encode(unsigned.copy(signature = sig))
            // ACCEPT 3x mit Abstand — Direktfunk, kein ACK auf Layer 2
            repeat(3) {
                bleManager.sendChannelDatagram(0, Constants.DATA_TYPE_MAPME_TRADE, frame)
                delay(2_000)
            }
            val meta = incoming.meta
            val card = AlbumCard(
                ghostId = offer.ghostId,
                kind = meta?.kind ?: GhostKind.IRRLICHT,
                name = null,
                points = meta?.points ?: 0,
                lat = 0.0, lon = 0.0,
                source = "traded",
                caughtAt = System.currentTimeMillis(),
                fromPubkey = offer.fromPubkey.joinToString("") { "%02x".format(it) },
                spriteIndex = meta?.spriteIndex ?: (offer.ghostId % 9).toInt())
            albumStore.addTraded(card, card.fromPubkey!!)
            bumpTradeCount()
            _state.value = State.Done(gave = false, card = card)
        }
    }

    fun declineIncoming() { _state.value = State.Idle }

    fun reset() { _state.value = State.Idle }

    // MARK: - Frame-Handling

    private fun handleFrame(dg: BleManager.ChannelDatagram) {
        val frame = try { TradePacket.decode(dg.payload) } catch (e: Exception) {
            Log.d(TAG, "kein MMG1: ${e.message}"); return
        }
        val me = myPubkey() ?: return
        if (frame.fromPubkey.contentEquals(me)) return   // eigenes Echo

        // Ohne gültige Ed25519-Sig verwerfen (Layer 2 ist unverifiziert)
        if (!TradeCrypto.verify(frame.fromPubkey,
                TradePacket.signedRegion(dg.payload), frame.signature)) {
            Log.d(TAG, "Trade-Frame mit ungültiger Sig verworfen"); return
        }

        when (frame.type) {
            TradeType.OFFER -> {
                if (_state.value !is State.Idle) return
                if (albumStore.has(frame.ghostId)) return   // hab ich schon
                val nonceKey = frame.nonce.joinToString("") { "%02x".format(it) }
                if (!seenNonces.add(nonceKey)) return       // Replay/Wiederholung
                _state.value = State.IncomingOffer(frame, TradePacket.decodeMeta(dg.payload), dg.snrX4)
            }
            TradeType.ACCEPT -> {
                val nonce = activeNonce ?: return
                val card = activeCard ?: return
                if (!frame.nonce.contentEquals(nonce)) return
                if (frame.ghostId != card.ghostId) return
                if (!frame.toPubkey.contentEquals(me)) return
                offerJob?.cancel(); offerJob = null
                activeNonce = null; activeCard = null
                albumStore.remove(card.ghostId)
                bumpTradeCount()
                _state.value = State.Done(gave = true, card = card)
            }
            TradeType.ABORT -> {
                val incoming = _state.value as? State.IncomingOffer ?: return
                if (incoming.frame.nonce.contentEquals(frame.nonce)) _state.value = State.Idle
            }
        }
    }
}
