package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.we.meet.feature.docs.R
import com.we.meet.ui.theme.Dimens

@Composable
internal fun DocsFileIcon(folder: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(Modifier.size(Dimens.ListLeadingIcon), contentAlignment = Alignment.Center) {
            Icon(if (folder) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null, modifier = Modifier.size(Dimens.IconMedium))
        }
    }
}

/** Shared hierarchy and an explicit, accessible exit for native document sheets. */
@Composable
internal fun DocsSheetHeader(title: String, onClose: () -> Unit, subtitle: String? = null, onBack: (() -> Unit)? = null, titleMaxLines: Int = Int.MAX_VALUE) {
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.ScreenPadding, end = Dimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_docs_back))
        }
        Column(Modifier.weight(1f).padding(vertical = Dimens.SpaceS)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() },
                maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.docs_close))
        }
    }
}
