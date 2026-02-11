package me.ash.reader.domain.service

fun interface SyncProgressReporter {
    suspend fun onProgress(currentFeedName: String?, completedFeeds: Int, totalFeeds: Int)
}
