package dev.asik.devicebridge.collectors

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.AudioReading
import dev.asik.devicebridge.util.ErrorLog
import dev.asik.devicebridge.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

class AudioCollector(
    private val context: Context,
    private val hub: StreamHub,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun start() {
        if (job != null) return
        if (!PermissionHelper.isGranted(context, android.Manifest.permission.RECORD_AUDIO)) {
            ErrorLog.warn("audio_permission", "Audio collector not started — permission denied")
            return
        }

        job = scope.launch(Dispatchers.IO) {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                ErrorLog.error("audio_init_failed", "AudioRecord initialization failed")
                return@launch
            }

            try {
                recorder.startRecording()
                val buffer = ShortArray(bufferSize)
                var lastPublishTime = System.currentTimeMillis()
                var accumulatedSum = 0.0
                var accumulatedSamples = 0L
                var accumulatedPeak = 0f

                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            val sample = buffer[i].toFloat()
                            accumulatedSum += sample * sample
                            val abs = if (sample < 0) -sample else sample
                            if (abs > accumulatedPeak) accumulatedPeak = abs
                        }
                        accumulatedSamples += read

                        val now = System.currentTimeMillis()
                        if (now - lastPublishTime >= 150L && accumulatedSamples > 0) {
                            val rms = sqrt(accumulatedSum / accumulatedSamples)
                            val rmsDb = if (rms > 0) 20 * log10(rms / 32767.0).toFloat() else -100f
                            val peakDb = if (accumulatedPeak > 0) 20 * log10(accumulatedPeak / 32767.0).toFloat() else -100f

                            hub.publishAudio(AudioReading(rmsDb, peakDb, now))

                            lastPublishTime = now
                            accumulatedSum = 0.0
                            accumulatedSamples = 0L
                            accumulatedPeak = 0f
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLog.error("audio_collect_error", e.message ?: "error in audio loop")
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
