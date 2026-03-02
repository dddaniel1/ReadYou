package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.FeedRequestThrottledException
import me.ash.reader.infrastructure.rss.RssHelper
import timber.log.Timber

private const val TAG = "LocalRssService"
private const val LOCAL_FEED_CONCURRENCY = 16
private const val DEFAULT_RETRY_AFTER_MILLIS = 15_000L
private const val MAX_RETRY_AFTER_MILLIS = 5 * 60_000L
private const val URL_BACKOFF_OTHER_INITIAL_MILLIS = 2_000L
private const val URL_BACKOFF_CONNECTIVITY_INITIAL_MILLIS = 3_000L
private const val URL_BACKOFF_TIMEOUT_INITIAL_MILLIS = 4_000L
private const val URL_BACKOFF_MAX_MILLIS = 30_000L
private const val HOST_MIN_CONCURRENCY = 1
private const val HOST_INITIAL_CONCURRENCY = 6
private const val HOST_MAX_CONCURRENCY = 10
private const val HOST_SUCCESS_STREAK_TO_GROW = 2
private const val HOST_SLOT_WAIT_MILLIS = 50L
private const val BACKOFF_JITTER_MAX_MILLIS = 1500L
private const val FEED_HEALTH_MAX_FAILURE_STREAK = 8

class LocalRssService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    private val groupDao: GroupDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    private val accountService: AccountService,
    private val syncLogger: SyncLogger,
) :
    AbstractRssRepository(
        articleDao,
        groupDao,
        feedDao,
        workManager,
        rssHelper,
        notificationHelper,
        ioDispatcher,
        defaultDispatcher,
        accountService,
    ) {

    private data class HostState(
        var currentLimit: Int = HOST_INITIAL_CONCURRENCY,
        var inFlight: Int = 0,
        var successStreak: Int = 0,
        var backoffUntilMillis: Long = 0L,
    )

    private data class UrlBackoffState(
        var failureCount: Int = 0,
        var backoffUntilMillis: Long = 0L,
    )

    private data class FeedHealthState(
        var failureStreak: Int = 0,
        var lastFailureAtMillis: Long = 0L,
    )

    private enum class NonThrottleFailureKind {
        Timeout,
        Connectivity,
        Other,
    }

    override suspend fun sync(
        accountId: Int,
        feedId: String?,
        groupId: String?,
        progressReporter: SyncProgressReporter?,
    ): ListenableWorker.Result = supervisorScope {
        return@supervisorScope runCatching {
            val preTime = System.currentTimeMillis()
            val preDate = Date(preTime)
            val currentAccount = accountService.getAccountById(accountId)!!
            require(currentAccount.type.id == AccountType.Local.id) {
                "Account type is invalid"
            }
            val semaphore = Semaphore(LOCAL_FEED_CONCURRENCY)
            val hostBackoffUntilMap = ConcurrentHashMap<String, Long>()
            val urlBackoffStateMap = ConcurrentHashMap<String, UrlBackoffState>()
            val hostStateMap = ConcurrentHashMap<String, HostState>()

            val feedsToSync =
                when {
                    feedId != null -> listOfNotNull(feedDao.queryById(feedId))
                    groupId != null -> feedDao.queryByGroupId(accountId, groupId)
                    else -> feedDao.queryAll(accountId)
                }
            val prioritizedFeedsToSync = prioritizeFeeds(feedsToSync)

            val totalFeeds = prioritizedFeedsToSync.size
            val completedFeeds = AtomicInteger(0)
            val failedFeeds = AtomicInteger(0)
            progressReporter?.onProgress(null, completedFeeds.get(), totalFeeds)

            prioritizedFeedsToSync
                .mapIndexed { _, currentFeed ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val hostLease = acquireHostSlot(currentFeed.url, hostStateMap)
                            try {
                                applyHostBackoffIfNeeded(currentFeed.url, hostBackoffUntilMap)
                                applyUrlBackoffIfNeeded(currentFeed.url, urlBackoffStateMap)
                                progressReporter?.onProgress(
                                    currentFeed.name,
                                    completedFeeds.get(),
                                    totalFeeds,
                                )
                                val archivedArticles =
                                    feedDao
                                        .queryArchivedArticles(currentFeed.id)
                                        .map { it.link }
                                        .toSet()
                                val fetchedFeed =
                                    try {
                                        syncFeed(currentFeed, preDate)
                                    } catch (e: FeedRequestThrottledException) {
                                        registerHostBackoff(
                                            url = currentFeed.url,
                                            retryAfterMillis = e.retryAfterMillis,
                                            hostBackoffUntilMap = hostBackoffUntilMap,
                                        )
                                        applyFailurePenalty(
                                            url = currentFeed.url,
                                            hostStateMap = hostStateMap,
                                            retryAfterMillis = e.retryAfterMillis,
                                        )
                                        throw e
                                    } catch (e: Exception) {
                                        registerUrlBackoff(
                                            url = currentFeed.url,
                                            failureKind = classifyNonThrottleFailure(e),
                                            urlBackoffStateMap = urlBackoffStateMap,
                                        )
                                        throw e
                                    }
                                val fetchedArticles =
                                    fetchedFeed.articles.filterNot {
                                        archivedArticles.contains(it.link)
                                    }

                                recordHostSuccess(currentFeed.url, hostStateMap)
                                recordUrlSuccess(currentFeed.url, urlBackoffStateMap)
                                recordFeedHealthSuccess(currentFeed.url)

                                val newArticles =
                                    articleDao.insertListIfNotExist(
                                        articles = fetchedArticles,
                                        feed = currentFeed,
                                    )
                                if (currentFeed.isNotification && newArticles.isNotEmpty()) {
                                    notificationHelper.notify(
                                        fetchedFeed.copy(articles = newArticles, feed = currentFeed)
                                    )
                                }
                                progressReporter?.onProgress(
                                    currentFeed.name,
                                    completedFeeds.incrementAndGet(),
                                    totalFeeds,
                                )
                            } catch (e: Exception) {
                                failedFeeds.incrementAndGet()
                                recordFeedHealthFailure(currentFeed.url)
                                syncLogger.log(e)
                                Timber.tag(TAG)
                                    .w(e, "sync feed failed: ${currentFeed.name} (${currentFeed.url})")
                                progressReporter?.onProgress(
                                    currentFeed.name,
                                    completedFeeds.incrementAndGet(),
                                    totalFeeds,
                                )
                            } finally {
                                releaseHostSlot(hostLease, hostStateMap)
                            }
                        }
                    }
                }
                .awaitAll()

            Timber.tag("RlOG").i("onCompletion: ${System.currentTimeMillis() - preTime}")
            if (failedFeeds.get() > 0) {
                Timber.tag(TAG).w("sync completed with ${failedFeeds.get()} feed failures")
            }
            accountService.update(currentAccount.copy(updateAt = Date()))
            ListenableWorker.Result.success()
        }
            .onFailure {
                syncLogger.log(it)
                Timber.tag(TAG).e(it, "sync round failed")
            }
            .getOrNull() ?: ListenableWorker.Result.retry()
    }

    private suspend fun syncFeed(feed: Feed, preDate: Date = Date()): FeedWithArticle {
        val fetchResult = rssHelper.queryRssXml(feed, "", preDate)
        val validatorsChanged =
            (fetchResult.etag != feed.etag) || (fetchResult.lastModified != feed.lastModified)
        if (validatorsChanged) {
            feedDao.update(
                feed.copy(
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified,
                )
            )
        }
        val articles = fetchResult.articles
        if (feed.icon == null) {
            val iconLink = rssHelper.queryRssIconLink(feed.url)
            if (iconLink != null) {
                rssHelper.saveRssIcon(feedDao, feed, iconLink)
            }
        }
        return FeedWithArticle(
            feed = feed.copy(isNotification = feed.isNotification && articles.isNotEmpty()),
            articles = articles,
        )
    }

    private suspend fun applyHostBackoffIfNeeded(
        url: String,
        hostBackoffUntilMap: Map<String, Long>,
    ) {
        val host = extractHost(url) ?: return
        val retryAt = hostBackoffUntilMap[host] ?: return
        val waitMillis = retryAt - System.currentTimeMillis()
        if (waitMillis > 0) {
            delay(waitMillis)
        }
    }

    private fun registerHostBackoff(
        url: String,
        retryAfterMillis: Long?,
        hostBackoffUntilMap: MutableMap<String, Long>,
    ) {
        val host = extractHost(url) ?: return
        val cappedRetryAfter =
            (retryAfterMillis ?: DEFAULT_RETRY_AFTER_MILLIS).coerceIn(
                minimumValue = 1000L,
                maximumValue = MAX_RETRY_AFTER_MILLIS,
            )
        val candidateRetryAt = System.currentTimeMillis() + cappedRetryAfter
        val previousRetryAt = hostBackoffUntilMap[host]
        if (previousRetryAt == null || candidateRetryAt > previousRetryAt) {
            hostBackoffUntilMap[host] = candidateRetryAt
        }
    }

    private suspend fun applyUrlBackoffIfNeeded(
        url: String,
        urlBackoffStateMap: Map<String, UrlBackoffState>,
    ) {
        val retryAt = urlBackoffStateMap[url]?.backoffUntilMillis ?: return
        val waitMillis = retryAt - System.currentTimeMillis()
        if (waitMillis > 0) {
            delay(waitMillis)
        }
    }

    private fun registerUrlBackoff(
        url: String,
        failureKind: NonThrottleFailureKind,
        urlBackoffStateMap: MutableMap<String, UrlBackoffState>,
    ) {
        val jitterMillis = ThreadLocalRandom.current().nextLong(BACKOFF_JITTER_MAX_MILLIS + 1)
        val initialBackoffMillis =
            when (failureKind) {
                NonThrottleFailureKind.Timeout -> URL_BACKOFF_TIMEOUT_INITIAL_MILLIS
                NonThrottleFailureKind.Connectivity -> URL_BACKOFF_CONNECTIVITY_INITIAL_MILLIS
                NonThrottleFailureKind.Other -> URL_BACKOFF_OTHER_INITIAL_MILLIS
            }
        val state = urlBackoffStateMap.getOrPut(url) { UrlBackoffState() }
        val exponent = state.failureCount.coerceAtMost(4)
        val baseBackoffMillis =
            (initialBackoffMillis * (1L shl exponent)).coerceAtMost(URL_BACKOFF_MAX_MILLIS)
        val candidateRetryAt = System.currentTimeMillis() + baseBackoffMillis + jitterMillis
        if (candidateRetryAt > state.backoffUntilMillis) {
            state.backoffUntilMillis = candidateRetryAt
        }
        state.failureCount += 1
    }

    private fun recordUrlSuccess(
        url: String,
        urlBackoffStateMap: MutableMap<String, UrlBackoffState>,
    ) {
        val state = urlBackoffStateMap[url] ?: return
        state.failureCount = 0
        state.backoffUntilMillis = 0L
    }

    private suspend fun acquireHostSlot(
        url: String,
        hostStateMap: MutableMap<String, HostState>,
    ): String? {
        val host = extractHost(url) ?: return null
        val state = hostStateMap.getOrPut(host) { HostState() }
        while (true) {
            val waitMillis =
                synchronized(state) {
                    val now = System.currentTimeMillis()
                    when {
                        now < state.backoffUntilMillis -> state.backoffUntilMillis - now
                        state.inFlight < state.currentLimit -> {
                            state.inFlight += 1
                            0L
                        }

                        else -> HOST_SLOT_WAIT_MILLIS
                    }
                }
            if (waitMillis <= 0L) {
                return host
            }
            delay(waitMillis)
        }
    }

    private fun releaseHostSlot(host: String?, hostStateMap: MutableMap<String, HostState>) {
        if (host == null) return
        val state = hostStateMap[host] ?: return
        synchronized(state) {
            state.inFlight = (state.inFlight - 1).coerceAtLeast(0)
        }
    }

    private fun recordHostSuccess(url: String, hostStateMap: MutableMap<String, HostState>) {
        val host = extractHost(url) ?: return
        val state = hostStateMap[host] ?: return
        synchronized(state) {
            state.successStreak += 1
            if (
                state.successStreak >= HOST_SUCCESS_STREAK_TO_GROW &&
                    state.currentLimit < HOST_MAX_CONCURRENCY
            ) {
                state.currentLimit += 1
                state.successStreak = 0
            }
        }
    }

    private fun applyFailurePenalty(
        url: String,
        hostStateMap: MutableMap<String, HostState>,
        retryAfterMillis: Long?,
    ) {
        val host = extractHost(url) ?: return
        val state = hostStateMap.getOrPut(host) { HostState() }
        val jitterMillis = ThreadLocalRandom.current().nextLong(BACKOFF_JITTER_MAX_MILLIS + 1)
        val baseBackoffMillis =
            retryAfterMillis
                ?.coerceIn(1000L, MAX_RETRY_AFTER_MILLIS)
                ?: DEFAULT_RETRY_AFTER_MILLIS
        synchronized(state) {
            state.successStreak = 0
            state.currentLimit = (state.currentLimit - 1).coerceAtLeast(HOST_MIN_CONCURRENCY)
            val candidateBackoffUntil = System.currentTimeMillis() + baseBackoffMillis + jitterMillis
            if (candidateBackoffUntil > state.backoffUntilMillis) {
                state.backoffUntilMillis = candidateBackoffUntil
            }
        }
    }

    private fun classifyNonThrottleFailure(throwable: Throwable): NonThrottleFailureKind {
        return when (throwable) {
            is SocketTimeoutException -> NonThrottleFailureKind.Timeout
            is ConnectException, is UnknownHostException, is SSLException ->
                NonThrottleFailureKind.Connectivity
            else -> NonThrottleFailureKind.Other
        }
    }

    private fun prioritizeFeeds(feeds: List<Feed>): List<Feed> {
        return feeds.sortedWith(
            compareBy<Feed> { feedHealthStateMap[it.url]?.failureStreak ?: 0 }
                .thenBy { feedHealthStateMap[it.url]?.lastFailureAtMillis ?: 0L }
        )
    }

    private fun recordFeedHealthSuccess(url: String) {
        feedHealthStateMap.remove(url)
    }

    private fun recordFeedHealthFailure(url: String) {
        val state = feedHealthStateMap.getOrPut(url) { FeedHealthState() }
        state.failureStreak = (state.failureStreak + 1).coerceAtMost(FEED_HEALTH_MAX_FAILURE_STREAK)
        state.lastFailureAtMillis = System.currentTimeMillis()
    }

    private fun extractHost(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase(Locale.US) }.getOrNull()

    companion object {
        private val feedHealthStateMap = ConcurrentHashMap<String, FeedHealthState>()
    }
}
