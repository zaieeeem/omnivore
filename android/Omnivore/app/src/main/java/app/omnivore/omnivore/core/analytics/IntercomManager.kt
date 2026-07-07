package app.omnivore.omnivore.core.analytics

import android.util.Log
import io.intercom.android.sdk.Intercom

/**
 * Central gate for Intercom (proprietary in-app support chat). Disabled by
 * default for the self-hosted build; enabled only when a real Intercom API key
 * and app id are configured and initialization succeeds (see
 * OmnivoreApplication). All Intercom access must go through [ifEnabled] so a
 * missing/dummy key never triggers Intercom.client() before initialize().
 */
object IntercomManager {
    @Volatile
    var enabled: Boolean = false

    /** Runs [block] only when Intercom is initialized; swallows any failure. */
    inline fun ifEnabled(block: (Intercom) -> Unit) {
        if (!enabled) return
        try {
            block(Intercom.client())
        } catch (e: Exception) {
            Log.w("IntercomManager", "Intercom call failed", e)
        }
    }
}
