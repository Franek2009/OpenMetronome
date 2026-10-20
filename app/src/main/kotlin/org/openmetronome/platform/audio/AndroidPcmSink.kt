package org.openmetronome.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.openmetronome.audio.AudioEngineException
import org.openmetronome.audio.AudioFailure
import org.openmetronome.audio.PcmSink
import org.openmetronome.audio.PcmSinkFactory
import org.openmetronome.audio.PlaybackHeadCounter

fun metronomeAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

class AndroidPcmSinkFactory(private val manager: AudioManager) : PcmSinkFactory {
    override fun create(): PcmSink {
        var track: AudioTrack? = null
        try {
            val rate = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 48000
            val block = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                ?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 256
            val minimumBytes = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBytes <= 0) throw AudioEngineException(AudioFailure.INITIALIZATION)
            val frames = maxOf((minimumBytes + 1) / 2, block * 4)
            track = AudioTrack.Builder()
                .setAudioAttributes(metronomeAudioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(frames * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw AudioEngineException(AudioFailure.INITIALIZATION)
            }
            return AndroidPcmSink(track, rate, block)
        } catch (error: Exception) {
            track?.release()
            throw AudioEngineException(AudioFailure.INITIALIZATION, error)
        }
    }
}

private class AndroidPcmSink(
    private val track: AudioTrack,
    override val sampleRate: Int,
    override val blockFrames: Int,
) : PcmSink {
    override val bufferFrames: Int = track.bufferSizeInFrames
    override val underruns: Int get() = track.underrunCount
    private val head = PlaybackHeadCounter()
    override val playedFrames: Long get() = head.update(track.playbackHeadPosition)
    override fun write(samples: ShortArray, offset: Int, count: Int): Int {
        val result = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
        if (result < 0) throw AudioEngineException(
            if (result == AudioTrack.ERROR_DEAD_OBJECT) AudioFailure.DEVICE_LOST
            else AudioFailure.WRITE_FAILED,
        )
        return result
    }
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun close() = track.release()
}
