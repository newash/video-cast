package com.newash.videocast.subs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Subtitle files sitting next to a video, e.g. "Movie.en.srt" for "Movie.mkv".
 * [bestMatch] is the pure name matcher; [find] is the SAF plumbing around it,
 * which needs a persisted tree grant covering the video's folder.
 */
object SiblingSubtitles {

    sealed interface Result {
        class Found(val uri: Uri) : Result
        data object NoAccess : Result
        data object None : Result
    }

    /** Best sibling subtitle of the video, via a persisted tree grant. */
    fun find(context: Context, videoUri: Uri, videoName: String, langTags: Set<String>): Result {
        if (!DocumentsContract.isDocumentUri(context, videoUri)) return Result.None
        val docId = DocumentsContract.getDocumentId(videoUri)
        val parentId = parentDocumentId(docId) ?: return Result.None
        val resolver = context.contentResolver
        val tree = resolver.persistedUriPermissions.firstOrNull { permission ->
            permission.isReadPermission && permission.uri.authority == videoUri.authority &&
                runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
                    ?.let { treeId -> isUnderTree(docId, treeId) } == true
        } ?: return Result.NoAccess
        val children = mutableMapOf<String, String>() // display name → document id
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree.uri, parentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) children[cursor.getString(0)] = cursor.getString(1)
        }
        val best = bestMatch(videoName, langTags, children.keys.toList()) ?: return Result.None
        return Result.Found(DocumentsContract.buildDocumentUriUsingTree(tree.uri, children.getValue(best)))
    }

    /**
     * Parent of a path-style document ID: "primary:Movies/a.mkv" → "primary:Movies",
     * "primary:a.mkv" → "primary:" (storage root). Null for non-path IDs ("msf:123"),
     * where sibling lookup is impossible.
     */
    fun parentDocumentId(docId: String): String? {
        val slash = docId.lastIndexOf('/')
        if (slash >= 0) return docId.substring(0, slash)
        val colon = docId.indexOf(':')
        return if (colon >= 0 && colon < docId.length - 1) docId.substring(0, colon + 1) else null
    }

    /** Whether [docId] lies under the tree rooted at [treeId] ("primary:Movies", root "primary:"). */
    fun isUnderTree(docId: String, treeId: String): Boolean =
        docId.startsWith("$treeId/") || (treeId.endsWith(":") && docId.startsWith(treeId))

    /**
     * Best sibling subtitle among [names]: a file tagged with one of [langTags]
     * ("Movie.en.srt", "Movie.eng.forced.srt") beats a plain undecorated
     * sibling ("Movie.srt"); files tagged with other languages never match.
     */
    fun bestMatch(videoName: String, langTags: Set<String>, names: List<String>): String? {
        val base = videoName.substringBeforeLast('.')
        fun rank(name: String): Int? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in EXTENSIONS || !name.startsWith("$base.", ignoreCase = true)) return null
            val tokens = name.drop(base.length + 1).dropLast(ext.length + 1)
                .split('.').filter(String::isNotEmpty).map(String::lowercase)
            return when {
                tokens.isEmpty() -> 2 // plain "base.ext"
                // Direct hit, or a region-tagged spelling of a saved language
                // ("pt-br" matches saved "pt"; "est" stays Estonian, not Spanish).
                tokens.any { token ->
                    token in langTags || LanguageTag.primary(token)?.let { it in langTags } == true
                } -> 1
                else -> null // some other language's (or unrelated) file
            }
        }
        return names.mapNotNull { name -> rank(name)?.let { it to name } }
            // Ties break toward the least-decorated name.
            .minWithOrNull(compareBy({ it.first }, { it.second.length }))?.second
    }

    private val EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
}
