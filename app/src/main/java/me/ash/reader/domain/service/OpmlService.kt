package me.ash.reader.domain.service

import android.content.Context
import be.ceau.opml.OpmlWriter
import be.ceau.opml.entity.Body
import be.ceau.opml.entity.Head
import be.ceau.opml.entity.Opml
import be.ceau.opml.entity.Outline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.OPMLDataSource
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.formatUrl
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.get
import java.io.InputStream
import java.util.*
import javax.inject.Inject

/**
 * Supports import and export from OPML files.
 */
class OpmlService @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val accountService: AccountService,
    private val rssService: RssService,
    private val OPMLDataSource: OPMLDataSource,
    @IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Imports OPML file.
     *
     * @param [inputStream] input stream of OPML file
     */
    @Throws(Exception::class)
    suspend fun saveToDatabase(inputStream: InputStream) {
        withContext(ioDispatcher) {
            val defaultGroup = groupDao.queryById(getDefaultGroupId(context.currentAccountId))!!
            val groupWithFeedList =
                OPMLDataSource.parseFileInputStream(inputStream, defaultGroup, context.currentAccountId)
            groupWithFeedList.forEach { groupWithFeed ->
                if (groupWithFeed.group != defaultGroup) {
                    groupDao.insert(groupWithFeed.group)
                }
                val repeatList = mutableListOf<Feed>()
                val normalizedFeedList = mutableListOf<Feed>()
                groupWithFeed.feeds.forEach {
                    it.groupId = groupWithFeed.group.id
                    val normalizedFeedUrl = normalizeImportedFeedUrl(it.url)
                    val normalizedFeed = it.copy(url = normalizedFeedUrl, groupId = it.groupId)
                    normalizedFeedList.add(normalizedFeed)
                    if (rssService.get().isFeedExist(normalizedFeedUrl)) {
                        repeatList.add(normalizedFeed)
                    }
                }
                feedDao.insertList((normalizedFeedList subtract repeatList.toSet()).toList())
            }
        }
    }

    private fun normalizeImportedFeedUrl(rawUrl: String): String {
        val input = rawUrl.trim()
        val rssHubPrefix = "rsshub://"
        if (!input.startsWith(rssHubPrefix, ignoreCase = true)) {
            return input
        }
        val configuredBaseUrl = context.dataStore.get<String>(DataStoreKey.rssHubBaseUrl)?.trim().orEmpty()
        if (configuredBaseUrl.isBlank()) {
            throw IllegalStateException(context.getString(R.string.rsshub_base_url_required))
        }
        val rssHubBaseUrl = configuredBaseUrl.formatUrl().trimEnd('/')
        val feedPath = input.substring(rssHubPrefix.length).trimStart('/')
        return if (feedPath.isBlank()) rssHubBaseUrl else "$rssHubBaseUrl/$feedPath"
    }

    /**
     * Exports OPML file.
     */
    @Throws(Exception::class)
    suspend fun saveToString(accountId: Int, attachInfo: Boolean): String {
        val defaultGroup = groupDao.queryById(getDefaultGroupId(accountId))
        return OpmlWriter().write(
            Opml(
                "2.0",
                Head(
                    accountService.getCurrentAccount().name,
                    Date().toString(), null, null, null,
                    null, null, null, null,
                    null, null, null, null,
                ),
                Body(groupDao.queryAllGroupWithFeed(accountId).map {
                    Outline(
                        mutableMapOf(
                            "text" to it.group.name,
                            "title" to it.group.name,
                        ).apply {
                            if (attachInfo) {
                                put("isDefault", (it.group.id == defaultGroup?.id).toString())
                            }
                        },
                        it.feeds.map { feed ->
                            Outline(
                                mutableMapOf(
                                    "text" to feed.name,
                                    "title" to feed.name,
                                    "xmlUrl" to feed.url,
                                    "htmlUrl" to feed.url
                                ).apply {
                                    if (attachInfo) {
                                        put("isNotification", feed.isNotification.toString())
                                        put("isFullContent", feed.isFullContent.toString())
                                        put("isBrowser", feed.isBrowser.toString())
                                    }
                                },
                                listOf()
                            )
                        }
                    )
                })
            )
        )!!
    }

    private fun getDefaultGroupId(accountId: Int): String = accountId.getDefaultGroupId()
}
