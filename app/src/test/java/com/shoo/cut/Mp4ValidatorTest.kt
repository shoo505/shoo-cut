package com.shoo.cut

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ValidatorTest {

    @Test
    fun acceptsFtypHeader() {
        val header = ByteArray(12)
        header[3] = 0x18
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        assertTrue(Mp4Validator.hasFtypBox(header))
    }

    @Test
    fun rejectsTruncatedHeader() {
        val header = ByteArray(11)
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        assertFalse(Mp4Validator.hasFtypBox(header))
    }

    @Test
    fun rejectsNonFtypBox() {
        val header = ByteArray(12)
        "moov".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        assertFalse(Mp4Validator.hasFtypBox(header))
    }

    @Test
    fun rejectsEmptyHeader() {
        assertFalse(Mp4Validator.hasFtypBox(ByteArray(0)))
    }
}
