package app.getplexo.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.getplexo.mobile.ui.HomeScreen
import app.getplexo.mobile.ui.Ink
import app.getplexo.mobile.ui.PlexoTheme
import app.getplexo.mobile.ui.PlexoViewModel

class MainActivity : ComponentActivity() {

    private var incomingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        incomingUrl = extractUrl(intent)

        setContent {
            PlexoTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    val vm: PlexoViewModel = viewModel()
                    HomeScreen(
                        vm = vm,
                        initialUrl = incomingUrl,
                        onInitialUrlUsed = { incomingUrl = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrl(intent)?.let { incomingUrl = it }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("https?://\\S+")
            .find(text)
            ?.value
            ?.trimEnd('.', ',', ')', ']', '"', '\'')
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001
    }
}
