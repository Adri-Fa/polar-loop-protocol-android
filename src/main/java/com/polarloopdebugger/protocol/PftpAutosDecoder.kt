package com.polarloopdebugger.protocol

/** PbDate{year=1,month=2,day=3}. */
data class PbDate(val year: Int, val month: Int, val day: Int)

/** PbTime{hour=1,minute=2,seconds=3,millis=4}. */
data class PbTime(val hour: Int, val minute: Int, val seconds: Int, val millis: Int)

/**
 * PbPpIntervalSamples, decoded to reconstructed cumulative values (ms) -- the wire format stores
 * signed deltas (zigzag varint) per PROTOCOL.md/ppi_samples.proto; each sample here is the running
 * sum, matching what PROTOCOL.md's worked example cites.
 */
data class PpiIntervalSamples(val ppi: List<Int>, val errorEstimate: List<Int>, val status: List<Int>)

data class PpiIntervalAutoSample(val triggerType: Int, val recordingTime: PbTime, val ppi: PpiIntervalSamples)

/** PbAutomaticHeartRateSamples{trigger_type=1, time=2, heart_rate=3 (plain packed uint32, bpm)}. */
data class AutomaticHeartRateSample(val triggerType: Int, val recordingTime: PbTime, val heartRate: List<Int>)

data class AutomaticSampleSession(
    val day: PbDate,
    val hrSamples: List<AutomaticHeartRateSample>,
    val ppiSamples: List<PpiIntervalAutoSample>,
    val skippedHrSampleIndices: List<Int> = emptyList(),
    val skippedSampleIndices: List<Int> = emptyList(),
)

// Bounded lookahead for resyncing after a broken top-level field (see [findResyncOffset]). Real
// examples seen so far (capture12/13) needed at most ~26 bytes; this is generous headroom above
// that, not a tuned/load-bearing limit.
private const val MAX_RESYNC_SCAN_BYTES = 64

/**
 * Decodes an AUTOSNNN.BPB body (PbAutomaticSampleSessions). `samples` (HR, field 2) is skipped --
 * not observed/confirmed in any capture yet.
 *
 * Real devices have been observed (capture12/13, byte-identical across two independent BLE
 * sessions -- a firmware bug, not transmission loss) to declare some length-delimited fields'
 * lengths short of what their content actually needs. This isn't a single uniform off-by-one:
 * it's been seen both as a dangling continuation bit inside a multi-byte varint (detectable --
 * parsing throws) and as a declared length that's short by exactly the same amount as an inner
 * field's own shortfall, with no parse error at all (the inner field's bytes are all
 * independently-valid single-byte varints, so the truncated read just silently yields one fewer
 * value). A shared-cursor "soft limit" reader can self-heal the first sub-case, but doing so
 * safely across nesting levels -- without the outer (possibly also-short) declared length
 * clamping away the exact byte the inner field needed -- turned out to require trusting an
 * unbounded chain of maybe-buggy lengths, which reintroduces the corruption risk this is trying to
 * avoid. Simpler and safer: read each length-delimited field as a straight byte copy of its
 * declared length (so a bad length can never desync anything past that one field), and if a
 * sample's ppi content doesn't parse cleanly inside that copy, skip just that sample via
 * [skippedSampleIndices] and resync ([findResyncOffset]) rather than guessing.
 */
fun decodeAutosFile(data: ByteArray): AutomaticSampleSession {
    var reader = ProtoReader(data)
    var day: PbDate? = null
    val hrSamples = mutableListOf<AutomaticHeartRateSample>()
    val ppiSamples = mutableListOf<PpiIntervalAutoSample>()
    val skippedHrSampleIndices = mutableListOf<Int>()
    val skippedSampleIndices = mutableListOf<Int>()
    var hrSampleIndex = 0
    var sampleIndex = 0
    while (reader.hasMore()) {
        val startOffset = reader.position()
        val field = try {
            reader.readField()
        } catch (e: Exception) {
            val resyncOffset = findResyncOffset(data, startOffset)
            reader = if (resyncOffset != null) ProtoReader(data, resyncOffset) else break
            continue
        }
        when (field.fieldNumber) {
            1 -> if (day == null) day = decodePbDate(field.bytes!!)
            2 -> {
                hrSampleIndex++
                try {
                    hrSamples.add(decodeAutomaticHeartRateSample(field.bytes!!))
                } catch (e: Exception) {
                    skippedHrSampleIndices.add(hrSampleIndex)
                }
            }
            3 -> {
                sampleIndex++
                try {
                    ppiSamples.add(decodePpiIntervalAutoSample(field.bytes!!))
                } catch (e: Exception) {
                    skippedSampleIndices.add(sampleIndex)
                }
            }
        }
    }
    return AutomaticSampleSession(
        requireNotNull(day) { "PbAutomaticSampleSessions missing required day" },
        hrSamples,
        ppiSamples,
        skippedHrSampleIndices,
        skippedSampleIndices,
    )
}

/**
 * After a broken top-level read, looks for the next byte offset that's plausibly a real tag: this
 * schema's only three top-level fields (day/samples/ppi) are all length-delimited, so a genuine
 * tag byte must encode field number 1-3 with wire type 2 *and* actually parse as a complete field
 * (its declared length must fit in what's left) -- both checks together reject the vast majority
 * of stray bytes that would otherwise coincidentally look like a tag.
 */
private fun findResyncOffset(data: ByteArray, from: Int): Int? {
    val limit = minOf(data.size, from + MAX_RESYNC_SCAN_BYTES)
    for (candidate in from until limit) {
        val tag = data[candidate].toInt() and 0xFF
        if (tag ushr 3 !in 1..3 || tag and 0x7 != 2) continue
        try {
            ProtoReader(data, candidate).readField()
            return candidate
        } catch (e: Exception) {
            // Not actually a valid field here -- keep scanning.
        }
    }
    return null
}

/** Shared with [decodeSleepResultFile] -- PbDate/PbTime are the same messages in both schemas. */
fun decodePbDate(data: ByteArray): PbDate {
    val reader = ProtoReader(data)
    var year = 0
    var month = 0
    var day = 0
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> year = field.varint!!.toInt()
            2 -> month = field.varint!!.toInt()
            3 -> day = field.varint!!.toInt()
        }
    }
    return PbDate(year, month, day)
}

fun decodePbTime(data: ByteArray): PbTime {
    val reader = ProtoReader(data)
    var hour = 0
    var minute = 0
    var seconds = 0
    var millis = 0
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> hour = field.varint!!.toInt()
            2 -> minute = field.varint!!.toInt()
            3 -> seconds = field.varint!!.toInt()
            4 -> millis = field.varint!!.toInt()
        }
    }
    return PbTime(hour, minute, seconds, millis)
}

private fun decodePpiIntervalAutoSample(data: ByteArray): PpiIntervalAutoSample {
    val reader = ProtoReader(data)
    var triggerType = 0
    var recordingTime: PbTime? = null
    var ppi: PpiIntervalSamples? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> triggerType = field.varint!!.toInt()
            2 -> recordingTime = decodePbTime(field.bytes!!)
            3 -> ppi = decodePpiIntervalSamples(field.bytes!!)
        }
    }
    return PpiIntervalAutoSample(
        triggerType,
        requireNotNull(recordingTime) { "PbPpIntervalAutoSamples missing required recording_time" },
        requireNotNull(ppi) { "PbPpIntervalAutoSamples missing required ppi" },
    )
}

private fun decodeAutomaticHeartRateSample(data: ByteArray): AutomaticHeartRateSample {
    val reader = ProtoReader(data)
    var triggerType = 0
    var recordingTime: PbTime? = null
    var heartRate: List<Long> = emptyList()
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> triggerType = field.varint!!.toInt()
            2 -> recordingTime = decodePbTime(field.bytes!!)
            3 -> heartRate = readPackedVarints(field.bytes!!)
        }
    }
    return AutomaticHeartRateSample(
        triggerType,
        requireNotNull(recordingTime) { "PbAutomaticHeartRateSamples missing required time" },
        heartRate.map { it.toInt() },
    )
}

private fun decodePpiIntervalSamples(data: ByteArray): PpiIntervalSamples {
    val reader = ProtoReader(data)
    var ppiDeltas: List<Long> = emptyList()
    var errorDeltas: List<Long> = emptyList()
    var status: List<Long> = emptyList()
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> ppiDeltas = readPackedVarints(field.bytes!!).map(ProtoReader::zigzagDecode)
            2 -> errorDeltas = readPackedVarints(field.bytes!!).map(ProtoReader::zigzagDecode)
            3 -> status = readPackedVarints(field.bytes!!)
        }
    }
    return PpiIntervalSamples(cumulativeSum(ppiDeltas), cumulativeSum(errorDeltas), status.map { it.toInt() })
}

/** Not private -- reused by [decodeActivitySamplesFile] for its packed steps_samples field. */
fun readPackedVarints(data: ByteArray): List<Long> {
    val reader = ProtoReader(data)
    val values = mutableListOf<Long>()
    while (reader.hasMore()) {
        values.add(reader.readVarint())
    }
    return values
}

private fun cumulativeSum(deltas: List<Long>): List<Int> {
    var running = 0L
    return deltas.map { running += it; running.toInt() }
}
