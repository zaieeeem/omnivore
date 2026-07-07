package app.omnivore.omnivore

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.omnivore.omnivore.core.analytics.IntercomManager
import app.omnivore.omnivore.core.datastore.DatastoreRepository
import app.omnivore.omnivore.core.datastore.libraryAutoSyncEnabled
import app.omnivore.omnivore.feature.library.LibrarySyncWorker
import app.omnivore.omnivore.utils.isSecretConfigured
import dagger.hilt.android.HiltAndroidApp
import io.intercom.android.sdk.Intercom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OmnivoreApplication: Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var datastoreRepository: DatastoreRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

  override fun onCreate() {
    super.onCreate()

    // Keep the periodic background library sync in step with the login state:
    // schedule it while a user is logged in (and hasn't disabled auto-sync in
    // settings), cancel it on logout. The settings screen re-schedules with
    // UPDATE when the user changes the auto-sync preferences.
    applicationScope.launch {
      datastoreRepository.hasAuthTokenFlow.distinctUntilChanged().collect { hasToken ->
        if (hasToken && datastoreRepository.getBooleanOrDefault(libraryAutoSyncEnabled, true)) {
          LibrarySyncWorker.schedulePeriodic(this@OmnivoreApplication)
        } else {
          LibrarySyncWorker.cancelPeriodic(this@OmnivoreApplication)
        }
      }
    }

    // Intercom (proprietary support chat) is optional for self-hosting. Only
    // initialize it when real credentials are configured; otherwise leave it
    // disabled so a dummy/placeholder key can't crash startup or later calls.
    val intercomApiKey = getString(R.string.intercom_api_key)
    val intercomAppId = getString(R.string.intercom_app_id)
    if (isSecretConfigured(intercomApiKey) && isSecretConfigured(intercomAppId)) {
      try {
        Intercom.initialize(this, intercomApiKey, intercomAppId)
        IntercomManager.enabled = true
      } catch (e: Exception) {
        IntercomManager.enabled = false
        Log.w("OmnivoreApplication", "Intercom initialization failed; disabling", e)
      }
    }
  }
}
