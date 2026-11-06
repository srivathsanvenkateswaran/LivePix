package com.srivathsan.livepix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.AssetEdit
import com.srivathsan.livepix.data.City
import com.srivathsan.livepix.data.LiveEffect
import com.srivathsan.livepix.data.Traits
import com.srivathsan.livepix.media.PhotoInfo
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InfoSheet(asset: Asset, traits: Traits?, edit: AssetEdit, place: City?, onDismiss: () -> Unit) {
    val c = Ios.colors
    val actions = LocalAssetActions.current
    val context = LocalContext.current
    val info by produceState(PhotoInfo(), asset.key) {
        value = withContext(Dispatchers.IO) { PhotoInfo.read(context, asset) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.groupedBackground.takeUnless { c.isDark } ?: Color1C,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.fullDate(asset.dateTaken), style = IosType.headline, color = c.label, modifier = Modifier.weight(1f))
                Text("Adjust", style = IosType.subheadline, color = c.blue, modifier = Modifier.clickable { onDismiss(); actions.adjustDate(listOf(asset)) }.padding(start = 12.dp))
            }
            Spacer(Modifier.height(4.dp))
            val tags = listOfNotNull(
                if (asset.isLive) (if (edit.effect == LiveEffect.LIVE) "Live Photo" else edit.effect.label) else null,
                if (traits?.hasDepth == true) "Portrait" else null,
                if (asset.isHeif) "HEIF" else asset.mime.substringAfter('/').uppercase(),
            )
            Text(tags.joinToString(" · "), style = IosType.subheadline, color = c.secondaryLabel)
            Spacer(Modifier.height(16.dp))

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (c.isDark) Color2C else c.background)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(info.camera ?: "Unknown Camera", style = IosType.headline, color = c.label, modifier = Modifier.weight(1f))
                    Text(asset.mime.substringAfter('/').uppercase(), style = IosType.caption2, color = c.secondaryLabel,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(c.fill).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                Column(Modifier.padding(14.dp)) {
                    info.lens?.let { Text(it, style = IosType.subheadline, color = c.label) }
                    val mp = asset.width.toLong() * asset.height / 1_000_000.0
                    Text(
                        listOfNotNull(
                            if (mp > 0) "%.0f MP".format(mp) else null,
                            if (asset.width > 0) "${asset.width} × ${asset.height}" else null,
                            Format.bytes(asset.size),
                            if (asset.isVideo) Format.duration(asset.durationMs) else null,
                        ).joinToString(" • "),
                        style = IosType.subheadline, color = c.secondaryLabel,
                    )
                }
                val pills = listOfNotNull(info.iso, info.focal, info.ev, info.aperture, info.shutter)
                if (pills.isNotEmpty()) {
                    HorizontalDivider(thickness = 0.5.dp, color = c.separator)
                    FlowRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        pills.forEach { Text(it, style = IosType.footnote.copy(fontWeight = FontWeight.Medium), color = c.secondaryLabel) }
                    }
                }
            }
            traits?.point?.let { point ->
                Spacer(Modifier.height(14.dp))
                MiniMap(listOf(point), Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(14.dp)))
            }
            Spacer(Modifier.height(14.dp))
            InfoRow("File", asset.name)
            InfoRow("Album", asset.folder)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                val point = traits?.point
                Text("Location", style = IosType.subheadline, color = c.secondaryLabel, modifier = Modifier.weight(0.35f))
                Text(
                    place?.fullName ?: point?.let { "%.5f, %.5f".format(it.lat, it.lng) } ?: "No Location",
                    style = IosType.subheadline, color = c.label, modifier = Modifier.weight(0.5f),
                )
                Text("Adjust", style = IosType.subheadline, color = c.blue, modifier = Modifier.weight(0.15f).clickable { onDismiss(); actions.adjustLocation(listOf(asset)) })
            }
            if (asset.isLive) {
                InfoRow("Live Photo", if (edit.keyFrameUs != null) "Custom key photo" else "Original key photo")
                InfoRow("Live format", if (asset.isMotionPhoto) "Motion Photo (movie inside the file)" else "HEIC + MOV pair")
            }
            if (traits?.hasDepth == true) InfoRow("Depth", if (traits.hasMatte) "Depth map + portrait matte" else "Depth map")
        }
    }
}

private val Color1C = androidx.compose.ui.graphics.Color(0xFF1C1C1E)
private val Color2C = androidx.compose.ui.graphics.Color(0xFF2C2C2E)

@Composable
private fun InfoRow(label: String, value: String) {
    val c = Ios.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = IosType.subheadline, color = c.secondaryLabel, modifier = Modifier.weight(0.35f))
        Text(value, style = IosType.subheadline, color = c.label, modifier = Modifier.weight(0.65f))
    }
}
