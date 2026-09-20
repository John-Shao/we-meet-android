package com.we.meet.ui.nav

import androidx.navigation.NavController

/** Removed content must not leave its upload/capture detail as the back target. */
internal fun NavController.openLibraryAfterRecordRemoval() {
    navigate("meeting_records?summaries=false") {
        popUpTo(Routes.HOME) { inclusive = false }
        launchSingleTop = true
    }
}
