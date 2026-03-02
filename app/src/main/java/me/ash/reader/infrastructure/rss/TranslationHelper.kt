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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag

class TranslationHelper
@Inject
constructor(
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
) {
    fun buildPlaceholderHtml(content: String): String {
        return runCatching {
                val document = Jsoup.parseBodyFragment(content)
                val translatableElements = document.collectTranslatableElements()
                if (translatableElements.isEmpty()) {
                    return@runCatching content
                }

                translatableElements.forEach { element ->
                    element.after(
                        createTranslationElement(
                            sourceElement = element,
                            text = TRANSLATION_LOADING_PLACEHOLDER,
                        )
                    )
                }
                document.body().html()
            }
            .getOrDefault(content)
    }

    suspend fun translateHtmlToChinese(content: String): Result<String> {
        return withContext(ioDispatcher) {
            runCatching {
                val plainText = Html.fromHtml(content, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                if (plainText.isBlank()) throw IllegalArgumentException("No text to translate")

                val document = Jsoup.parseBodyFragment(content)
                val translatableElements = document.collectTranslatableElements()
                if (translatableElements.isEmpty()) throw IllegalArgumentException("No text to translate")

                translatableElements.forEach { element ->
                    val translatedText =
                        runCatching { translateChunk(element.text().trim()) }.getOrDefault("")
                    element.after(
                        createTranslationElement(
                            sourceElement = element,
                            text = translatedText.ifBlank { TRANSLATION_UNAVAILABLE_PLACEHOLDER },
                        )
                    )
                }
                val translated = document.body().html()

                translated.ifBlank { throw IOException("Empty translation response") }
            }
        }
    }

    private fun org.jsoup.nodes.Document.collectTranslatableElements(): List<Element> {
        return body()
            .select(TRANSLATABLE_SELECTOR)
            .filter { element ->
                !element.hasClass(TRANSLATION_PARAGRAPH_CLASS) &&
                    element.text().isNotBlank() &&
                    element.select(TRANSLATABLE_SELECTOR).none { it !== element }
            }
    }

    private fun createTranslationElement(sourceElement: Element, text: String): Element {
        return Element(Tag.valueOf(sourceElement.tagName()), "")
            .addClass(TRANSLATION_PARAGRAPH_CLASS)
            .attr("style", "margin-top:0.35em;")
            .text(text)
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
        const val TRANSLATION_PARAGRAPH_CLASS = "ry-translation-placeholder"
        private const val TRANSLATABLE_SELECTOR = "p,li,blockquote,h1,h2,h3,h4,h5,h6"
        private const val TRANSLATION_LOADING_PLACEHOLDER = "..."
        private const val TRANSLATION_UNAVAILABLE_PLACEHOLDER = "Translation unavailable"
    }
}
