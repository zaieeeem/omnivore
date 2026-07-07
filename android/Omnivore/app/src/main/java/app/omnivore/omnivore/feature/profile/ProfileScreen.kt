package app.omnivore.omnivore.feature.profile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import app.omnivore.omnivore.R
import app.omnivore.omnivore.core.designsystem.component.SwitchPreferenceWidget
import app.omnivore.omnivore.core.designsystem.component.TextPreferenceWidget
import app.omnivore.omnivore.feature.onboarding.OnboardingViewModel
import app.omnivore.omnivore.navigation.Routes

internal const val RELEASE_URL = "https://github.com/omnivore-app/omnivore/releases"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    navController: NavHostController,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    profileViewModel: ProfileViewModel = hiltViewModel()
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.profile_view_title)) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
        )
    }) { paddingValues ->
        SettingsViewContent(
            onboardingViewModel = onboardingViewModel,
            profileViewModel = profileViewModel,
            navController = navController,
            paddingValues = paddingValues
        )
    }
}

@Composable
fun SettingsViewContent(
    onboardingViewModel: OnboardingViewModel,
    profileViewModel: ProfileViewModel,
    navController: NavHostController,
    paddingValues: PaddingValues
) {
    val showLogoutDialog = remember { mutableStateOf(false) }

    val state = rememberLazyListState()

    LazyColumn(
        state = state,
        contentPadding = paddingValues,
    ) {

        item {
            TextPreferenceWidget(
                title = stringResource(R.string.profile_filters),
                onPreferenceClick = { navController.navigate(Routes.Filters.route) },
            )
        }

        item { HorizontalDivider() }

        item {
            SwitchPreferenceWidget(
                title = stringResource(R.string.auto_sync_toggle_title),
                subtitle = stringResource(R.string.auto_sync_toggle_subtitle),
                checked = profileViewModel.autoSyncEnabled,
                onCheckedChanged = { profileViewModel.setAutoSyncEnabled(it) },
            )
        }

        // Restricting auto-sync to a specific Wi-Fi needs the SSID, which is
        // only readable without location permission on API 33+
        // (NEARBY_WIFI_DEVICES with neverForLocation). Below that, auto-sync
        // simply runs on any Wi-Fi.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && profileViewModel.autoSyncEnabled) {
            item {
                HomeWifiPreference(profileViewModel = profileViewModel)
            }
        }

        item { HorizontalDivider() }

        item {
            TextPreferenceWidget(
                title = stringResource(R.string.profile_manage_account),
                onPreferenceClick = { navController.navigate(Routes.Account.route) },
            )
        }

        item {
            TextPreferenceWidget(
                title = stringResource(R.string.about_logout),
                onPreferenceClick = { showLogoutDialog.value = true },
            )
        }

        item {
            TextPreferenceWidget(
                title = stringResource(R.string.about_view_title),
                onPreferenceClick = { navController.navigate(Routes.About.route) },
            )
        }
    }

    if (showLogoutDialog.value) {
        LogoutDialog { performLogout ->
            if (performLogout) {
                onboardingViewModel.logout()
            }
            showLogoutDialog.value = false
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun HomeWifiPreference(profileViewModel: ProfileViewModel) {
    val context = LocalContext.current
    val homeWifiSsid = profileViewModel.homeWifiSsid

    val ssidUnavailableMessage = stringResource(R.string.auto_sync_wifi_name_unavailable)
    val permissionDeniedMessage = stringResource(R.string.auto_sync_wifi_permission_denied)

    val captureHomeWifi = {
        profileViewModel.captureHomeWifi { success ->
            if (!success) {
                Toast.makeText(context, ssidUnavailableMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            captureHomeWifi()
        } else {
            Toast.makeText(context, permissionDeniedMessage, Toast.LENGTH_LONG).show()
        }
    }

    TextPreferenceWidget(
        title = stringResource(R.string.auto_sync_home_wifi_title),
        subtitle = if (homeWifiSsid != null) {
            stringResource(R.string.auto_sync_home_wifi_set_subtitle, homeWifiSsid)
        } else {
            stringResource(R.string.auto_sync_home_wifi_any_subtitle)
        },
        onPreferenceClick = {
            if (homeWifiSsid != null) {
                profileViewModel.clearHomeWifi()
            } else if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                captureHomeWifi()
            } else {
                permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        },
    )
}
