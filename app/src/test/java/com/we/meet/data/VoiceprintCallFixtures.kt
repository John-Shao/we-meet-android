package com.we.meet.data

import com.we.meet.data.api.dto.*

internal object VoiceprintCallFixtures {
    const val ROOM = "RM_synthetic"
    const val PARTICIPANT = "PA_synthetic"
    const val OBSERVED = "2099-10-10T00:00:03Z"
    fun connection(organization: String? = null) = VoiceprintCallConnectionDto(ROOM, organization,
        organization?.let { "Synthetic organization" }, OBSERVED,
        VoiceprintCallLimitsDto(3000, 60000, 120000, 86400),
        VoiceprintCallPermissionDto(true, 7, true, true),
        VoiceprintCallControlDto(VoiceprintFixtures.ENROLLMENT, PARTICIPANT, 3, true, true, "", "paused", "",
            VoiceprintCallRuntimeDto("stopped", "paused", null)))
    fun ready() = connection().let { it.copy(control = it.control.copy(paused = false, sharedMicrophone = false,
        deviceGroup = "headset", state = "ready", runtime = VoiceprintCallRuntimeDto("sampling", "", "2099-10-10T00:00:00Z",
            VoiceprintCallRemainingDto(57000, 117000)))) }
}
