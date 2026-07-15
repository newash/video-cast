package com.newash.videocast.subs

import com.newash.videocast.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
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
        // The API requires query params lowercase and alphabetically sorted
        // (unsorted requests get bounced through a 301 that can drop headers).
        val url = "$BASE_URL/subtitles" +
            "?languages=${languages.lowercase().urlEncoded()}" +
            "&order_by=download_count&order_direction=desc" +
            "&query=${query.lowercase().urlEncoded()}"
        parsed { JSONObject(request(url)).getJSONArray("data").toResults() }
    }

    /** Requests a temporary download link, then fetches the raw subtitle bytes. */
    suspend fun download(fileId: Long): ByteArray = withContext(Dispatchers.IO) {
        val link = parsed {
            JSONObject(request("$BASE_URL/download", body = """{"file_id":$fileId}""")).getString("link")
        }
        connect(link).run {
            val code = responseCode
            if (code !in 200..299) throw IOException("subtitle download failed: HTTP $code")
            inputStream.use { it.readBytes() }
        }
    }

    private fun request(url: String, body: String? = null): String = connect(url).run {
        setRequestProperty("Api-Key", apiKey)
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json")
        body?.let {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { out -> out.write(it.toByteArray(Charsets.UTF_8)) }
        }
        val code = responseCode
        val text = (if (code in 200..299) inputStream else errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IOException("OpenSubtitles HTTP $code: ${text.take(200)}")
        text
    }

    private fun connect(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }

    /** A 200 with an unexpected shape is a network-layer failure to callers. */
    private fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("unexpected OpenSubtitles response: ${e.message}")
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
        val USER_AGENT = "VideoCast v${BuildConfig.VERSION_NAME}"
    }
}
