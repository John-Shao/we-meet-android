package com.we.meet.feature.docs.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Both drawers use the full requested width, including on tablets and in landscape.
 * Material's stock sheet caps its inner column at 360 dp even with a wider surface. */
@Composable
internal fun DocNavigationDrawerSheet(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(),
        shape = DrawerDefaults.shape,
        color = DrawerDefaults.modalContainerColor,
        tonalElevation = DrawerDefaults.ModalDrawerElevation,
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(DrawerDefaults.windowInsets), content = content)
    }
}
