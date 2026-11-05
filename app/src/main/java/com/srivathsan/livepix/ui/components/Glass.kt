package com.srivathsan.livepix.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

/** iOS 26 "Liquid Glass" approximation: translucent fill, hairline highlight, soft shadow. */
fun Modifier.glass(shape: Shape, fill: Color, border: Color, elevation: Dp = 10.dp): Modifier =
    this.shadow(elevation, shape, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
        .clip(shape)
        .background(fill)
        .border(BorderStroke(0.75.dp, border), shape)

@Composable
fun Modifier.glass(shape: Shape = CircleShape, dark: Boolean? = null): Modifier {
    val c = Ios.colors
    val isDark = dark ?: c.isDark
    return glass(
        shape,
        if (isDark) Color(0xB82C2C2E) else Color(0xE6FAFAFA),
        if (isDark) Color(0x24FFFFFF) else Color(0xB3FFFFFF),
    )
}

/** Shrinks slightly while pressed, like UIKit buttons. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    return scale(s)
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Ios.colors.label,
    dark: Boolean? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .size(size)
            .glass(CircleShape, dark)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.48f))
    }
}

@Composable
fun GlassTextButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ios.colors.label,
    dark: Boolean? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .height(44.dp)
            .glass(RoundedCornerShape(50), dark)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = IosType.headline.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/** A glass capsule holding several icon buttons, like the viewer's center toolbar group. */
@Composable
fun GlassGroup(modifier: Modifier = Modifier, dark: Boolean? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(48.dp).glass(RoundedCornerShape(50), dark).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
fun GroupIcon(icon: ImageVector, contentDescription: String, tint: Color = Ios.colors.label, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(interaction).size(44.dp).clip(CircleShape).clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(23.dp)) }
}

/** UISegmentedControl, iOS 26 style: glass track with a sliding highlighted capsule. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ios.colors
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val segment = with(density) { (width / options.size.coerceAtLeast(1)).toDp() }
    val offset by animateDpAsState(segment * selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "seg")
    Box(
        modifier.height(40.dp).glass(RoundedCornerShape(50)).padding(3.dp).onSizeChanged { width = it.width },
    ) {
        Box(
            Modifier.offset(x = offset).width(segment).fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(if (c.isDark) Color(0x33FFFFFF) else Color(0x14000000)),
        )
        Row(Modifier.fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.width(segment).fillMaxHeight().clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = IosType.subheadline.copy(fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium), color = c.label)
                }
            }
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** The floating iOS 26 tab bar: a glass capsule of tabs plus a separate round search button. */
@Composable
fun FloatingTabBar(
    tabs: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    searchIcon: ImageVector,
    searchSelected: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ios.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.height(62.dp).glass(RoundedCornerShape(50)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, tab ->
                val active = i == selected && !searchSelected
                val bg by animateColorAsState(
                    if (active) (if (c.isDark) Color(0x2EFFFFFF) else Color(0x12000000)) else Color.Transparent, label = "tabbg",
                )
                val tint = if (active) c.blue else c.label
                Column(
                    Modifier.fillMaxHeight().width(92.dp).clip(RoundedCornerShape(50)).background(bg)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (active) tab.selectedIcon else tab.icon, null, tint = tint, modifier = Modifier.size(25.dp))
                    Text(tab.label, style = IosType.caption2.copy(letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold), color = tint)
                }
            }
        }
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.pressScale(interaction).size(62.dp).glass(CircleShape)
                .clickable(interaction, indication = null, onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(searchIcon, "Search", tint = if (searchSelected) c.blue else c.label, modifier = Modifier.size(26.dp))
        }
    }
}
