package app.omnivore.omnivore.core.analytics

import android.content.Context
import android.util.Log
import app.omnivore.omnivore.R
import app.omnivore.omnivore.utils.isSecretConfigured
import com.posthog.android.PostHog
import com.posthog.android.Properties
import io.intercom.android.sdk.identity.Registration
import javax.inject.Inject


class EventTracker @Inject constructor(val app: Context) {
    // Null when PostHog (cloud analytics) is not configured for this build.
    // Self-hosted installs run without analytics, so every call is a no-op then.
    private val posthog: PostHog?

    init {
        val posthogClientKey = app.getString(R.string.posthog_client_key)
        val posthogInstanceAddress = app.getString(R.string.posthog_instance_address)

        posthog = if (isSecretConfigured(posthogClientKey) && isSecretConfigured(posthogInstanceAddress)) {
            try {
                PostHog.Builder(app, posthogClientKey, posthogInstanceAddress)
                    .captureApplicationLifecycleEvents()
                    .collectDeviceId(false)
                    .build()
                    .also { PostHog.setSingletonInstance(it) }
            } catch (e: Exception) {
                Log.w("EventTracker", "PostHog initialization failed; disabling analytics", e)
                null
            }
        } else {
            null
        }
    }

    fun registerUser(userID: String, intercomHash: String?, isDebug: Boolean) {
        posthog?.identify(userID)
        if (!isDebug) {
            IntercomManager.ifEnabled { client ->
                client.loginIdentifiedUser(Registration.create().withUserId(userID))
                intercomHash?.let { client.setUserHash(it) }
            }
        }
    }

    fun track(eventName: String, properties: Properties = Properties()) {
        posthog?.capture(eventName, properties)
    }

    fun logout() {
        posthog?.reset()
    }
}
