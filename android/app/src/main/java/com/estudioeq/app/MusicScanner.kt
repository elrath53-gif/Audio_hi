package com.estudioeq.app

import android.content.ContentUris
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object MusicScanner {

    private val mimeCache = ConcurrentHashMap<Long, String>()
    private val sizeCache = ConcurrentHashMap<Long, Long>()

    private fun inferFormatTag(displayName: String, mimeType: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            ext == "flac" || mimeType.contains("flac") -> "FLAC"
            ext == "wav" || ext == "wave" || mimeType.contains("wav") -> "WAV"
            ext == "aif" || ext == "aiff" || ext == "aifc" || mimeType.contains("aiff") -> "AIFF"
            ext == "opus" || mimeType.contains("opus") -> "OPUS"
            ext == "ogg" || ext == "oga" || mimeType.contains("ogg") -> "OGG"
            ext == "m4a" || ext == "alac" || ext == "mp4" -> "M4A"
            ext == "aac" || mimeType.contains("aac") -> "AAC"
            ext == "weba" || ext == "webm" || mimeType.contains("webm") -> "WEBM"
            ext == "mp3" || mimeType.contains("mpeg") -> "MP3"
            ext.isNotEmpty() -> ext.uppercase(Locale.ROOT).take(5)
            else -> "AUDIO"
        }
    }

    private fun resolveMimeType(rawMime: String?, displayName: String): String {
        val clean = rawMime?.trim().orEmpty()
        if (clean.isNotEmpty() && clean != "audio/*" && clean != "application/octet-stream") {
            return clean
        }
        return when (displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "flac" -> "audio/flac"
            "wav", "wave" -> "audio/wav"
            "aif", "aiff", "aifc" -> "audio/aiff"
            "opus", "ogg", "oga" -> "audio/ogg"
            "m4a", "mp4", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "weba", "webm" -> "audio/webm"
            "3gp" -> "audio/3gpp"
            else -> "audio/mpeg"
        }
    }

    fun queryTracksJson(context: Context): String {
        val arr = JSONArray()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.IS_MUSIC
        )
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%')"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val durationMs = cursor.getLong(durCol)
                    if (durationMs in 1..2999) continue

                    val rawTitle = cursor.getString(titleCol)?.trim().orEmpty()
                    val displayName = cursor.getString(nameCol)?.trim().orEmpty()
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol)?.trim().orEmpty() else ""
                    val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val resolvedMime = resolveMimeType(mime, displayName)
                    mimeCache[id] = resolvedMime
                    if (sizeBytes > 0L) sizeCache[id] = sizeBytes

                    val cleanFallback = displayName.substringBeforeLast('.', displayName)
                    val title = if (rawTitle.isNotEmpty() && !rawTitle.equals("<unknown>", ignoreCase = true)) {
                        rawTitle
                    } else if (cleanFallback.isNotEmpty()) {
                        cleanFallback
                    } else {
                        "Pista $id"
                    }

                    val rawArtist = cursor.getString(artistCol)?.trim().orEmpty()
                    val artist = if (rawArtist.isNotEmpty() && !rawArtist.equals("<unknown>", ignoreCase = true)) {
                        rawArtist
                    } else {
                        ""
                    }

                    val kbps = if (durationMs > 0L && sizeBytes > 0L) {
                        ((sizeBytes * 8L) / durationMs).toInt()
                    } else {
                        0
                    }

                    val dateAdded = cursor.getLong(addedCol)
                    val obj = JSONObject()
                    obj.put("id", id)
                    obj.put("name", title)
                    obj.put("artist", artist)
                    obj.put("fmt", inferFormatTag(displayName, mime))
                    obj.put("kbps", kbps)
                    obj.put("duration", if (durationMs > 0) durationMs / 1000L else 0L)
                    obj.put("dateAdded", dateAdded)
                    obj.put("url", "https://estudiomaster.local/media/$id")
                    arr.put(obj)
                }
            }
        } catch (_: Exception) {
        }
        return arr.toString()
    }

    private fun queryDisplayName(context: Context, uri: android.net.Uri): String {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun openMediaResponse(
        context: Context,
        mediaId: Long,
        requestHeaders: Map<String, String>
    ): WebResourceResponse {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
        try {
            val mimeType = mimeCache[mediaId] ?: run {
                val rawMime = context.contentResolver.getType(uri)
                val displayName = if (rawMime.isNullOrBlank() || rawMime == "audio/*" || rawMime == "application/octet-stream") {
                    queryDisplayName(context, uri)
                } else {
                    ""
                }
                resolveMimeType(rawMime, displayName).also { mimeCache[mediaId] = it }
            }

            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return empty404()
            val totalLength = pfd.statSize.takeIf { it > 0L } ?: (sizeCache[mediaId] ?: -1L)

            val rangeHeader = requestHeaders.entries
                .firstOrNull { it.key.equals("Range", ignoreCase = true) }
                ?.value

            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=") && totalLength > 0L) {
                val spec = rangeHeader.removePrefix("bytes=").trim()
                val parts = spec.split('-', limit = 2)
                val start = parts[0].trim().toLongOrNull()?.coerceIn(0L, totalLength - 1L) ?: 0L
                val end = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?.toLongOrNull()?.coerceIn(start, totalLength - 1L)
                    ?: (totalLength - 1L)

                if (start > 0L) {
                    Os.lseek(pfd.fileDescriptor, start, OsConstants.SEEK_SET)
                }
                val contentLength = (end - start + 1L).coerceAtLeast(0L)
                val rawStream = BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd), 262144)
                val bounded = BoundedPfdInputStream(pfd, rawStream, contentLength)
                val headers = mapOf(
                    "Accept-Ranges" to "bytes",
                    "Content-Range" to "bytes $start-$end/$totalLength",
                    "Content-Length" to contentLength.toString(),
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "private, max-age=3600"
                )
                return WebResourceResponse(mimeType, null, 206, "Partial Content", headers, bounded)
            }

            val maxBytes = if (totalLength > 0L) totalLength else Long.MAX_VALUE
            val rawStream = BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd), 262144)
            val bounded = BoundedPfdInputStream(pfd, rawStream, maxBytes)
            val headers = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "private, max-age=3600"
            )
            if (totalLength > 0L) {
                headers["Content-Length"] = totalLength.toString()
            }
            return WebResourceResponse(mimeType, null, 200, "OK", headers, bounded)
        } catch (_: Exception) {
            return empty404()
        }
    }

    private fun empty404(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private class BoundedPfdInputStream(
        private val pfd: ParcelFileDescriptor,
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        private var closed = false

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val maxRead = minOf(len.toLong(), remaining).toInt()
            val count = delegate.read(b, off, maxRead)
            if (count > 0) remaining -= count.toLong()
            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                delegate.close()
            } catch (_: Exception) {
            }
            try {
                pfd.close()
            } catch (_: Exception) {
            }
        }
    }
}
