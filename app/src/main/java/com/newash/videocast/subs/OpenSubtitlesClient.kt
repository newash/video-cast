package com.newash.videocast.subs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for the OpenSubtitles REST API (api.opensubtitles.com/api/v1),
 * title-query search only. Anonymous usage: an API key but no user login
 * (5 downloads/day is plenty here). Plain HttpURLConnection + org.json — two
 * endpoints don't justify an HTTP/JSON dependency.
 */
class OpenSubtitlesClient(private val apiKey: String) {

    data class Result(val fileId: Long, val name: String, val language: String)

    suspend fun search(query: String, languages: String): List<Result> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/subtitles?query=${query.urlEncoded()}&languages=${languages.urlEncoded()}" +
            "&order_by=download_count&order_direction=desc"
        JSONObject(request(url)).getJSONArray("data").toResults()
    }

    /** Requests a temporary download link, then fetches the raw subtitle bytes. */
    suspend fun download(fileId: Long): ByteArray = withContext(Dispatchers.IO) {
        val link = JSONObject(request("$BASE_URL/download", body = """{"file_id":$fileId}"""))
            .getString("link")
        URL(link).openStream().use { it.readBytes() }
    }

    private fun request(url: String, body: String? = null): String =
        (URL(url).openConnection() as HttpURLConnection).run {
            setRequestProperty("Api-Key", apiKey)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            body?.let {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { out -> out.write(it.toByteArray()) }
            }
            val text = (if (responseCode < 400) inputStream else errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode >= 400) throw IOException("OpenSubtitles HTTP $responseCode: ${text.take(200)}")
            text
        }

    private fun JSONArray.toResults(): List<Result> = (0 until length()).mapNotNull { i ->
        val attributes = optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
        val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
        Result(
            fileId = file.optLong("file_id", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
            name = file.optString("file_name").ifBlank { attributes.optString("release", "subtitle") },
            language = attributes.optString("language").ifBlank { "?" },
        )
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        const val USER_AGENT = "VideoCast v1.0"
    }
}
