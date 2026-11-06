package com.srivathsan.livepix

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.srivathsan.livepix.data.hasMediaLocation
import com.srivathsan.livepix.ui.LivePixApp
import com.srivathsan.livepix.ui.PermissionScreen
import com.srivathsan.livepix.ui.hasMediaAccess
import com.srivathsan.livepix.ui.theme.LivePixTheme

class MainActivity : ComponentActivity() {
    private val vm: GalleryViewModel by viewModels()
    private var externalUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        externalUri = intent.viewUri()
        setContent {
            LivePixTheme {
                val context = LocalContext.current
                var granted by remember { mutableStateOf(hasMediaAccess(context)) }
                if (granted) {
                    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        // Places and photo locations need GPS, which Android otherwise blanks out of files.
                        if (hasMediaLocation(context)) vm.refresh() else askLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                    LivePixApp(vm, externalUri)
                } else {
                    PermissionScreen { granted = hasMediaAccess(context) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        externalUri = intent.viewUri()
    }

    private fun Intent.viewUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null
}
