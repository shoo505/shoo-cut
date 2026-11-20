package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutputNamingTest {

    @Test
    fun producesFourCanonicalNames() {
        assertEquals(
            listOf(
                "ShooCut_Part1.mp4",
                "ShooCut_Part2.mp4",
                "ShooCut_Part3.mp4",
                "ShooCut_Part4.mp4"
            ),
            OutputNaming.partFileNames()
        )
    }

    @Test
    fun namesAreOneBased() {
        assertEquals("ShooCut_Part1.mp4", OutputNaming.partFileName(1))
        assertEquals("ShooCut_Part4.mp4", OutputNaming.partFileName(4))
    }

    @Test
    fun rejectsInvalidPartNumbers() {
        assertThrows(IllegalArgumentException::class.java) { OutputNaming.partFileName(0) }
        assertThrows(IllegalArgumentException::class.java) { OutputNaming.partFileName(5) }
    }
}
