package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.feature.docs.R
import com.we.meet.feature.docs.data.net.DocumentDto
import com.we.meet.ui.theme.Dimens

@Composable
internal fun DocChildrenEntry(doc: DocumentDto, children: List<DocumentDto>, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = Dimens.SpaceL),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(Dimens.SpaceM), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.docs_children_count, doc.numchild), style = MaterialTheme.typography.titleSmall)
                if (children.isNotEmpty()) Text(
                    children.take(3).joinToString(" · ") { it.displayTitle.ifBlank { "…" } },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
        }
    }
}

@Composable
internal fun DocNavigationBar(navigation: DocNavigation, onDirectory: () -> Unit, onSwitch: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = Dimens.ElevationSubtle) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Dimens.SpaceS)) {
            TextButton(onClick = onDirectory, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(Dimens.IconSmall))
                Spacer(Modifier.width(Dimens.SpaceS))
                Text(stringResource(R.string.docs_directory_in,
                    navigation.parent?.displayTitle?.ifBlank { stringResource(R.string.docs_untitled) }.orEmpty()),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (navigation.currentIndex >= 0) Text(
                    "${navigation.currentIndex + 1}/${navigation.siblings.size}",
                    modifier = Modifier.padding(start = Dimens.SpaceS),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS)) {
                NavigationButton(navigation.previous, true, onSwitch, Modifier.weight(1f))
                NavigationButton(navigation.next, false, onSwitch, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavigationButton(doc: DocumentDto?, previous: Boolean, onSwitch: (String) -> Unit, modifier: Modifier) {
    TextButton(onClick = { doc?.let { onSwitch(it.id) } }, enabled = doc != null,
        modifier = modifier.heightIn(min = Dimens.MinTouchTarget)) {
        if (previous) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null)
        Column(Modifier.weight(1f), horizontalAlignment = if (previous) Alignment.Start else Alignment.End) {
            Text(stringResource(if (previous) R.string.docs_previous else R.string.docs_next),
                style = MaterialTheme.typography.labelSmall)
            Text(doc?.displayTitle?.ifBlank { stringResource(R.string.docs_untitled) }
                ?: stringResource(if (previous) R.string.docs_first_document else R.string.docs_last_document),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
        if (!previous) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
    }
}
