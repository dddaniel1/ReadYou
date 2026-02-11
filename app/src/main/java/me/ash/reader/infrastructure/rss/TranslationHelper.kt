package me.ash.reader.infrastructure.rss

import android.text.Html
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync
import org.json.JSONArray

class TranslationHelper
@Inject
constructor(
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
) {
    suspend fun translateHtmlToChinese(content: String): Result<String> {
        return withContext(ioDispatcher) {
            runCatching {
                val plainText =
                    Html.fromHtml(content, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                if (plainText.isBlank()) throw IllegalArgumentException("No text to translate")

                val lines =
                    plainText
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                        .take(MAX_TRANSLATION_LINES)
                if (lines.isEmpty()) throw IllegalArgumentException("No text to translate")

                val translatedBuilder = StringBuilder()
                lines.forEachIndexed { index, line ->
                    if (index > 0) translatedBuilder.append("\n\n")
                    translatedBuilder.append(line).append('\n')
                    val translatedLine = runCatching { translateChunk(line) }.getOrDefault("")
                    translatedBuilder.append(translatedLine.ifBlank { "Translation unavailable" })
                }
                val translated = translatedBuilder.toString()

                translated.ifBlank { throw IOException("Empty translation response") }
            }
        }
    }

    private suspend fun translateChunk(chunk: String): String {
        val url =
            "https://translate.googleapis.com/translate_a/single"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", "zh-CN")
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", chunk)
                .build()

        val request = Request.Builder().url(url).build()
        return okHttpClient.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Translate request failed: ${response.code}")
            }

            val body = response.body.string()
            val firstLevel = JSONArray(body).optJSONArray(0) ?: return@use ""
            buildString {
                repeat(firstLevel.length()) { index ->
                    append(firstLevel.optJSONArray(index)?.optString(0).orEmpty())
                }
            }.trim()
        }
    }

    companion object {
        private const val MAX_TRANSLATION_LINES = 120
    }
}
