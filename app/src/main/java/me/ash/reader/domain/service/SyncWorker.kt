package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.model.account.Account
import me.ash.reader.infrastructure.rss.ReaderCacheHelper

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val readerCacheHelper: ReaderCacheHelper,
    private val workManager: WorkManager,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val data = inputData
        val accountId = data.getInt("accountId", -1)
        require(accountId != -1)
        if (!tryAcquireAccountSync(accountId)) {
            return Result.success()
        }
        val feedId = data.getString("feedId")
        val groupId = data.getString("groupId")

        val progressReporter =
            SyncProgressReporter { currentFeedName, completedFeeds, totalFeeds ->
                setProgress(
                    workDataOf(
                        PROGRESS_CURRENT_FEED_NAME to (currentFeedName ?: ""),
                        PROGRESS_COMPLETED_FEEDS to completedFeeds,
                        PROGRESS_TOTAL_FEEDS to totalFeeds,
                    )
                )
            }

        progressReporter.onProgress(currentFeedName = null, completedFeeds = 0, totalFeeds = 0)

        try {
            return rssService
                .get()
                .sync(
                    accountId = accountId,
                    feedId = feedId,
                    groupId = groupId,
                    progressReporter = progressReporter,
                )
                .also {
                    rssService.get().clearKeepArchivedArticles().forEach {
                        readerCacheHelper.deleteCacheFor(articleId = it.id)
                    }
                    workManager
                        .beginUniqueWork(
                            uniqueWorkName = POST_SYNC_WORK_NAME,
                            existingWorkPolicy = ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<ReaderWorker>()
                                .addTag(READER_TAG)
                                .addTag(ONETIME_WORK_TAG)
                                .setBackoffCriteria(
                                    backoffPolicy = BackoffPolicy.EXPONENTIAL,
                                    backoffDelay = 30,
                                    timeUnit = TimeUnit.SECONDS,
                                )
                                .build(),
                        )
                        .then(OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build())
                        .enqueue()
                }
        } finally {
            releaseAccountSync(accountId)
        }
    }

    companion object {
        private const val SYNC_WORK_NAME_PERIODIC = "ReadYou"
        @Deprecated("do not use")
        private const val READER_WORK_NAME_PERIODIC = "FETCH_FULL_CONTENT_PERIODIC"
        private const val POST_SYNC_WORK_NAME = "POST_SYNC_WORK"

        private const val SYNC_ONETIME_NAME = "SYNC_ONETIME"

        const val SYNC_TAG = "SYNC_TAG"
        const val READER_TAG = "READER_TAG"
        const val ONETIME_WORK_TAG = "ONETIME_WORK_TAG"
        const val PERIODIC_WORK_TAG = "PERIODIC_WORK_TAG"

        const val PROGRESS_CURRENT_FEED_NAME = "sync_progress_current_feed_name"
        const val PROGRESS_COMPLETED_FEEDS = "sync_progress_completed_feeds"
        const val PROGRESS_TOTAL_FEEDS = "sync_progress_total_feeds"

        private val runningAccountSyncs = ConcurrentHashMap.newKeySet<Int>()

        private fun tryAcquireAccountSync(accountId: Int): Boolean =
            runningAccountSyncs.add(accountId)

        private fun releaseAccountSync(accountId: Int) {
            runningAccountSyncs.remove(accountId)
        }

        fun cancelOneTimeWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(SYNC_ONETIME_NAME)
        }

        fun cancelPeriodicWork(workManager: WorkManager) {
            workManager.cancelUniqueWork(SYNC_WORK_NAME_PERIODIC)
            workManager.cancelUniqueWork(READER_WORK_NAME_PERIODIC)
        }

        fun enqueueOneTimeWork(workManager: WorkManager, inputData: Data = workDataOf()) {
            workManager
                .beginUniqueWork(
                    SYNC_ONETIME_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .addTag(SYNC_TAG)
                        .addTag(ONETIME_WORK_TAG)
                        .setInputData(inputData)
                        .build(),
                )
                .enqueue()
        }

        fun enqueuePeriodicWork(account: Account, workManager: WorkManager) {
            val syncInterval = account.syncInterval
            val syncOnlyWhenCharging = account.syncOnlyWhenCharging
            val syncOnlyOnWiFi = account.syncOnlyOnWiFi
            val workState =
                workManager
                    .getWorkInfosForUniqueWork(SYNC_WORK_NAME_PERIODIC)
                    .get()
                    .firstOrNull()
                    ?.state

            val policy =
                if (workState == WorkInfo.State.ENQUEUED || workState == WorkInfo.State.RUNNING)
                    ExistingPeriodicWorkPolicy.UPDATE
                else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE

            workManager.enqueueUniquePeriodicWork(
                SYNC_WORK_NAME_PERIODIC,
                policy,
                PeriodicWorkRequestBuilder<SyncWorker>(syncInterval.value, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresCharging(syncOnlyWhenCharging.value)
                            .setRequiredNetworkType(
                                if (syncOnlyOnWiFi.value) NetworkType.UNMETERED
                                else NetworkType.CONNECTED
                            )
                            .build()
                    )
                    .setBackoffCriteria(
                        backoffPolicy = BackoffPolicy.EXPONENTIAL,
                        backoffDelay = 30,
                        timeUnit = TimeUnit.SECONDS,
                    )
                    .setInputData(workDataOf("accountId" to account.id))
                    .addTag(SYNC_TAG)
                    .addTag(PERIODIC_WORK_TAG)
                    .setInitialDelay(syncInterval.value, TimeUnit.MINUTES)
                    .build(),
            )

            workManager.cancelUniqueWork(READER_WORK_NAME_PERIODIC)
        }
    }
}
