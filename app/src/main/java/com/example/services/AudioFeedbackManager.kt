package com.example.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    LONG_BARCODE_SIREN
}

private data class AudioPlaybackRequest(
    val tone: WmsAudioTone,
    val volume: Float
)

class AudioFeedbackManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioQueue = Channel<AudioPlaybackRequest>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch {
            for (req in audioQueue) {
                playSynthesizedSequence(req.tone, req.volume)
            }
        }
    }

    fun play(
        tone: WmsAudioTone,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean,
        volume: Float = 0.8f
    ) {
        if (vibrationEnabled) {
            triggerVibration(tone)
        }
        if (soundEnabled) {
            audioQueue.trySend(AudioPlaybackRequest(tone, volume.coerceIn(0f, 1f)))
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
            val pattern = when (tone) {
                WmsAudioTone.MATCH -> longArrayOf(0, 45)
                WmsAudioTone.EXACT_COMPLETE -> longArrayOf(0, 50, 40, 80)
                WmsAudioTone.MISMATCH_ALERT, WmsAudioTone.LONG_BARCODE_SIREN -> longArrayOf(0, 180, 70, 220)
                WmsAudioTone.SURPLUS_WARNING -> longArrayOf(0, 110, 60, 110)
                WmsAudioTone.INVOICE_LOCKED, WmsAudioTone.BLOCKED_COMPLETED -> longArrayOf(0, 150, 50, 150)
                WmsAudioTone.INVOICE_REOPENED -> longArrayOf(0, 60, 40, 60)
                WmsAudioTone.INVOICE_FINISHED -> longArrayOf(0, 70, 40, 70, 40, 140)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private suspend fun playSynthesizedSequence(tone: WmsAudioTone, volume: Float) {
        when (tone) {
            WmsAudioTone.MATCH -> playTone(920.0, 95, volume)
            WmsAudioTone.EXACT_COMPLETE -> {
                playTone(880.0, 80, volume)
                playTone(1175.0, 140, volume)
            }
            WmsAudioTone.MISMATCH_ALERT -> {
                playTone(260.0, 180, volume)
                playTone(210.0, 220, volume)
            }
            WmsAudioTone.SURPLUS_WARNING -> {
                playTone(580.0, 110, volume)
                playTone(440.0, 140, volume)
            }
            WmsAudioTone.INVOICE_LOCKED -> {
                playTone(494.0, 120, volume)
                playTone(370.0, 180, volume)
            }
            WmsAudioTone.INVOICE_REOPENED -> {
                playTone(523.0, 90, volume)
                playTone(659.0, 120, volume)
            }
            WmsAudioTone.BLOCKED_COMPLETED -> {
                playTone(310.0, 150, volume)
                playTone(260.0, 200, volume)
            }
            WmsAudioTone.INVOICE_FINISHED -> {
                playTone(659.0, 90, volume)
                playTone(784.0, 90, volume)
                playTone(1046.0, 180, volume)
            }
            WmsAudioTone.LONG_BARCODE_SIREN -> {
                playTone(740.0, 140, volume)
                playTone(390.0, 160, volume)
                playTone(740.0, 160, volume)
            }
        }
    }

    private suspend fun playTone(freqHz: Double, durationMs: Int, volume: Float) {
        var track: AudioTrack? = null
        try {
            val sampleRate = 22050
            val numSamples = (sampleRate * durationMs) / 1000
            if (numSamples <= 0) return
            val samples = ShortArray(numSamples)
            val amp = (Short.MAX_VALUE * 0.45f * volume).toInt()
            for (i in 0 until numSamples) {
                val env = when {
                    i < 120 -> i / 120.0
                    i > numSamples - 200 -> (numSamples - i) / 200.0
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                val angle = 2.0 * PI * i * freqHz / sampleRate
                samples[i] = (sin(angle) * amp * env).toInt().toShort()
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
            delay(durationMs.toLong() + 15L)
            track.stop()
        } catch (_: Throwable) {
            // Safe ignore on headless/test devices without audio HAL
        } finally {
            runCatching { track?.release() }
        }
    }
}
