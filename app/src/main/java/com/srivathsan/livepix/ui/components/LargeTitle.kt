package com.srivathsan.livepix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.srivathsan.livepix.ui.theme.Ios
import com.srivathsan.livepix.ui.theme.IosType

/**
 * UINavigationBar with a large title: the big title sits under the buttons until the content
 * scrolls, then it collapses into a centred title on a solid bar with a hairline, like iOS.
 */
@Composable
fun LargeTitleHeader(
    title: String,
    subtitle: String?,
    collapsed: Boolean,
    top: Dp,
    modifier: Modifier = Modifier,
    hasButtons: Boolean = true,
    buttons: @Composable BoxScope.() -> Unit = {},
) {
    val c = Ios.colors
    val solid by animateFloatAsState(if (collapsed) 1f else 0f, label = "bar")
    Box(modifier.fillMaxWidth()) {
        // Background: soft fade while expanded, solid bar once collapsed.
        Box(
            Modifier.matchParentSize().alpha(1f - solid)
                .background(Brush.verticalGradient(listOf(c.background, c.background.copy(alpha = 0.92f), c.background.copy(alpha = 0f)))),
        )
        Box(Modifier.matchParentSize().alpha(solid).background(c.background.copy(alpha = 0.96f)))
        Column(Modifier.fillMaxWidth().padding(top = top + 6.dp, start = 16.dp, end = 16.dp, bottom = if (collapsed) 8.dp else 18.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                buttons()
                androidx.compose.animation.AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
                    Text(
                        title, style = IosType.headline, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 96.dp),
                    )
                }
            }
            if (!collapsed) {
                Text(title, style = IosType.largeTitle, color = c.label, modifier = Modifier.padding(top = if (hasButtons) 4.dp else 0.dp))
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel)
                }
            }
        }
        if (collapsed) HorizontalDivider(Modifier.align(Alignment.BottomCenter).alpha(solid), thickness = 0.5.dp, color = c.separator)
    }
}
