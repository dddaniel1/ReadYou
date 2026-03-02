package me.ash.reader.infrastructure.rss

import android.content.Context
import android.util.Log
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndImageImpl
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.isFuture
import me.ash.reader.ui.ext.spacerDollar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync
import okhttp3.internal.commonIsSuccessful
import okio.IOException
import org.jsoup.Jsoup

val enclosureRegex = """<enclosure\s+url="([^"]+)"\s+type=".*"\s*/>""".toRegex()
private val audioEnclosureRegex =
    """<enclosure\s+url=(["'])(.*?)\1\s+type=(["'])audio/[^"']+\3\s*/?>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val audioTagRegex =
    """<audio[^>]*src=(["'])(.*?)\1[^>]*>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val sourceAudioTagRegex =
    """<source[^>]*src=(["'])(.*?)\1[^>]*type=(["'])audio/[^"']+\3[^>]*>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
val imgRegex = """img.*?src=(["'])((?!data).*?)\1""".toRegex(RegexOption.DOT_MATCHES_ALL)
private val retryableStatusCodes = setOf(429, 503)

data class FeedFetchResult(
    val articles: List<Article>,
    val etag: String?,
    val lastModified: String?,
    val notModified: Boolean = false,
)

class FeedRequestThrottledException(
    message: String,
    val retryAfterMillis: Long?,
) : IOException(message)

/** Some operations on RSS. */
class RssHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
) {

    @Throws(Exception::class)
    suspend fun searchFeed(feedLink: String): SyndFeed {
        return withContext(ioDispatcher) {
            val response = response(okHttpClient, feedLink)
            val contentType = response.header("Content-Type")
            val httpContentType =
                contentType?.let {
                    if (it.contains("charset=", ignoreCase = true)) it
                    else "$it; charset=UTF-8"
                } ?: "text/xml; charset=UTF-8"


            response.body.byteStream().use { inputStream ->
                    SyndFeedInput().build(XmlReader(inputStream, httpContentType)).also {
                    it.icon = SyndImageImpl()
                    it.icon.link = queryRssIconLink(feedLink)
                    it.icon.url = it.icon.link
                }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun parseFullContent(link: String, title: String): String {
        return withContext(ioDispatcher) {
            val response = response(okHttpClient, link)
            if (response.commonIsSuccessful) {
                val responseBody = response.body
                val charset = responseBody.contentType()?.charset()
                val content =
                    responseBody.source().use {
                        if (charset != null) {
                            return@use it.readString(charset)
                        }

                        val peekContent = it.peek().readString(Charsets.UTF_8)

                        val charsetFromMeta =
                            runCatching {
                                    val element =
                                        Jsoup.parse(peekContent, link)
                                            .selectFirst("meta[http-equiv=content-type]")
                                    return@runCatching if (element == null) Charsets.UTF_8
                                    else {
                                        element
                                            .attr("content")
                                            .substringAfter("charset=")
                                            .removeSurrounding("\"")
                                            .lowercase()
                                            .let { Charset.forName(it) }
                                    }
                                }
                                .getOrDefault(Charsets.UTF_8)

                        if (charsetFromMeta == Charsets.UTF_8) {
                            peekContent
                        } else {
                            it.readString(charsetFromMeta)
                        }
                    }

                val articleContent = Readability.parseToElement(content, link)
                articleContent?.let {
                    val h1Element = articleContent.selectFirst("h1")
                    if (h1Element != null && h1Element.hasText() && h1Element.text() == title) {
                        h1Element.remove()
                    }
                    articleContent.toString()
                } ?: throw IOException("articleContent is null")
            } else throw IOException(response.message)
        }
    }

    suspend fun queryRssXml(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
    ): FeedFetchResult {
        return try {
            val accountId = context.currentAccountId
            val response = response(okHttpClient, feed.url, feed.etag, feed.lastModified)
            if (response.code == 304) {
                response.close()
                FeedFetchResult(
                    articles = emptyList(),
                    etag = feed.etag,
                    lastModified = feed.lastModified,
                    notModified = true,
                )
            } else if (!response.commonIsSuccessful) {
                val retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After"))
                val responseCode = response.code
                val responseMessage = response.message
                response.close()
                if (responseCode in retryableStatusCodes) {
                    throw FeedRequestThrottledException(
                        message = "Request throttled for ${feed.name}: $responseCode $responseMessage",
                        retryAfterMillis = retryAfterMillis,
                    )
                }
                throw IOException(responseMessage)
            } else {
                val contentType = response.header("Content-Type")
                val etag = response.header("ETag") ?: feed.etag
                val lastModified = response.header("Last-Modified") ?: feed.lastModified

                val httpContentType =
                    contentType?.let {
                        if (it.contains("charset=", ignoreCase = true)) it
                        else "$it; charset=UTF-8"
                    } ?: "text/xml; charset=UTF-8"

                response.body.byteStream().use { inputStream ->
                    val articles =
                        SyndFeedInput()
                            .apply { isPreserveWireFeed = true }
                            .build(XmlReader(inputStream, httpContentType))
                            .entries
                            .asSequence()
                            .takeWhile { latestLink == null || latestLink != it.link }
                            .map { buildArticleFromSyndEntry(feed, accountId, it, preDate) }
                            .toList()
                    FeedFetchResult(
                        articles = articles,
                        etag = etag,
                        lastModified = lastModified,
                    )
                }
            }
        } catch (e: FeedRequestThrottledException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RLog", "queryRssXml[${feed.name}]: ${e.message}")
            FeedFetchResult(
                articles = emptyList(),
                etag = feed.etag,
                lastModified = feed.lastModified,
            )
        }
    }

    fun buildArticleFromSyndEntry(
        feed: Feed,
        accountId: Int,
        syndEntry: SyndEntry,
        preDate: Date = Date(),
    ): Article {
        val desc = syndEntry.description?.value
        val content =
            syndEntry.contents
                .takeIf { it.isNotEmpty() }
                ?.let { it.joinToString("\n") { it.value } }
        val rawDescription = content ?: desc ?: ""
        val podcastMediaUrl = findPodcastMediaUrl(syndEntry) ?: findPodcastMediaUrl(rawDescription)
        val descriptionWithPodcast = rawDescription.withInjectedPodcastAudioTag(podcastMediaUrl)
        //        Log.i(
        //            "RLog",
        //            "request rss:\n" +
        //                    "name: ${feed.name}\n" +
        //                    "feedUrl: ${feed.url}\n" +
        //                    "url: ${syndEntry.link}\n" +
        //                    "title: ${syndEntry.title}\n" +
        //                    "desc: ${desc}\n" +
        //                    "content: ${content}\n"
        //        )
        return Article(
            id = accountId.spacerDollar(UUID.randomUUID().toString()),
            accountId = accountId,
            feedId = feed.id,
            date =
                (syndEntry.publishedDate ?: syndEntry.updatedDate)?.takeIf { !it.isFuture(preDate) }
                    ?: preDate,
            title = syndEntry.title.decodeHTML() ?: feed.name,
            author = syndEntry.author,
            rawDescription = descriptionWithPodcast,
            shortDescription = Readability.parseToText(desc ?: content, syndEntry.link).take(280),
            //            fullContent = content,
            img =
                findThumbnail(
                    syndEntry = syndEntry,
                    text = content ?: desc,
                    articleLink = syndEntry.link,
                    feedLink = feed.url,
                ),
            link = syndEntry.link ?: "",
            updateAt = preDate,
        )
    }

    private fun findPodcastMediaUrl(syndEntry: SyndEntry): String? {
        val directEnclosure =
            syndEntry.enclosures.firstOrNull {
                it.url?.isNotBlank() == true &&
                    (it.type?.startsWith("audio", ignoreCase = true) == true)
            }
        if (directEnclosure?.url?.isNotBlank() == true) {
            return directEnclosure.url
        }

        val mediaModule = syndEntry.getModule(MediaModule.URI) as? MediaEntryModule
        return mediaModule
            ?.mediaContents
            ?.firstOrNull {
                val mediumIsAudio = it.medium?.equals("audio", ignoreCase = true) == true
                val typeIsAudio = it.type?.startsWith("audio", ignoreCase = true) == true
                mediumIsAudio || typeIsAudio
            }
            ?.reference
            ?.let { it as? UrlReference }
            ?.url
            ?.toString()
            ?.takeIf { it.isNotBlank() }
    }

    private fun findPodcastMediaUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return audioEnclosureRegex.find(text)?.groupValues?.getOrNull(2)
            ?: audioTagRegex.find(text)?.groupValues?.getOrNull(2)
            ?: sourceAudioTagRegex.find(text)?.groupValues?.getOrNull(2)
    }

    fun findThumbnail(
        syndEntry: SyndEntry,
        text: String?,
        articleLink: String?,
        feedLink: String?,
    ): String? {
        val mediaModule = syndEntry.getModule(MediaModule.URI) as? MediaEntryModule
        val mediaThumbnail = mediaModule?.let { findThumbnail(it) }
        if (!mediaThumbnail.isNullOrBlank()) {
            return normalizeImageUrl(mediaThumbnail, articleLink, feedLink)
        }

        val enclosureThumbnail =
            syndEntry.enclosures
                .firstOrNull {
                    it.url?.isNotBlank() == true &&
                        (it.type?.startsWith("image", ignoreCase = true) == true)
                }
                ?.url
        if (!enclosureThumbnail.isNullOrBlank()) {
            return normalizeImageUrl(enclosureThumbnail, articleLink, feedLink)
        }

        val htmlThumbnail = findThumbnail(text)
        if (!htmlThumbnail.isNullOrBlank()) {
            return normalizeImageUrl(htmlThumbnail, articleLink, feedLink)
        }

        return null
    }

    private fun findThumbnail(mediaModule: MediaEntryModule): String? {
        val candidates =
            buildList {
                    add(mediaModule.metadata)
                    addAll(mediaModule.mediaGroups.map { mediaGroup -> mediaGroup.metadata })
                    addAll(mediaModule.mediaContents.map { content -> content.metadata })
                }
                .flatMap { it.thumbnail.toList() }

        val thumbnail = candidates.firstOrNull()

        if (thumbnail != null) {
            return thumbnail.url.toString()
        } else {
            val imageMedia = mediaModule.mediaContents.firstOrNull { it.medium == "image" }
            if (imageMedia != null) {
                return (imageMedia.reference as? UrlReference)?.url.toString()
            }
        }
        return null
    }

    fun findThumbnail(text: String?): String? {
        text ?: return null
        val enclosure = enclosureRegex.find(text)?.groupValues?.get(1)
        if (enclosure?.isNotBlank() == true) {
            return enclosure
        }

        val document = Jsoup.parseBodyFragment(text)
        val imageElement =
            document.selectFirst("img[src],img[data-src],img[data-original],img[srcset],source[srcset]")

        val srcCandidate =
            imageElement
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?: imageElement
                    ?.attr("data-src")
                    ?.takeIf { it.isNotBlank() }
                ?: imageElement
                    ?.attr("data-original")
                    ?.takeIf { it.isNotBlank() }

        if (!srcCandidate.isNullOrBlank()) {
            return srcCandidate.takeIf { !it.startsWith("data:") }
        }

        val srcsetCandidate =
            imageElement
                ?.attr("srcset")
                ?.takeIf { it.isNotBlank() }
                ?.split(',')
                ?.mapNotNull { candidate -> candidate.trim().split(' ').firstOrNull()?.trim() }
                ?.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

        if (!srcsetCandidate.isNullOrBlank()) {
            return srcsetCandidate
        }

        // From https://gitlab.com/spacecowboy/Feeder
        // Using negative lookahead to skip data: urls, being inline base64
        // And capturing original quote to use as ending quote
        // Base64 encoded images can be quite large - and crash database cursors
        return imgRegex.find(text)?.groupValues?.get(2)?.takeIf { !it.startsWith("data:") }
    }

    private fun normalizeImageUrl(imageUrl: String, articleLink: String?, feedLink: String?): String {
        val trimmed = imageUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        val base = articleLink?.takeIf { it.isNotBlank() } ?: feedLink?.takeIf { it.isNotBlank() }
        return runCatching {
            if (base == null) trimmed else java.net.URI(base).resolve(trimmed).toString()
        }.getOrDefault(trimmed)
    }

    suspend fun queryRssIconLink(feedLink: String?): String? {
        if (feedLink.isNullOrEmpty()) return null
        val iconFinder = BestIconFinder(okHttpClient)
        val domain = feedLink.extractDomain()
        return iconFinder.findBestIcon(domain ?: feedLink).also {
            Log.i("RLog", "queryRssIconByLink: get $it from $domain")
        }
    }

    suspend fun saveRssIcon(feedDao: FeedDao, feed: Feed, iconLink: String) {
        feedDao.update(feed.copy(icon = iconLink))
    }

    private suspend fun response(
        client: OkHttpClient,
        url: String,
        etag: String? = null,
        lastModified: String? = null,
    ): okhttp3.Response {
        val requestBuilder = Request.Builder().url(url)
        etag?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("If-None-Match", it) }
        lastModified
            ?.takeIf { it.isNotBlank() }
            ?.let { requestBuilder.header("If-Modified-Since", it) }
        return client.newCall(requestBuilder.build()).executeAsync()
    }

    private fun parseRetryAfterMillis(retryAfter: String?): Long? {
        if (retryAfter.isNullOrBlank()) return null
        val seconds = retryAfter.toLongOrNull()
        if (seconds != null) {
            return (seconds * 1000L).coerceAtLeast(1000L)
        }
        val parsedDate =
            runCatching {
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                        isLenient = false
                        timeZone = TimeZone.getTimeZone("GMT")
                    }.parse(retryAfter)
                }
                .getOrNull()
                ?: return null
        return (parsedDate.time - System.currentTimeMillis()).coerceAtLeast(1000L)
    }
}

private fun String.withInjectedPodcastAudioTag(podcastUrl: String?): String {
    if (podcastUrl.isNullOrBlank()) return this
    if (contains(podcastUrl)) return this
    return "$this<audio preload=\"none\" src=\"$podcastUrl\"></audio>"
}
