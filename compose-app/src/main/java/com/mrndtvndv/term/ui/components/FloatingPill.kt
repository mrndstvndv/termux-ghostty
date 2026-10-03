package com.mrndtvndv.term.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private val PillShape = RoundedCornerShape(28.dp)
private val PillSizeSpring = spring<IntSize>(dampingRatio = 0.8f, stiffness = 380f)

/** Floating pill that springs between [state] contents. Callers place it and own the insets. */
@Composable
fun <T> FloatingPill(
    state: T,
    modifier: Modifier = Modifier,
    label: String = "FloatingPill",
    content: @Composable (T) -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .shadow(elevation = 6.dp, shape = PillShape)
                .clip(PillShape),
            shape = PillShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            AnimatedContent(
                targetState = state,
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    val spec = spring<Float>(stiffness = Spring.StiffnessMedium)
                    (fadeIn(spec) + scaleIn(initialScale = 0.92f, animationSpec = spec))
                        .togetherWith(fadeOut(spec) + scaleOut(targetScale = 0.92f, animationSpec = spec))
                        .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> PillSizeSpring }))
                },
                label = label,
                content = { content(it) }
            )
        }
    }
}

@Composable
fun PillActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
fun PillIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(onClick = onClick, modifier = modifier.size(44.dp)) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
fun PillDivider() {
    VerticalDivider(
        modifier = Modifier
            .height(20.dp)
            .padding(horizontal = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}
