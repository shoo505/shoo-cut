package com.shoo.cut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafUriTest {

    private val treeSegments = listOf("tree", "primary:Movies")

    @Test
    fun treeUriIsTreeAndNotDocument() {
        assertTrue(SafUri.isTreeUri(treeSegments))
        assertFalse(SafUri.isDocumentUri(treeSegments))
    }

    @Test
    fun downloadsTreeUriIsTree() {
        val segments = listOf("tree", "downloads")
        assertTrue(SafUri.isTreeUri(segments))
        assertFalse(SafUri.isDocumentUri(segments))
    }

    @Test
    fun plainDocumentUriIsDocument() {
        val segments = listOf("document", "primary:Movies/video.mp4")
        assertTrue(SafUri.isDocumentUri(segments))
        assertFalse(SafUri.isTreeUri(segments))
    }

    @Test
    fun msfDocumentUriIsDocument() {
        val segments = listOf("document", "msf:1000000123")
        assertTrue(SafUri.isDocumentUri(segments))
        assertFalse(SafUri.isTreeUri(segments))
    }

    @Test
    fun treeScopedDocumentUriIsDocumentButNotTree() {
        // This is exactly the shape createDocument()/buildDocumentUriUsingTree() return.
        val segments = listOf(
            "tree", "primary:Movies",
            "document", "primary:Movies/ShooCut_Part1.mp4"
        )
        assertTrue(SafUri.isDocumentUri(segments))
        assertFalse(
            "A tree-scoped document URI must not be treated as a folder tree URI",
            SafUri.isTreeUri(segments)
        )
    }

    @Test
    fun childrenUriIsNotATreeUri() {
        val segments = listOf(
            "tree", "primary:Movies",
            "document", "primary:Movies", "children"
        )
        assertFalse(SafUri.isTreeUri(segments))
        assertTrue(SafUri.isDocumentUri(segments))
    }

    @Test
    fun malformedSegmentsAreRejected() {
        val malformed = listOf(
            emptyList(),
            listOf("tree"),
            listOf("document"),
            listOf("root", "downloads"),
            listOf("tree", "primary:Movies", "children"),
            listOf("document", "id", "extra")
        )
        for (segments in malformed) {
            assertFalse("Unexpected tree classification for $segments", SafUri.isTreeUri(segments))
        }
        assertFalse(SafUri.isDocumentUri(emptyList()))
        assertFalse(SafUri.isDocumentUri(listOf("tree", "primary:Movies", "children")))
    }

    @Test
    fun everyTreeUriIsNeverADocumentUri() {
        val treeUris = listOf(
            listOf("tree", "primary:Movies"),
            listOf("tree", "primary:DCIM"),
            listOf("tree", "downloads"),
            listOf("tree", "home:")
        )
        for (segments in treeUris) {
            assertTrue(SafUri.isTreeUri(segments))
            assertFalse(SafUri.isDocumentUri(segments))
        }
        assertEquals(4, treeUris.count { SafUri.isTreeUri(it) })
    }
}
