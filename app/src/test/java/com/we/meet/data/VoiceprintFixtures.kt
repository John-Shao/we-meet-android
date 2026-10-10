package com.we.meet.data

import com.we.meet.data.api.dto.*
import com.we.meet.data.voiceprint.VoiceprintWave

object VoiceprintFixtures {
    const val OWNER = "11111111-1111-4111-8111-111111111111"
    const val ORGANIZATION = "22222222-2222-4222-8222-222222222222"
    const val PROFILE = "33333333-3333-4333-8333-333333333333"
    const val ENROLLMENT = "44444444-4444-4444-8444-444444444444"
    const val SAMPLE = "55555555-5555-4555-8555-555555555555"
    const val KEY = "66666666-6666-4666-8666-666666666666"
    const val DELETION = "77777777-7777-4777-8777-777777777777"
    const val EXPIRES = "2099-10-10T00:00:00Z"
    val token = "a".repeat(43)
    fun settings(organization: String? = null) = VoiceprintSettingsDto(organization, true, 1, 1, true, false, false,
        listOf(VoiceprintProfileDto(PROFILE, "pending", 1, null, null)))
    fun enrollment(organization: String? = null) = VoiceprintEnrollmentDto(ENROLLMENT, organization, PROFILE, "open", EXPIRES,
        1, 1, (0..5).map { "Synthetic prompt $it" }, 6, emptyList(), 24000, 1, "pcm16_wav", VoiceprintDurationDto(3000, 10000), token)
    fun sample() = VoiceprintSampleDto(SAMPLE, PROFILE, "quality_pending", "enrollment", 3000, EXPIRES, false, true)
    fun deletion() = VoiceprintDeletionDto(DELETION, "queued", 1, null, null)
    fun wav(seconds: Int = 3) = VoiceprintWave.encode(ShortArray(24000 * seconds) { (it % 97).toShort() })
}
