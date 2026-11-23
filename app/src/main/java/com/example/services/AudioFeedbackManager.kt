package com.example.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

enum class WmsAudioTone {
    MATCH,
    EXACT_COMPLETE,
    MISMATCH_ALERT,
    SURPLUS_WARNING,
    INVOICE_LOCKED,
    INVOICE_REOPENED,
    BLOCKED_COMPLETED,
    INVOICE_FINISHED,
    SESSION_COMPLETED_FANFARE,
    LONG_BARCODE_SIREN
}

data class ToneNote(
    val frequencyHz: Double,
    val durationMs: Int
)

data class AudioCueSpec(
    val notes: List<ToneNote>,
    val vibrationPattern: LongArray,
    val fallbackGeneratorTone: Int,
    val addHarmonicClarity: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioCueSpec) return false
        return notes == other.notes &&
            vibrationPattern.contentEquals(other.vibrationPattern) &&
            fallbackGeneratorTone == other.fallbackGeneratorTone &&
            addHarmonicClarity == other.addHarmonicClarity
    }

    override fun hashCode(): Int {
        var result = notes.hashCode()
        result = 31 * result + vibrationPattern.contentHashCode()
        result = 31 * result + fallbackGeneratorTone
        result = 31 * result + addHarmonicClarity.hashCode()
        return result
    }
}

private data class AudioPlaybackRequest(
    val tone: WmsAudioTone,
    val volume: Float
)

class AudioFeedbackManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioQueue = Channel<AudioPlaybackRequest>(
        capacity = 6,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            for (req in audioQueue) {
                playCue(req.tone, req.volume)
            }
        }
    }

    fun play(
        tone: WmsAudioTone,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        volume: Float = 0.85f
    ) {
        if (vibrationEnabled) {
            triggerVibration(tone)
        }
        if (soundEnabled) {
            val safeVolume = volume.coerceIn(0.05f, 1f)
            audioQueue.trySend(AudioPlaybackRequest(tone, safeVolume))
        }
    }

    fun release() {
        audioQueue.close()
        scope.cancel()
    }

    private fun triggerVibration(tone: WmsAudioTone) {
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager ?: return
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            }
            if (!vibrator.hasVibrator()) return
            val pattern = specFor(tone).vibrationPattern
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private suspend fun playCue(tone: WmsAudioTone, volume: Float) {
        val spec = specFor(tone)
        var anyTrackPlayed = false
        for (note in spec.notes) {
            val played = playPcmTone(
                freqHz = note.frequencyHz,
                durationMs = note.durationMs,
                volume = volume,
                addHarmonic = spec.addHarmonicClarity
            )
            if (played) anyTrackPlayed = true
        }
        if (!anyTrackPlayed) {
            playToneGeneratorFallback(spec.fallbackGeneratorTone, volume)
        }
    }

    private suspend fun playPcmTone(
        freqHz: Double,
        durationMs: Int,
        volume: Float,
        addHarmonic: Boolean
    ): Boolean {
        var track: AudioTrack? = null
        return try {
            val sampleRate = 22050
            val numSamples = (sampleRate * durationMs) / 1000
            if (numSamples <= 0) return false
            val samples = ShortArray(numSamples)
            val amp = Short.MAX_VALUE * 0.50 * volume
            for (i in 0 until numSamples) {
                val env = when {
                    i < 110 -> i / 110.0
                    i > numSamples - 180 -> (numSamples - i) / 180.0
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                val fundamental = sin(2.0 * PI * i * freqHz / sampleRate)
                val wave = if (addHarmonic) {
                    fundamental * 0.80 + sin(2.0 * PI * i * (freqHz * 2.0) / sampleRate) * 0.20
                } else {
                    fundamental
                }
                samples[i] = (wave * amp * env).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(samples, 0, samples.size)
            track.play()
            delay(durationMs.toLong() + 12L)
            track.stop()
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { track?.release() }
        }
    }

    private fun playToneGeneratorFallback(toneType: Int, volume: Float) {
        runCatching {
            val pct = (volume * 100f).toInt().coerceIn(10, 100)
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, pct)
            try {
                generator.startTone(toneType, 160)
            } finally {
                generator.release()
            }
        }
    }

    companion object {
        fun specFor(tone: WmsAudioTone): AudioCueSpec {
            return when (tone) {
                WmsAudioTone.MATCH -> AudioCueSpec(
                    notes = listOf(ToneNote(1046.5, 85)),
                    vibrationPattern = longArrayOf(0, 45),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_BEEP
                )
                WmsAudioTone.EXACT_COMPLETE -> AudioCueSpec(
                    notes = listOf(ToneNote(880.0, 80), ToneNote(1318.5, 145)),
                    vibrationPattern = longArrayOf(0, 50, 40, 85),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_ACK
                )
                WmsAudioTone.MISMATCH_ALERT -> AudioCueSpec(
                    notes = listOf(ToneNote(310.0, 180), ToneNote(220.0, 220)),
                    vibrationPattern = longArrayOf(0, 200, 70, 240),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_NACK,
                    addHarmonicClarity = false
                )
                WmsAudioTone.SURPLUS_WARNING -> AudioCueSpec(
                    notes = listOf(ToneNote(620.0, 110), ToneNote(420.0, 150)),
                    vibrationPattern = longArrayOf(0, 115, 55, 115),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_BEEP2
                )
                WmsAudioTone.INVOICE_LOCKED -> AudioCueSpec(
                    notes = listOf(ToneNote(493.88, 110), ToneNote(370.0, 170)),
                    vibrationPattern = longArrayOf(0, 140, 50, 140),
                    fallbackGeneratorTone = ToneGenerator.TONE_SUP_ERROR
                )
                WmsAudioTone.INVOICE_REOPENED -> AudioCueSpec(
                    notes = listOf(ToneNote(523.25, 85), ToneNote(659.25, 115)),
                    vibrationPattern = longArrayOf(0, 55, 40, 55),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_PROMPT
                )
                WmsAudioTone.BLOCKED_COMPLETED -> AudioCueSpec(
                    notes = listOf(ToneNote(330.0, 140), ToneNote(246.94, 190)),
                    vibrationPattern = longArrayOf(0, 160, 60, 160),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_NACK,
                    addHarmonicClarity = false
                )
                WmsAudioTone.INVOICE_FINISHED -> AudioCueSpec(
                    notes = listOf(ToneNote(659.25, 85), ToneNote(783.99, 85), ToneNote(1046.5, 175)),
                    vibrationPattern = longArrayOf(0, 70, 40, 70, 40, 140),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_ACK
                )
                WmsAudioTone.SESSION_COMPLETED_FANFARE -> AudioCueSpec(
                    notes = listOf(
                        ToneNote(587.33, 85),
                        ToneNote(739.99, 85),
                        ToneNote(880.0, 85),
                        ToneNote(1174.66, 220)
                    ),
                    vibrationPattern = longArrayOf(0, 80, 40, 80, 40, 190),
                    fallbackGeneratorTone = ToneGenerator.TONE_PROP_ACK
                )
                WmsAudioTone.LONG_BARCODE_SIREN -> AudioCueSpec(
                    notes = listOf(ToneNote(780.0, 130), ToneNote(380.0, 150), ToneNote(780.0, 150)),
                    vibrationPattern = longArrayOf(0, 180, 70, 220),
                    fallbackGeneratorTone = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                )
            }
        }
    }
}
