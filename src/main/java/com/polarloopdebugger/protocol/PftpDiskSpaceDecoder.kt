package com.polarloopdebugger.protocol

data class DiskSpaceResult(
    val fragmentSize: Long,
    val totalFragments: Long,
    val freeFragments: Long,
) {
    val totalBytes: Long get() = fragmentSize * totalFragments
    val freeBytes: Long get() = fragmentSize * freeFragments
}

/**
 * Decodes a GET_DISK_SPACE query response body against PbPFtpDiskSpaceResult's schema
 * (pftp_response.proto: `fragment_size`=1, `total_fragments`=2, `free_fragments`=3, all
 * required VARINT fields) -- total/free bytes are fragment_size * {total,free}_fragments.
 *
 * CAPTURE-VALIDATED (capture19): structurally matches a real response, e.g. `08 80 20 10 88 27 18 C4 13`
 * decode as fragment_size=4096, total_fragments=3668, free_fragments=3354 -> 15,024,128 total /
 * 13,737,984 free bytes (14.3 / 13.1 MB), matching the on-device figure shown in the app. See
 * PROTOCOL.md's "GET_DISK_SPACE" section.
 */
fun decodeDiskSpaceResult(data: ByteArray): DiskSpaceResult {
    val reader = ProtoReader(data)
    var fragmentSize: Long? = null
    var totalFragments: Long? = null
    var freeFragments: Long? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> fragmentSize = field.varint
            2 -> totalFragments = field.varint
            3 -> freeFragments = field.varint
        }
    }
    return DiskSpaceResult(
        requireNotNull(fragmentSize) { "PbPFtpDiskSpaceResult missing required fragment_size" },
        requireNotNull(totalFragments) { "PbPFtpDiskSpaceResult missing required total_fragments" },
        requireNotNull(freeFragments) { "PbPFtpDiskSpaceResult missing required free_fragments" },
    )
}
