package com.shoo.cut

/**
 * Classifies the path shape of Android DocumentsContract URIs.
 *
 * This deliberately does not touch `android.net.Uri`, so the rules can be unit
 * tested on a plain JVM. Callers pass `Uri.pathSegments`.
 *
 * Shapes produced by the platform:
 *
 *  - tree URI (ACTION_OPEN_DOCUMENT_TREE result):
 *      /tree/<treeDocumentId>
 *  - document URI:
 *      /document/<documentId>
 *  - tree-scoped document URI (what DocumentsContract returns when it was
 *    called with a tree URI):
 *      /tree/<treeDocumentId>/document/<documentId>
 *  - children URI:
 *      /tree/<treeDocumentId>/document/<treeDocumentId>/children
 *
 * A tree URI is *not* a document URI. `DocumentsContract.getDocumentId()` and
 * `DocumentsProvider` reject it with `IllegalArgumentException("Invalid URI: ...")`.
 */
object SafUri {

    private const val SEGMENT_TREE = "tree"
    private const val SEGMENT_DOCUMENT = "document"

    /** True only for a folder picker result: exactly `/tree/<id>`. */
    fun isTreeUri(segments: List<String>): Boolean =
        segments.size == 2 && segments[0] == SEGMENT_TREE

    /**
     * True for document URIs, including the tree-scoped form returned by
     * `DocumentsContract.createDocument()` and `buildDocumentUriUsingTree()`.
     */
    fun isDocumentUri(segments: List<String>): Boolean {
        if (segments.size >= 2 && segments[0] == SEGMENT_DOCUMENT) {
            return true
        }
        return segments.size >= 4 &&
            segments[0] == SEGMENT_TREE &&
            segments[2] == SEGMENT_DOCUMENT
    }
}
