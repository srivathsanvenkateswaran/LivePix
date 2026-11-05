package com.srivathsan.livepix.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.R
import com.srivathsan.livepix.ui.components.glass
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

@Composable
fun BusyOverlay(label: String) {
    Box(Modifier.fillMaxSize().background(Color(0x33000000)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(
            Modifier.glass(RoundedCornerShape(18.dp)).padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Ios.colors.label, strokeWidth = 2.5.dp)
            Spacer(Modifier.height(12.dp))
            Text(label, style = IosType.subheadline, color = Ios.colors.label)
        }
    }
}

val MEDIA_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

fun hasMediaAccess(context: android.content.Context): Boolean =
    MEDIA_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ||
        (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

/** First-run screen, modelled on the iOS photo access prompt. */
@Composable
fun PermissionScreen(onResult: () -> Unit) {
    val c = Ios.colors
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    Column(
        Modifier.fillMaxSize().background(c.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
        Text("LivePix", style = IosType.largeTitle, color = c.label)
        Spacer(Modifier.height(10.dp))
        Text(
            "Allow access to your photos to view Live Photos, change Portrait depth, and pick key frames for pictures taken on iPhone.",
            style = IosType.body, color = c.secondaryLabel, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "Allow Access to Photos", style = IosType.headline, color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.blue)
                .clickable {
                    val perms = if (Build.VERSION.SDK_INT >= 34) MEDIA_PERMISSIONS + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED else MEDIA_PERMISSIONS
                    launcher.launch(perms)
                }
                .padding(vertical = 15.dp),
        )
    }
}
