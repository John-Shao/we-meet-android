package com.we.meet.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.we.meet.R
import com.we.meet.ui.ai.AiHubScreen
import com.we.meet.ui.home.HomeScreen
import com.we.meet.ui.profile.ProfileScreen

private data class TabItem(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val tabs = listOf(
    TabItem(R.string.tab_meeting, Icons.Filled.Videocam, Icons.Filled.Videocam),
    TabItem(R.string.tab_ai, Icons.Filled.AutoAwesome, Icons.Filled.AutoAwesome),
    TabItem(R.string.tab_profile, Icons.Filled.Person, Icons.Filled.Person),
)

@Composable
fun MainTabScreen(
    onCreateMeeting: () -> Unit,
    onJoinMeeting: () -> Unit,
    onJoinSlug: (slug: String) -> Unit,
    onScanQrCode: () -> Unit,
    onHistoryClick: (roomId: String) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenAssistantCall: () -> Unit,
    onSignedOut: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            CompactTabBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
            )
        },
    ) { padding ->
        when (selectedTab) {
            0 -> {
                Box(modifier = Modifier.padding(padding)) {
                    HomeScreen(
                        onCreateMeeting = onCreateMeeting,
                        onJoinMeeting = onJoinMeeting,
                        onJoinSlug = onJoinSlug,
                        onScanQrCode = onScanQrCode,
                        onHistoryClick = onHistoryClick,
                        onSettingsClick = onSettingsClick,
                    )
                }
            }
            1 -> {
                Box(modifier = Modifier.padding(padding)) {
                    AiHubScreen(onOpenAssistantCall = onOpenAssistantCall)
                }
            }
            2 -> {
                Box(modifier = Modifier.padding(padding)) {
                    ProfileScreen(onSignedOut = onSignedOut)
                }
            }
        }
    }
}

@Composable
private fun CompactTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = selectedTab == index
                val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onTabSelected(index) },
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = stringResource(tab.labelRes),
                        tint = color,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        fontSize = 10.sp,
                        color = color,
                        lineHeight = 12.sp,
                    )
                }
            }
        }
    }
}
