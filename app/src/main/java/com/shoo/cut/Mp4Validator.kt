package com.shoo.cut

/**
 * Minimal, dependency-free MP4/ISO-BMFF header check used before an output part
 * is reported as successful. A finalized MP4 written by FFmpeg starts with a
 * box header whose type at offset 4 is `ftyp`.
 */
object Mp4Validator {

    const val HEADER_SIZE = 12
    private const val FTYP_OFFSET = 4
    private const val FTYP_TYPE = "ftyp"

    fun hasFtypBox(header: ByteArray): Boolean {
        if (header.size < HEADER_SIZE) return false
        val boxType = String(header, FTYP_OFFSET, FTYP_TYPE.length, Charsets.US_ASCII)
        return boxType == FTYP_TYPE
    }
}
