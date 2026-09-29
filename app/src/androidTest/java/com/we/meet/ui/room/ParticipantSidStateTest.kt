package com.we.meet.ui.room

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.Dispatchers
import livekit.LivekitModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParticipantSidStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val participant = Participant(
        sid = Participant.Sid(""),
        coroutineDispatcher = Dispatchers.Unconfined,
    )
    private var observedSid = "unobserved"

    @After fun dispose() { participant.dispose() }

    private fun joined(sid: String) {
        participant.updateFromInfo(
            LivekitModels.ParticipantInfo.newBuilder()
                .setSid(sid)
                .setIdentity("local-user")
                .build(),
        )
    }

    @Test fun firstCompositionBeforeJoinDoesNotCrashAndSidTracksReconnectAndCleanup() {
        compose.setContent { observedSid = rememberParticipantSid(participant).value }
        compose.runOnIdle { assertEquals("", observedSid) }
        compose.runOnIdle { joined("PA_first") }
        compose.runOnIdle { assertEquals("PA_first", observedSid) }
        compose.runOnIdle { joined("PA_reconnected") }
        compose.runOnIdle { assertEquals("PA_reconnected", observedSid) }
        compose.runOnIdle { participant.dispose() }
        compose.runOnIdle { assertEquals("", observedSid) }
    }

    @Test fun alreadyJoinedParticipantIsObservedAndRefreshesAfterActivityResumes() {
        joined("PA_initial")
        compose.setContent { observedSid = rememberParticipantSid(participant).value }
        compose.runOnIdle { assertEquals("PA_initial", observedSid) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        joined("PA_background_reconnect")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals("PA_background_reconnect", observedSid) }
    }
}
