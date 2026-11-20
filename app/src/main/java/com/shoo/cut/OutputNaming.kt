package com.shoo.cut

/**
 * Canonical names for the four output documents. Keeping them in one place
 * guarantees that the names shown in the UI, the names pre-created through SAF
 * and the names reported after processing are identical.
 */
object OutputNaming {

    const val PART_COUNT = 4

    private const val BASE_NAME = "ShooCut"

    fun partFileName(partNumber: Int): String {
        require(partNumber in 1..PART_COUNT) {
            "Part number must be between 1 and $PART_COUNT"
        }
        return "${BASE_NAME}_Part$partNumber.mp4"
    }

    fun partFileNames(): List<String> = (1..PART_COUNT).map(::partFileName)
}
