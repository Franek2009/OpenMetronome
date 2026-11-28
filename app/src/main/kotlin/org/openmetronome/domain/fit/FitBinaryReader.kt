package org.openmetronome.domain.fit

import java.io.InputStream
import java.security.MessageDigest

internal enum class FitFailureKind { CORRUPT, UNSUPPORTED, INCONSISTENT, LIMIT }

internal class FitReadException(val kind: FitFailureKind, reason: String) : Exception(reason)

internal data class FitReadLimits(
    val files: Int = 20,
    val fileBytes: Int = 8 * 1024 * 1024,
    val batchBytes: Int = 32 * 1024 * 1024,
    val sessions: Int = 100,
    val recordsPerFile: Int = 500_000,
    val timerEventsPerFile: Int = 16_384,
) {
    init {
        require(files > 0 && fileBytes >= 14 && batchBytes >= 14 && sessions > 0 &&
            recordsPerFile > 0 && timerEventsPerFile > 0)
    }
}

/** A batch belongs to one sequential import. Invalid files still consume the bytes read. */
internal class FitReadBudget(val limits: FitReadLimits = FitReadLimits()) {
    private var files = 0
    private var bytes = 0L
    private var sessions = 0
    val remainingBytes: Int get() = (limits.batchBytes - bytes).coerceAtLeast(0).toInt()

    fun startFile() {
        if (remainingBytes == 0) fitFailure(FitFailureKind.LIMIT, "FIT batch byte limit exceeded")
        if (files >= limits.files) fitFailure(FitFailureKind.LIMIT, "Too many FIT files")
        files++
    }

    fun consumeBytes(count: Int) {
        bytes += count
        if (bytes > limits.batchBytes) fitFailure(FitFailureKind.LIMIT, "FIT batch byte limit exceeded")
    }

    fun consumeSession() {
        if (sessions >= limits.sessions) fitFailure(FitFailureKind.LIMIT, "Too many FIT sessions")
        sessions++
    }
}

internal fun fitFailure(kind: FitFailureKind, reason: String): Nothing = throw FitReadException(kind, reason)

internal data class FitMessage(val number: Int, val fields: Map<Int, Long>) {
    operator fun get(field: Int): Long? = fields[field]
}

/** FIT wire reader. Only emits selected scalar fields; all other bytes still feed CRC and SHA. */
internal object FitBinaryReader {
    private const val TIMESTAMP = 253
    private const val ENUM = 0
    private const val UINT8 = 2
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    internal const val FILE_ID = 0
    internal const val SESSION = 18
    internal const val EVENT = 21
    internal const val ACTIVITY = 34

    private val selectedTypes = mapOf(
        FILE_ID to mapOf(0 to ENUM),
        SESSION to mapOf(2 to UINT32, 5 to ENUM, 6 to ENUM, 7 to UINT32, 8 to UINT32, 27 to UINT8),
        EVENT to mapOf(0 to ENUM, 1 to ENUM, 4 to UINT8),
        ACTIVITY to mapOf(1 to UINT16),
    )
    private val widths = mapOf(0 to 1, 1 to 1, 2 to 1, 0x83 to 2, 0x84 to 2, 0x85 to 4, 0x86 to 4,
        7 to 1, 0x88 to 4, 0x89 to 8, 0x0A to 1, 0x8B to 2, 0x8C to 4, 0x0D to 1,
        0x8E to 8, 0x8F to 8, 0x90 to 8)

    /** The caller owns the stream. No decoded result is valid until this returns the digest. */
    fun read(input: InputStream, budget: FitReadBudget, checkCancelled: () -> Unit,
        onMessage: (FitMessage) -> Unit): String {
        checkCancelled()
        budget.startFile()
        val wire = Wire(input, budget, checkCancelled)
        val header = IntArray(12) { wire.byte() }
        if (!hasMagic(header)) corrupt("Missing FIT signature")
        val headerSize = header[0]
        if (headerSize < 12 || headerSize == 13) corrupt("Invalid FIT header size")
        if (headerSize != 12 && headerSize != 14) unsupported("Unsupported FIT header size")
        val protocol = header[1]
        val bodySize = (0..3).sumOf { header[4 + it].toLong() shl (it * 8) }
        if (bodySize + headerSize + 2 > budget.limits.fileBytes) fitFailure(FitFailureKind.LIMIT, "FIT file byte limit exceeded")
        if (headerSize == 14) {
            val computed = wire.crc
            val expected = wire.byte() or (wire.byte() shl 8)
            if (expected != 0 && expected != computed) corrupt("Invalid FIT header CRC")
        }
        wire.bodyRemaining = bodySize.toInt()
        val unsupported = try {
            if (protocol != 0x10 && protocol != 0x20) unsupported("Unsupported FIT protocol version")
            readRecords(wire, protocol, budget.limits.recordsPerFile, checkCancelled, onMessage)
            null
        } catch (error: FitReadException) {
            if (error.kind != FitFailureKind.UNSUPPORTED) throw error
            // Confirm the envelope before classifying an unknown encoding as unsupported.
            wire.skipBody(wire.bodyRemaining)
            error
        }
        val computed = wire.crc
        val expected = wire.byte() or (wire.byte() shl 8)
        if (expected != computed) corrupt("Invalid FIT file CRC")
        wire.verifyEnd()
        checkCancelled()
        if (unsupported != null) throw unsupported
        return wire.digest()
    }

    private fun readRecords(wire: Wire, protocol: Int, maxRecords: Int, checkCancelled: () -> Unit,
        onMessage: (FitMessage) -> Unit) {
        val definitions = arrayOfNulls<Definition>(16)
        var timestamp: Long? = null
        var recordCount = 0
        var firstData = true
        while (wire.bodyRemaining > 0) {
            checkCancelled()
            if (++recordCount > maxRecords) fitFailure(FitFailureKind.LIMIT, "Too many FIT records")
            val recordHeader = wire.bodyByte()
            val compressed = recordHeader and 0x80 != 0
            val local = if (compressed) (recordHeader shr 5) and 3 else recordHeader and 15
            if (!compressed && recordHeader and 0x10 != 0) unsupported("Reserved FIT record header flag")
            if (!compressed && recordHeader and 0x40 != 0) {
                val developer = recordHeader and 0x20 != 0
                if (developer && protocol < 0x20) unsupported("Developer fields require FIT 2.0")
                definitions[local] = readDefinition(wire, developer)
                continue
            }
            if (!compressed && recordHeader and 0x20 != 0) unsupported("Unexpected developer record flag")
            val definition = definitions[local] ?: corrupt("FIT data record has no definition")
            val selected = selectedTypes[definition.number]
            val values = if (selected != null) mutableMapOf<Int, Long>() else null
            if (compressed) {
                val reference = timestamp ?: corrupt("Compressed FIT timestamp lacks a reference")
                if (definition.fields.any { it.number == TIMESTAMP }) {
                    unsupported("Compressed FIT record includes a timestamp field")
                }
                val offset = recordHeader and 31
                val unfolded = (reference and -32L) + offset + if (offset < (reference and 31L)) 32 else 0
                if (unfolded >= 0xFFFF_FFFFL) corrupt("Compressed FIT timestamp overflow")
                timestamp = unfolded
                values?.put(TIMESTAMP, unfolded)
            }
            for (field in definition.fields) {
                if (field.number == TIMESTAMP || (values != null && field.number in selected.orEmpty())) {
                    val raw = wire.bodyNumber(field.size, definition.bigEndian)
                    val invalid = (1L shl (8 * field.size)) - 1
                    if (raw == invalid) continue
                    if (field.number == TIMESTAMP) timestamp = raw
                    values?.put(field.number, raw)
                } else {
                    wire.skipBody(field.size)
                }
            }
            wire.skipBody(definition.developerBytes)
            // An empty projection also preserves the identity of the first data record.
            if (firstData || values != null) onMessage(FitMessage(definition.number, values.orEmpty()))
            firstData = false
        }
    }

    private data class Field(val number: Int, val size: Int)
    private data class Definition(val number: Int, val bigEndian: Boolean, val fields: List<Field>, val developerBytes: Int)

    private fun readDefinition(wire: Wire, developer: Boolean): Definition {
        if (wire.bodyByte() != 0) unsupported("Reserved FIT definition field")
        val bigEndian = when (wire.bodyByte()) {
            0 -> false
            1 -> true
            else -> unsupported("Unknown FIT record architecture")
        }
        val number = wire.bodyNumber(2, bigEndian).toInt()
        val selected = selectedTypes[number].orEmpty()
        val seen = BooleanArray(256)
        val fields = List(wire.bodyByte()) {
            val field = wire.bodyByte()
            val size = wire.bodyByte()
            val type = wire.bodyByte()
            val width = widths[type] ?: unsupported("Unknown FIT base type")
            if (seen[field] || size == 0 || size % width != 0) corrupt("Invalid FIT field definition")
            seen[field] = true
            val expected = if (field == TIMESTAMP) UINT32 else selected[field]
            if (expected != null && (type != expected || size != widths.getValue(expected))) {
                corrupt("Invalid type or size of FIT scalar field")
            }
            Field(field, size)
        }
        val developerFields = mutableSetOf<Int>()
        var developerBytes = 0
        if (developer) repeat(wire.bodyByte()) {
            val field = wire.bodyByte()
            val size = wire.bodyByte()
            val index = wire.bodyByte()
            if (size == 0 || !developerFields.add((index shl 8) or field)) corrupt("Invalid FIT developer field definition")
            developerBytes += size
        }
        return Definition(number, bigEndian, fields, developerBytes)
    }

    private fun hasMagic(header: IntArray): Boolean =
        header[8] == '.'.code && header[9] == 'F'.code && header[10] == 'I'.code && header[11] == 'T'.code

    private fun corrupt(reason: String): Nothing = fitFailure(FitFailureKind.CORRUPT, reason)
    private fun unsupported(reason: String): Nothing = fitFailure(FitFailureKind.UNSUPPORTED, reason)

    private class Wire(private val input: InputStream, private val budget: FitReadBudget,
        private val checkCancelled: () -> Unit) {
        private val sha = MessageDigest.getInstance("SHA-256")
        private val buffer = ByteArray(8192)
        private var offset = 0
        private var available = 0
        private var ended = false
        private var bytes = 0
        var crc = 0
            private set
        var bodyRemaining = 0

        private fun read(): Int {
            if (ended) return -1
            if (bytes % 4096 == 0) checkCancelled()
            if (offset == available) {
                // At the limit read at most one more byte to distinguish exact EOF from excess.
                val remaining = minOf(budget.remainingBytes, budget.limits.fileBytes - bytes)
                val length = if (remaining >= buffer.size) buffer.size else remaining + 1
                val read = input.read(buffer, 0, length)
                if (read < 0) {
                    ended = true
                    return -1
                }
                if (read == 0) throw java.io.IOException("FIT source made no progress")
                budget.consumeBytes(read)
                if (read > budget.limits.fileBytes - bytes) fitFailure(FitFailureKind.LIMIT, "FIT file byte limit exceeded")
                available = read
                offset = 0
            }
            val value = buffer[offset++].toInt() and 255
            bytes++
            return value
        }

        fun byte(): Int {
            val value = read()
            if (value < 0) corrupt("Truncated FIT file")
            sha.update(value.toByte())
            crc = (crc ushr 8) xor crcTable[(crc xor value) and 255]
            return value
        }

        fun bodyByte(): Int {
            if (bodyRemaining <= 0) corrupt("Record exceeds declared FIT data length")
            bodyRemaining--
            return byte()
        }

        fun bodyNumber(size: Int, bigEndian: Boolean): Long {
            var result = 0L
            for (index in 0 until size) {
                val shift = (if (bigEndian) size - index - 1 else index) * 8
                result = result or (bodyByte().toLong() shl shift)
            }
            return result
        }

        fun skipBody(size: Int) { repeat(size) { bodyByte() } }

        fun verifyEnd() {
            val trailing = read()
            if (trailing < 0) return
            if (trailing == 12 || trailing == 14) {
                val possible = IntArray(12) { if (it == 0) trailing else read() }
                if (hasMagic(possible)) unsupported("Chained FIT containers are unsupported")
            }
            corrupt("Unexpected bytes after FIT CRC")
        }

        fun digest(): String = buildString(64) {
            for (byte in sha.digest()) {
                append(HEX[(byte.toInt() ushr 4) and 15])
                append(HEX[byte.toInt() and 15])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
    // Reflected CRC-16 polynomial, with an initial value of zero.
    private val crcTable = IntArray(256) { seed ->
        var remainder = seed
        repeat(8) { remainder = (remainder ushr 1) xor if (remainder and 1 == 0) 0 else 0xA001 }
        remainder
    }
}
