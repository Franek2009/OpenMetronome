package org.openmetronome.platform.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.openmetronome.domain.audio.SamplePcm

/** Decode only. No MediaPlayer, AudioTrack, audio focus, preview, or volume changes. */
class AndroidSampleDecoder(private val context: Context) {
    fun name(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.take(160) else null
        }
    }.getOrNull() ?: "Mój sample"

    fun decode(uri: Uri): SamplePcm.Audio = context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Nie można otworzyć pliku." }
        val bytes = ByteArrayOutputStream()
        val block = ByteArray(8192)
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (true) {
            check(SystemClock.elapsedRealtime() < deadline) { "Odczyt pliku trwa zbyt długo." }
            val count = input.read(block)
            if (count < 0) break
            require(bytes.size() + count <= SamplePcm.MAX_BYTES) { "Plik może mieć najwyżej 10 MiB." }
            bytes.write(block, 0, count)
        }
        decodeBytes(bytes.toByteArray())
    }

    internal fun decodeBytes(bytes: ByteArray): SamplePcm.Audio {
        require(bytes.isNotEmpty() && bytes.size <= SamplePcm.MAX_BYTES) { "Nieprawidłowy lub za duży plik audio." }
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") return SamplePcm.decodeWav(bytes)
        // Container detection, never trust extension or document MIME alone.
        val ogg = bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "OggS"
        val mp3 = bytes.size >= 3 && (String(bytes, 0, 3, Charsets.US_ASCII) == "ID3" ||
            (bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224))
        require(ogg || mp3) { "Wybierz WAV, MP3 lub OGG (Vorbis/Opus)." }
        val temp = File.createTempFile("sample-decode-", ".audio", context.cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            temp.writeBytes(bytes)
            extractor.setDataSource(temp.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Brak ścieżki audio.")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
            require(mime in listOf("audio/mpeg", "audio/vorbis", "audio/opus")) { "Nieobsługiwany kodek audio." }
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                require(format.getLong(MediaFormat.KEY_DURATION) <= 10_000_000L) { "Sample może mieć najwyżej 10 sekund." }
            }
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(mime))
            codec = decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var fed = false
            var done = false
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!done) {
                check(SystemClock.elapsedRealtime() < deadline) { "Dekodowanie trwa zbyt długo." }
                if (!fed) {
                    val index = decoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fed = true
                        } else {
                            decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        val nextRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val nextChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val nextEncoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(pcm.size() == 0 || (rate == nextRate && channels == nextChannels && encoding == nextEncoding))
                        rate = nextRate; channels = nextChannels; encoding = nextEncoding
                    }
                    else -> if (index >= 0) {
                        require(rate in 8000..192000 && channels in 1..2) { "Wymagany dźwięk mono/stereo, 8–192 kHz." }
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                        val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        require(pcm.size().toLong() + info.size <= rate.toLong() * channels * width * 10) { "Sample może mieć najwyżej 10 sekund." }
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = requireNotNull(decoder.getOutputBuffer(index))
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val block = ByteArray(info.size); output.get(block); pcm.write(block)
                        }
                        done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            val buffer = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            require(buffer.remaining() % (channels * width) == 0)
            val mono = DoubleArray(buffer.remaining() / (channels * width)) {
                var sum = 0.0
                repeat(channels) { sum += if (width == 4) buffer.float.toDouble() else buffer.short / 32768.0 }
                sum / channels
            }
            return SamplePcm.Audio(rate, mono)
        } catch (error: MediaCodec.CodecException) {
            throw IllegalArgumentException("Nie można zdekodować tego pliku MP3/OGG na tym urządzeniu.", error)
        } catch (error: java.io.IOException) {
            throw IllegalArgumentException("Nie można odczytać pliku MP3/OGG. Plik może być uszkodzony.", error)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }; temp.delete() // Only this decoder's temporary copy.
        }
    }
}
