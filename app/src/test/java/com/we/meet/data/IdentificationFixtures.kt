package com.we.meet.data

import com.we.meet.data.api.dto.*

internal object IdentificationFixtures {
    const val OWNER = "11111111-1111-4111-8111-111111111111"
    const val RECORD = "22222222-2222-4222-8222-222222222222"
    const val SPEAKER = "33333333-3333-4333-8333-333333333333"
    const val ORG = "44444444-4444-4444-8444-444444444444"
    const val KEY = "55555555-5555-4555-8555-555555555555"
    const val JOB = "66666666-6666-4666-8666-666666666666"
    const val SUGGESTION = "77777777-7777-4777-8777-777777777777"
    fun options(revision: Int = 1) = IdentityOptionsDto(revision, null, true,
        listOf(IdentityPersonDto(SPEAKER, "Speaker 0")), IdentityPageDto(listOf(IdentityScopeDto(ORG, "Organization", true)), null))
    fun suggestion() = IdentitySuggestionDto(SUGGESTION, "pending", "suggested", "all_clips_agree", 3, 12000,
        listOf(IdentityIntervalDto(0, 4000), IdentityIntervalDto(10000, 14000), IdentityIntervalDto(20000, 24000)), true, false, IdentityPersonDto(OWNER, "Synthetic Reviewer"))
    fun response(processing: Boolean = false, key: String = KEY, revision: Int = 1) = IdentityResponseDto(revision,
        IdentityBatchDto(ORG, key, null, 1, "2026-10-10T00:00:00Z", processing, listOf(IdentityJobDto(JOB, SPEAKER,
            if (processing) "queued" else "succeeded", false, if (processing) null else suggestion()))))
    fun submission() = IdentitySubmissionDto(KEY, 1, null, listOf(OWNER), listOf(SPEAKER))
}
