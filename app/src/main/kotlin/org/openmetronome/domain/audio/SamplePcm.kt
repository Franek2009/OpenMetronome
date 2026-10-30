package org.openmetronome.domain.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Pure JVM preparation. No decoder, filesystem, Android types, or playback. */
object SamplePcm {
    /** Exact private-library WAV invariants, shared by loading and backup validation. */
    fun decodeCanonical(bytes: ByteArray): ShortArray {
        require(bytes.size in 46..(44 + MAX_FRAMES * 2))
        val audio = decodeWav(bytes)
        require(audio.rate == RATE && audio.mono.size in 48..MAX_FRAMES)
        val pcm = ShortArray(audio.mono.size) { (audio.mono[it] * 32768).toInt().toShort() }
        require(pcm.first() == 0.toShort() && pcm.last() == 0.toShort())
        require(pcm.any { it != 0.toShort() })
        require(pcm.all { kotlin.math.abs(it.toInt()) <= ClickGenerator.PEAK_SAMPLE })
        require(kotlin.math.abs(pcm.map { it.toDouble() }.average()) < 655)
        require(bytes.contentEquals(encodeCanonical(pcm)))
        return pcm
    }

    const val RATE = 48000
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_SECONDS = 10
    const val MAX_FRAMES = RATE * 40 / 1000
    data class Audio(val rate: Int, val mono: DoubleArray)
    data class Prepared(val pcm: ShortArray, val trimmed: Boolean)

    fun prepare(audio: Audio): Prepared {
        require(audio.rate in 8000..192000 && audio.mono.isNotEmpty()) { "Nieobsługiwane próbkowanie lub pusty plik." }
        require(audio.mono.size <= audio.rate * MAX_SECONDS) { "Sample może mieć najwyżej 10 sekund." }
        require(audio.mono.all { it.isFinite() }) { "Nieprawidłowe wartości PCM." }
        val mean = audio.mono.average()
        val centered = DoubleArray(audio.mono.size) { audio.mono[it] - mean }
        val peak = centered.maxOf { abs(it) }
        require(peak > 1e-7) { "Plik nie zawiera słyszalnego sygnału." }
        val onset = (centered.indexOfFirst { abs(it) >= peak * .01 } - (audio.rate * .00025).toInt()).coerceAtLeast(0)
        val frames = min(centered.size - onset, audio.rate * 40 / 1000)
        require(frames >= audio.rate / 1000) { "Sample jest za krótki (minimum 1 ms)." }
        val segment = centered.copyOfRange(onset, onset + frames)
        val result = finish(resample(segment, audio.rate.toDouble(), RATE), RATE)
        return Prepared(result, onset > 0 || onset + frames < centered.size)
    }

    fun click(canonical: ShortArray, rate: Int, accented: Boolean): ShortArray {
        require(canonical.isNotEmpty() && canonical.size <= MAX_FRAMES && rate in 8000..192000)
        if (!accented && rate == RATE) return canonical.copyOf()
        val input = DoubleArray(canonical.size) { canonical[it].toDouble() }
        return finish(resample(input, RATE * if (accented) 1.22 else 1.0, rate), rate)
    }

    /** Windowed-sinc low-pass interpolation, with reduced cutoff when downsampling/pitching up. */
    private fun resample(input: DoubleArray, sourceRate: Double, targetRate: Int): DoubleArray {
        val ratio = sourceRate / targetRate
        val count = max(1, (input.size / ratio).roundToInt())
        val cutoff = min(1.0, 1.0 / ratio) * .9
        val radius = ceil(24 / cutoff).toInt()
        return DoubleArray(count) { i ->
            val position = i * ratio
            val center = floor(position).toInt()
            var sum = 0.0
            var weights = 0.0
            for (j in max(0, center - radius)..min(input.lastIndex, center + radius)) {
                val distance = position - j
                val x = PI * cutoff * distance
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(x) / x
                val window = .5 + .5 * cos(PI * distance / (radius + 1))
                val weight = cutoff * sinc * window
                sum += input[j] * weight
                weights += weight
            }
            if (abs(weights) > 1e-12) sum / weights else 0.0
        }
    }

    private fun finish(input: DoubleArray, rate: Int): ShortArray {
        val edges = DoubleArray(input.size) { i ->
            min(1.0, i / (rate * .00025)) * min(1.0, (input.lastIndex - i) / (rate * .0015))
        }
        val wave = DoubleArray(input.size) { input[it] * edges[it] }
        require(edges.sum() > 0) { "Sample jest za krótki." }
        val dc = wave.sum() / edges.sum()
        for (i in wave.indices) wave[i] -= dc * edges[i]
        val peak = wave.maxOf { abs(it) }
        require(peak.isFinite() && peak > 1e-9) { "Plik nie zawiera słyszalnego sygnału." }
        return ShortArray(wave.size) { (wave[it] / peak * ClickGenerator.PEAK_SAMPLE).roundToInt().toShort() }
    }

    /** Bounded RIFF/WAVE reader: PCM 8/16/24/32-bit and IEEE float32, mono or stereo. */
    fun decodeWav(bytes: ByteArray): Audio {
        require(bytes.size in 44..MAX_BYTES) { "Nieprawidłowy lub za duży WAV." }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE") { "Nieobsługiwany format WAV." }
        val end = 8L + (b.getInt(4).toLong() and 0xffffffffL)
        require(end in 44..bytes.size.toLong()) { "Uszkodzony nagłówek WAV." }
        var format = 0; var channels = 0; var rate = 0; var bits = 0; var align = 0
        var dataOffset = -1; var dataLength = 0
        var cursor = 12L
        while (cursor + 8 <= end) {
            val pos = cursor.toInt()
            val length = b.getInt(pos + 4).toLong() and 0xffffffffL
            val next = cursor + 8 + length
            require(next <= end) { "Uszkodzony fragment WAV." }
            when (tag(pos)) {
                "fmt " -> {
                    require(length >= 16)
                    format = b.getShort(pos + 8).toInt() and 65535
                    channels = b.getShort(pos + 10).toInt() and 65535
                    rate = b.getInt(pos + 12)
                    align = b.getShort(pos + 20).toInt() and 65535
                    bits = b.getShort(pos + 22).toInt() and 65535
                }
                "data" -> { require(dataOffset == -1); dataOffset = pos + 8; dataLength = length.toInt() }
            }
            cursor = next + (length and 1)
        }
        require(format == 1 || format == 3) { "WAV wymaga liniowego PCM lub float32." }
        require(channels in 1..2 && rate in 8000..192000) { "Wymagany dźwięk mono/stereo, 8–192 kHz." }
        require((format == 1 && bits in listOf(8, 16, 24, 32)) || (format == 3 && bits == 32)) { "Nieobsługiwana rozdzielczość WAV." }
        require(align == channels * bits / 8 && dataOffset >= 0 && dataLength % align == 0)
        require(dataLength / align in 1..rate * MAX_SECONDS) { "Sample może mieć najwyżej 10 sekund." }
        b.position(dataOffset)
        val frames = dataLength / align
        val mono = DoubleArray(frames) {
            var sum = 0.0
            repeat(channels) {
                sum += when {
                    format == 3 -> b.float.toDouble()
                    bits == 8 -> ((b.get().toInt() and 255) - 128) / 128.0
                    bits == 16 -> b.short / 32768.0
                    bits == 24 -> {
                        val low = b.get().toInt() and 255
                        val mid = b.get().toInt() and 255
                        val high = b.get().toInt()
                        (low or (mid shl 8) or (high shl 16)) / 8388608.0
                    }
                    else -> b.int / 2147483648.0
                }
            }
            sum / channels
        }
        require(mono.all { it.isFinite() }) { "Nieprawidłowe wartości PCM." }
        return Audio(rate, mono)
    }

    fun encodeCanonical(pcm: ShortArray): ByteArray {
        require(pcm.size in 1..MAX_FRAMES)
        return ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size * 2); pcm.forEach { putShort(it) }
        }.array()
    }
}
