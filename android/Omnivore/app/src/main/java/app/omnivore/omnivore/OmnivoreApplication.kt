package app.omnivore.omnivore

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.omnivore.omnivore.core.analytics.IntercomManager
import app.omnivore.omnivore.utils.isSecretConfigured
import dagger.hilt.android.HiltAndroidApp
import io.intercom.android.sdk.Intercom
import javax.inject.Inject

@HiltAndroidApp
class OmnivoreApplication: Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

  override fun onCreate() {
    super.onCreate()

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
