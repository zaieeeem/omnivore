package app.omnivore.omnivore.feature.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.compose.ui.text.intl.Locale
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.omnivore.omnivore.R
import app.omnivore.omnivore.core.data.repository.LibraryRepository
import app.omnivore.omnivore.core.datastore.DatastoreRepository
import app.omnivore.omnivore.core.datastore.libraryAutoSyncEnabled
import app.omnivore.omnivore.core.datastore.libraryAutoSyncHomeWifiSsid
import app.omnivore.omnivore.core.datastore.libraryLastSyncTimestamp
import app.omnivore.omnivore.core.datastore.omnivoreAuthToken
import app.omnivore.omnivore.core.network.Networker
import app.omnivore.omnivore.graphql.generated.SaveUrlMutation
import app.omnivore.omnivore.graphql.generated.type.SaveUrlInput
import app.omnivore.omnivore.utils.Constants
import app.omnivore.omnivore.utils.currentWifiSsid
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val libraryRepository: LibraryRepository,
    private val datastoreRepository: DatastoreRepository,
    private val networker: Networker,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LibrarySyncWorker"

        const val PERIODIC_WORK_NAME = "library-auto-sync"
        const val INPUT_IS_PERIODIC = "isPeriodicSync"

        private const val SYNC_INTERVAL_HOURS = 4L
        private const val MAX_PREFETCH_ITEMS = 500
        private const val REACHABILITY_TIMEOUT_MS = 3_000

        /**
         * Enqueues (or updates) the periodic background auto-sync. Use
         * [ExistingPeriodicWorkPolicy.KEEP] on app start and
         * [ExistingPeriodicWorkPolicy.UPDATE] when settings change.
         */
        fun schedulePeriodic(
            context: Context,
            policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        ) {
            val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(
                SYNC_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setInputData(workDataOf(INPUT_IS_PERIODIC to true))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, policy, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    private val isPeriodicRun: Boolean
        get() = inputData.getBoolean(INPUT_IS_PERIODIC, false)

    override suspend fun doWork(): Result {
        return try {
            withContext(Dispatchers.IO) {
                if (isPeriodicRun && !shouldRunPeriodicSync()) {
                    return@withContext Result.success()
                }

                if (!isServerReachable()) {
                    Log.d(TAG, "API server unreachable, skipping sync")
                    // A periodic run just waits for the next interval; a
                    // save-triggered sync retries with backoff instead.
                    return@withContext if (isPeriodicRun) Result.success() else Result.retry()
                }

                performItemSync()
                loadUsingSearchAPI()
                Log.d(TAG, "Library sync completed successfully")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in LibrarySyncWorker", e)
            Result.failure()
        }
    }

    private suspend fun shouldRunPeriodicSync(): Boolean {
        if (datastoreRepository.getString(omnivoreAuthToken) == null) {
            Log.d(TAG, "Skipping auto-sync: not logged in")
            return false
        }

        if (!datastoreRepository.getBooleanOrDefault(libraryAutoSyncEnabled, true)) {
            Log.d(TAG, "Skipping auto-sync: disabled in settings")
            return false
        }

        val homeSsid = datastoreRepository.getString(libraryAutoSyncHomeWifiSsid)
            ?: return true // no home network configured: any Wi-Fi is fine

        val currentSsid = currentWifiSsid(applicationContext)
        if (currentSsid != homeSsid) {
            Log.d(
                TAG,
                "Skipping auto-sync: current Wi-Fi (${currentSsid ?: "unknown"}) " +
                    "is not the configured home network"
            )
            return false
        }

        return true
    }

    /**
     * Cheap reachability probe of the configured API server. Any HTTP response
     * (even an error status) counts as reachable; only connect/timeout
     * failures do not — a self-hosted server may only be reachable on the
     * home LAN.
     */
    private suspend fun isServerReachable(): Boolean {
        return try {
            val connection =
                URL("${networker.baseUrl()}/_ah/health").openConnection() as HttpURLConnection
            connection.connectTimeout = REACHABILITY_TIMEOUT_MS
            connection.readTimeout = REACHABILITY_TIMEOUT_MS
            connection.requestMethod = "GET"
            try {
                connection.responseCode
                true
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun performItemSync(
        cursor: String? = null,
        since: String = getLastSyncTime()?.toString() ?: Instant.MIN.toString(),
        count: Int = 0,
        syncStart: String = Instant.now().toString(),
    ) {
        libraryRepository.syncOfflineItemsWithServerIfNeeded()

        val result = libraryRepository.sync(
            context = applicationContext,
            since = since,
            cursor = cursor,
            limit = 10
        )
        val totalCount = count + result.count

        if (result.hasError) {
            result.errorString?.let { errorString ->
                Log.e("LibrarySyncWorker", "SYNC ERROR: $errorString")
            }
        }

        if (!result.hasError && result.hasMoreItems && result.cursor != null) {
            performItemSync(
                cursor = result.cursor,
                since = since,
                count = totalCount,
                syncStart = syncStart
            )
        } else {
            datastoreRepository.putString(libraryLastSyncTimestamp, syncStart)
        }
    }

    private suspend fun loadUsingSearchAPI() {
        var cursor: String? = null
        var scanned = 0
        var downloaded = 0

        // Walk the whole inbox page by page so every article is available
        // offline, fetching content sequentially to avoid hammering the
        // (typically self-hosted) server.
        while (scanned < MAX_PREFETCH_ITEMS) {
            val result = libraryRepository.librarySearch(
                context = applicationContext,
                cursor = cursor,
                query = "${SavedItemFilter.INBOX.queryString} ${SavedItemSortFilter.NEWEST.queryString}"
            )

            if (result.savedItems.isEmpty()) {
                break
            }

            result.savedItems.forEach {
                val isSavedInDB =
                    libraryRepository.isSavedItemContentStoredInDB(
                        applicationContext,
                        it.savedItem.slug
                    )

                if (!isSavedInDB) {
                    libraryRepository.fetchSavedItemContent(applicationContext, it.savedItem.slug)
                    downloaded++
                }
                scanned++
            }

            Log.d(TAG, "Content prefetch progress: $scanned items checked, $downloaded downloaded")

            cursor = result.cursor ?: break
        }

        Log.d(TAG, "Content prefetch done: $scanned items checked, $downloaded downloaded")
    }

    private fun getLastSyncTime(): Instant? = runBlocking {
        datastoreRepository.getString(libraryLastSyncTimestamp)?.let {
            try {
                return@let Instant.parse(it)
            } catch (e: Exception) {
                return@let null
            }
        }
    }
}
