package app.omnivore.omnivore.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val UNKNOWN_SSID = "<unknown ssid>"
private const val SSID_READ_TIMEOUT_MS = 2_000L

/**
 * Returns true when this device/app can read the current Wi-Fi SSID:
 * API 33+ and the NEARBY_WIFI_DEVICES runtime permission
 * (declared with neverForLocation, so no location permission is involved).
 */
fun canReadWifiSsid(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.NEARBY_WIFI_DEVICES
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Reads the SSID of the currently connected Wi-Fi network, or null when it is
 * unavailable (not on Wi-Fi, permission missing, below API 33, or the system
 * redacted it). Never throws.
 */
suspend fun currentWifiSsid(context: Context): String? {
    if (!canReadWifiSsid(context)) return null
    return try {
        currentWifiSsidApi33(context)
    } catch (e: Exception) {
        null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private suspend fun currentWifiSsidApi33(context: Context): String? {
    val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java) ?: return null

    return withTimeoutOrNull(SSID_READ_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback(
                FLAG_INCLUDE_LOCATION_INFO
            ) {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                    val ssid = wifiInfo?.ssid
                        ?.removeSurrounding("\"")
                        ?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
                    connectivityManager.unregisterNetworkCallback(this)
                    if (continuation.isActive) {
                        continuation.resume(ssid)
                    }
                }
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)

            continuation.invokeOnCancellation {
                try {
                    connectivityManager.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                    // already unregistered
                }
            }
        }
    }
}
