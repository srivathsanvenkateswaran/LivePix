package com.srivathsan.livepix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.LibraryState
import com.srivathsan.livepix.data.Asset
import com.srivathsan.livepix.data.GeoPoint
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType
import java.text.DateFormatSymbols
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Adjust Date & Time. With several items, the first moves to the chosen time and the rest shift by
 * the same amount, keeping their spacing, as in Photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustDateSheet(assets: List<Asset>, onAdjust: (Long) -> Unit, onRevert: () -> Unit, onDismiss: () -> Unit) {
    val c = Ios.colors
    val first = assets.first()
    val start = remember { Calendar.getInstance().apply { timeInMillis = first.dateTaken } }
    var year by remember { mutableIntStateOf(start.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(start.get(Calendar.MONTH)) }
    var day by remember { mutableIntStateOf(start.get(Calendar.DAY_OF_MONTH)) }
    var hour by remember { mutableIntStateOf(start.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(start.get(Calendar.MINUTE)) }
    val daysInMonth = remember(year, month) {
        Calendar.getInstance().apply { clear(); set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    val adjusted = remember(year, month, day, hour, minute, daysInMonth) {
        Calendar.getInstance().apply {
            timeInMillis = first.dateTaken
            set(year, month, day.coerceAtMost(daysInMonth), hour, minute)
        }.timeInMillis
    }
    val years = remember { (start.get(Calendar.YEAR) - 40..Calendar.getInstance().get(Calendar.YEAR) + 1).toList() }
    val months = remember { DateFormatSymbols.getInstance().shortMonths.take(12) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (c.isDark) Color(0xFF1C1C1E) else c.groupedBackground,
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            SheetHeader("Adjust Date & Time", "Adjust", onCancel = onDismiss) { onAdjust(adjusted) }
            Spacer(Modifier.height(14.dp))
            Text("Original", style = IosType.footnote, color = c.secondaryLabel)
            Text(Format.fullDate(first.originalDate ?: first.dateTaken), style = IosType.subheadline, color = c.label)
            Spacer(Modifier.height(8.dp))
            Text("Adjusted", style = IosType.footnote, color = c.secondaryLabel)
            Text(Format.fullDate(adjusted), style = IosType.subheadline, color = c.blue)
            if (assets.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${Format.count(assets.size, "item")} will move by the same amount, keeping their order.",
                    style = IosType.footnote, color = c.secondaryLabel,
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The band behind the centre row, as on iOS wheels.
                Box(Modifier.fillMaxWidth().height(WHEEL_ROW).clip(RoundedCornerShape(8.dp)).background(c.fill))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    key(daysInMonth) {
                        Wheel((1..daysInMonth).map { it.toString() }, day.coerceAtMost(daysInMonth) - 1, Modifier.weight(0.8f)) { day = it + 1 }
                    }
                    Wheel(months, month, Modifier.weight(1f)) { month = it }
                    Wheel(years.map { it.toString() }, years.indexOf(year).coerceAtLeast(0), Modifier.weight(1.1f)) { year = years[it] }
                    Wheel((0..23).map { "%02d".format(it) }, hour, Modifier.weight(0.8f)) { hour = it }
                    Wheel((0..59).map { "%02d".format(it) }, minute, Modifier.weight(0.8f)) { minute = it }
                }
            }
            if (assets.any { it.originalDate != null }) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Revert to Original", style = IosType.body, color = c.red, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onRevert).padding(vertical = 8.dp),
                )
            }
        }
    }
}

private val WHEEL_ROW = 36.dp

@Composable
private fun Wheel(values: List<String>, index: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val c = Ios.colors
    val list = rememberLazyListState(index)
    val rowPx = with(LocalDensity.current) { WHEEL_ROW.toPx() }
    val current = { (list.firstVisibleItemIndex + (list.firstVisibleItemScrollOffset / rowPx).roundToInt()).coerceIn(values.indices) }
    LaunchedEffect(list, values.size) {
        snapshotFlow { current() }.collect { onChange(it) }
    }
    LazyColumn(
        state = list,
        flingBehavior = rememberSnapFlingBehavior(list),
        contentPadding = PaddingValues(vertical = WHEEL_ROW * 2),
        modifier = modifier.height(WHEEL_ROW * 5),
    ) {
        itemsIndexed(values) { i, v ->
            Box(Modifier.fillMaxWidth().height(WHEEL_ROW), contentAlignment = Alignment.Center) {
                Text(v, style = IosType.body, color = if (i == current()) c.label else c.tertiaryLabel, maxLines = 1)
            }
        }
    }
}

/** Adjust Location: search the bundled place names, or remove the location. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustLocationSheet(
    assets: List<Asset>,
    state: LibraryState,
    onPick: (GeoPoint?) -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ios.colors
    var query by remember { mutableStateOf("") }
    val results = remember(query, state.geo) { state.geo?.search(query).orEmpty() }
    val edited = assets.any { state.traitsOf(it)?.locationEdited == true }
    // Before typing, offer the places already in the library.
    val suggested = remember(state.geo, state.traits) {
        state.assets.mapNotNull { a -> state.traitsOf(a)?.point?.let { state.geo?.nearest(it.lat, it.lng) } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (c.isDark) Color(0xFF1C1C1E) else c.groupedBackground,
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            SheetHeader("Adjust Location", null, onCancel = onDismiss) {}
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = c.secondaryLabel, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search for a city", style = IosType.body, color = c.secondaryLabel)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = IosType.body.copy(color = c.label), cursorBrush = SolidColor(c.blue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (query.isBlank()) {
                    item {
                        LocationRow(Icons.Outlined.LocationOff, "No Location", null) { onPick(null) }
                        if (edited) LocationRow(Icons.Outlined.Place, "Revert to Original", null, tint = c.red) { onRevert() }
                    }
                }
                items(if (query.isBlank()) suggested else results) { city ->
                    LocationRow(Icons.Outlined.Place, city.name, listOf(city.region, city.country).filter { it.isNotEmpty() }.distinct().joinToString(", ")) {
                        onPick(city.point)
                    }
                }
                if (query.isNotBlank() && results.isEmpty()) {
                    item { Text("No places found", style = IosType.subheadline, color = c.secondaryLabel, modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LocationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = Ios.colors.blue,
    onClick: () -> Unit,
) {
    val c = Ios.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = IosType.body, color = if (tint == c.red) c.red else c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = IosType.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(Modifier.padding(start = 34.dp), thickness = 0.5.dp, color = c.separator)
}

@Composable
private fun SheetHeader(title: String, confirm: String?, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = Ios.colors
    Box(Modifier.fillMaxWidth()) {
        Text("Cancel", style = IosType.body, color = c.blue, modifier = Modifier.align(Alignment.CenterStart).clickable(onClick = onCancel))
        Text(title, style = IosType.headline, color = c.label, modifier = Modifier.align(Alignment.Center))
        if (confirm != null) {
            Text(
                confirm, style = IosType.body.copy(fontWeight = FontWeight.SemiBold), color = c.blue,
                modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onConfirm),
            )
        }
    }
}
