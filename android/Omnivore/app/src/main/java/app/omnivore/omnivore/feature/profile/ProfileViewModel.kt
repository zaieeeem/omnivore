package app.omnivore.omnivore.feature.profile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import app.omnivore.omnivore.core.data.DataService
import app.omnivore.omnivore.core.datastore.DatastoreRepository
import app.omnivore.omnivore.core.datastore.libraryAutoSyncEnabled
import app.omnivore.omnivore.core.datastore.libraryAutoSyncHomeWifiSsid
import app.omnivore.omnivore.core.datastore.libraryLastSyncTimestamp
import app.omnivore.omnivore.core.network.Networker
import app.omnivore.omnivore.core.network.viewer
import app.omnivore.omnivore.feature.library.LibrarySyncWorker
import app.omnivore.omnivore.utils.currentWifiSsid
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import app.omnivore.omnivore.core.analytics.IntercomManager
import io.intercom.android.sdk.IntercomSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val networker: Networker,
    private val dataService: DataService,
    private val datastoreRepo: DatastoreRepository
) : ViewModel() {

    var snackbarMessage by mutableStateOf("Resetting data...")

    var isResettingData by mutableStateOf(false)
    var isDataResetCompleted by mutableStateOf(false)

    var autoSyncEnabled by mutableStateOf(true)
        private set
    var homeWifiSsid by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            autoSyncEnabled = datastoreRepo.getBooleanOrDefault(libraryAutoSyncEnabled, true)
            homeWifiSsid = datastoreRepo.getString(libraryAutoSyncHomeWifiSsid)
        }
    }

    fun updateAutoSyncEnabled(enabled: Boolean) {
        autoSyncEnabled = enabled
        viewModelScope.launch {
            datastoreRepo.putBoolean(libraryAutoSyncEnabled, enabled)
            if (enabled) {
                LibrarySyncWorker.schedulePeriodic(
                    applicationContext,
                    ExistingPeriodicWorkPolicy.UPDATE
                )
            } else {
                LibrarySyncWorker.cancelPeriodic(applicationContext)
            }
        }
    }

    /**
     * Captures the current Wi-Fi SSID as the "home" network that auto-sync is
     * restricted to. Invokes [onResult] with false when the SSID can't be
     * read (not on Wi-Fi, permission missing, or redacted by the system).
     */
    fun captureHomeWifi(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ssid = currentWifiSsid(applicationContext)
            if (ssid == null) {
                onResult(false)
            } else {
                datastoreRepo.putString(libraryAutoSyncHomeWifiSsid, ssid)
                homeWifiSsid = ssid
                onResult(true)
            }
        }
    }

    fun clearHomeWifi() {
        homeWifiSsid = null
        viewModelScope.launch {
            datastoreRepo.clearValue(libraryAutoSyncHomeWifiSsid)
        }
    }

    fun resetDataCache() {
        isResettingData = true

        viewModelScope.launch {
            datastoreRepo.clearValue(libraryLastSyncTimestamp)
            dataService.clearDatabase()
            delay(1000)
            isResettingData = false

            if (!isDataResetCompleted) {
                isDataResetCompleted = true
            }
        }
    }

    fun presentIntercom() {
        viewModelScope.launch {
            val viewer = networker.viewer()
            viewer?.let { v ->
                IntercomManager.ifEnabled { client ->
                    v.intercomHash?.let { intercomHash ->
                        client.setUserHash(intercomHash)
                    }
                    client.present(space = IntercomSpace.Messages)
                }
            }
        }
    }
}
