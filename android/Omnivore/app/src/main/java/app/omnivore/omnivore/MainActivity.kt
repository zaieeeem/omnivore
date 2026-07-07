package app.omnivore.omnivore

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.omnivore.omnivore.feature.root.RootView
import app.omnivore.omnivore.feature.theme.OmnivoreTheme
import app.omnivore.omnivore.utils.hasPspdfkitLicense
import com.pspdfkit.PSPDFKit
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {

        installSplashScreen()

        super.onCreate(savedInstanceState)


        // Only initialize PSPDFKit (proprietary, license-keyed) when a real
        // license is configured. Without one the app never touches PSPDFKit and
        // opens PDFs through the system viewer instead (see PDFReaderActivity),
        // so a missing/dummy license can never crash the app.
        if (hasPspdfkitLicense(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    PSPDFKit.initialize(
                        this@MainActivity,
                        getString(R.string.pspdfkit_license_key)
                    )
                } catch (e: Throwable) {
                    Log.e("MainActivity", "PSPDFKit initialization failed", e)
                }
            }
        }

        enableEdgeToEdge()

        setContent {
            OmnivoreTheme {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    RootView()
                }
            }
        }
    }
}
