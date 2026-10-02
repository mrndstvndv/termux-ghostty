package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class PathSegment(val name: String, val fullPath: String)

private fun parsePathSegments(path: String): List<PathSegment> {
    if (path.isBlank() || path == "/") {
        return listOf(PathSegment("/", "/"))
    }
    val segments = mutableListOf(PathSegment("/", "/"))
    val parts = path.split("/").filter { it.isNotEmpty() }
    var currentPath = ""
    for (part in parts) {
        currentPath += "/$part"
        segments.add(PathSegment(part, currentPath))
    }
    return segments
}

@Composable
fun SftpBreadcrumbs(
    currentPath: String,
    trailPath: String,
    onSegmentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isTabActive: Boolean = true
) {
    val effectivePath = if (trailPath.isNotBlank()) trailPath else currentPath
    val segments = remember(effectivePath) { parsePathSegments(effectivePath) }
    val scrollState = rememberScrollState()
    val activeSegmentRequester = remember { BringIntoViewRequester() }
    val activeSegmentIndex = segments.indexOfFirst { it.fullPath == currentPath }
    var revealedPath by remember { mutableStateOf<String?>(null) }

    // BringIntoView propagates to ALL ancestors including HorizontalPager: an offscreen
    // SFTP page requesting it yanks the pager Terminal->Git overshooting onto SFTP.
    // Only request when this tab is active (pager already settled on SFTP), so the
    // request scopes to the breadcrumb Row's own horizontalScroll.
    // Reveal once per path: isTabActive flickers when a breadcrumb fling hands its
    // leftover velocity to the pager, which would snap a manual scroll back.
    LaunchedEffect(currentPath, effectivePath, isTabActive) {
        if (!isTabActive || activeSegmentIndex < 0 || revealedPath == currentPath) return@LaunchedEffect
        activeSegmentRequester.bringIntoView()
        revealedPath = currentPath
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            segments.forEachIndexed { index, segment ->
                val isCurrent = segment.fullPath == currentPath
                SftpBreadcrumbSegment(
                    segment = segment,
                    isCurrent = isCurrent,
                    activeSegmentRequester = activeSegmentRequester,
                    onSegmentClick = onSegmentClick
                )

                if (index < segments.lastIndex) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SftpBreadcrumbSegment(
    segment: PathSegment,
    isCurrent: Boolean,
    activeSegmentRequester: BringIntoViewRequester,
    onSegmentClick: (String) -> Unit
) {
    Surface(
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .then(
                if (isCurrent) {
                    Modifier.bringIntoViewRequester(activeSegmentRequester)
                } else {
                    Modifier
                }
            )
            .clickable {
                onSegmentClick(segment.fullPath)
            }
    ) {
        Text(
            text = segment.name,
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
