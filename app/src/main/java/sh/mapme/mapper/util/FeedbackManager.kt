package sh.mapme.mapper.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * FeedbackManager - Audio and haptic feedback for RX/TX events
 * Mirrors the sound design from connect.html
 */
class FeedbackManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Settings (can be toggled by user)
    var soundEnabled = true
    var vibrateEnabled = true

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
    }

    /**
     * Play RX feedback - short high beep (880Hz, 100ms)
     */
    fun playRx() {
        if (soundEnabled) {
            scope.launch { playTone(880f, 880f, 100) }
        }
        vibrate(50)
    }

    /**
     * Play TX feedback - rising tone (440Hz -> 880Hz, 150ms)
     */
    fun playTx() {
        if (soundEnabled) {
            scope.launch { playTone(440f, 880f, 150) }
        }
        vibrate(100)
    }

    /**
     * Play new hex discovered feedback - higher sweep (660Hz -> 1320Hz, 200ms)
     */
    fun playHex() {
        if (soundEnabled) {
            scope.launch { playTone(660f, 1320f, 200) }
        }
        vibrate(150)
    }

    /**
     * Play repeater advertisement feedback - two-tone (523Hz -> 659Hz, 200ms)
     */
    fun playAdvert() {
        if (soundEnabled) {
            scope.launch { playTone(523f, 659f, 200) }
        }
        vibrate(75)
    }

    /**
     * Play TX confirmation feedback - success sound
     */
    fun playTxConfirm() {
        if (soundEnabled) {
            scope.launch { playTone(880f, 1760f, 100) }
        }
        vibrate(200)
    }

    /**
     * Play connection success feedback
     */
    fun playConnected() {
        if (soundEnabled) {
            scope.launch {
                playTone(440f, 880f, 100)
                delay(120)
                playTone(880f, 1320f, 100)
            }
        }
        vibrate(100)
    }

    /**
     * Play TX mode started feedback - warning descending tones
     */
    fun playTxModeStarted() {
        if (soundEnabled) {
            scope.launch {
                playTone(880f, 880f, 150)
                delay(180)
                playTone(660f, 660f, 150)
            }
        }
        vibrate(100)
    }

    /**
     * Play discover reply feedback - repeater found
     */
    fun playDiscoverReply() {
        if (soundEnabled) {
            scope.launch { playTone(1000f, 1000f, 80) }
        }
        vibrate(75)
    }

    /**
     * Vibrate for specified duration
     */
    fun vibrate(durationMs: Long) {
        if (!vibrateEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }

    /**
     * Generate and play a tone with frequency sweep
     */
    private fun playTone(startFreq: Float, endFreq: Float, durationMs: Int) {
        try {
            val numSamples = (SAMPLE_RATE * durationMs / 1000)
            val samples = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toFloat() / numSamples
                // Interpolate frequency
                val freq = startFreq + (endFreq - startFreq) * t
                // Fade out envelope
                val envelope = 1f - (t * t)
                // Generate sample
                val angle = 2.0 * PI * freq * i / SAMPLE_RATE
                samples[i] = (sin(angle) * Short.MAX_VALUE * 0.3 * envelope).toInt().toShort()
            }

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(samples, 0, samples.size)
            audioTrack.play()

            // Release after playback
            scope.launch {
                delay(durationMs.toLong() + 50)
                audioTrack.release()
            }
        } catch (e: Exception) {
            // Ignore audio errors
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
