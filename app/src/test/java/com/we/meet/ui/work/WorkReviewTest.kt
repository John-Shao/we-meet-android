package com.we.meet.ui.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.we.meet.data.api.*
import com.we.meet.data.repository.WorkRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private const val RUN = "b435958e-79be-4111-8f83-d36a48e3ef56"
private val FILE = WorkFileDto("report.md", "a".repeat(64))
private fun review(status: String = "queued") = WorkReviewDto(
    "4807b44f-16b3-4583-bde3-9c4d7d1cbfae", RUN, status, "qwen3.8-flash",
    selection = listOf(FILE), snapshot = listOf(FILE), reservedTokens = 4096,
)
private class ReviewFixture : WorkFixture() {
    var enabled: Boolean? = true
    var rows = listOf(review())
    var failure = false
    var calls = 0
    var barrier: CompletableDeferred<Unit>? = null
    override suspend fun capabilities() = WorkCapabilities(true, enabled)
    override suspend fun task(id: String) = WorkTaskDto(id, "review results", listOf(WorkRunDto(RUN, "succeeded", "local")))
    override suspend fun files(id: String) = listOf(FILE)
    override suspend fun reviews(id: String): List<WorkReviewDto> {
        calls++; barrier?.await()
        if (failure) throw IOException("source access revoked")
        return rows
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkReviewTest {
    @Test fun missingInformationKeepsHistoricalFindingsInconclusiveWithoutRewritingReport() {
        val report = WorkReviewReport("needs_changes", "Model opinion", missingInformation = listOf("Original input was not supplied"))
        assertEquals("inconclusive", reviewVerdict(report))
        assertEquals("needs_changes", report.verdict)
        assertEquals("needs_changes", reviewVerdict(report.copy(missingInformation = emptyList())))
    }
    @Test fun disabledReviewerRetainsHistoryAndOldServerSkipsUnsupportedApi() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = ReviewFixture().apply { enabled = false }
            val vm = WorkViewModel(WorkRepository(api), SavedStateHandle()).also { store.put("work", it) }
            runCurrent(); vm.select("task"); runCurrent()
            assertFalse(vm.ui.value.reviewEnabled)
            assertEquals(1, vm.ui.value.reviews.size)
            assertEquals(1, api.calls)
            api.enabled = null
            vm.refresh(); runCurrent()
            assertTrue(vm.ui.value.reviews.isEmpty())
            assertFalse(vm.ui.value.reviewsUnavailable)
            assertEquals(1, api.calls)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun completedOriginalContinuesPollingReviewAndRevocationClearsCachedContent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = ReviewFixture()
            val vm = WorkViewModel(WorkRepository(api), SavedStateHandle()).also { store.put("work", it) }
            runCurrent(); vm.select("task"); runCurrent()
            api.rows = listOf(review("succeeded").copy(inputTokens = 100, outputTokens = 30,
                report = WorkReviewReport("no_issues", "Review fixture completed")))
            vm.poll(); runCurrent()
            assertEquals("succeeded", vm.ui.value.reviews.single().status)
            assertEquals(100L, vm.ui.value.reviews.single().inputTokens)
            api.failure = true
            vm.refresh(); runCurrent()
            assertTrue(vm.ui.value.reviewsUnavailable)
            assertTrue(vm.ui.value.reviews.isEmpty())
            assertTrue(vm.ui.value.files.isEmpty())
            assertEquals("", vm.ui.value.preview)
            api.failure = false
            vm.refresh(); runCurrent()
            assertFalse(vm.ui.value.reviewsUnavailable)
            assertEquals(1, vm.ui.value.reviews.size)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun lateReviewCannotReopenClosedDetail() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = ReviewFixture()
            val vm = WorkViewModel(WorkRepository(api), SavedStateHandle()).also { store.put("work", it) }
            runCurrent()
            api.barrier = CompletableDeferred()
            vm.select("task"); runCurrent()
            assertEquals(1, api.calls)
            vm.closeDetail()
            api.barrier!!.complete(Unit); runCurrent()
            assertNull(vm.ui.value.selected)
            assertTrue(vm.ui.value.reviews.isEmpty())
            assertTrue(vm.ui.value.files.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun accountChangeDuringRequestDiscardsResultsAndSavedDispatchIntent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            var session = "owner-a"
            val api = ReviewFixture()
            val saved = SavedStateHandle(mapOf("workSession" to "owner-a", "requestId" to "old-run", "requestGoal" to "private goal", "requestWorkspace" to "private-folder"))
            val vm = WorkViewModel(WorkRepository(api) { session }, saved).also { store.put("work", it) }
            runCurrent(); api.barrier = CompletableDeferred()
            vm.select("task"); runCurrent()
            session = "owner-b"; api.barrier!!.complete(Unit); runCurrent()
            assertEquals("work_account_changed", vm.ui.value.error)
            assertNull(vm.ui.value.selected)
            assertTrue(vm.ui.value.reviews.isEmpty())
            assertEquals("", vm.pendingGoal)
            assertNull(saved.get<String>("requestId"))
            saved["workSession"] = "owner-a"; saved["requestId"] = "old-run"; saved["requestGoal"] = "private goal"
            val next = WorkViewModel(WorkRepository(api) { session }, saved).also { store.put("next", it) }
            runCurrent()
            assertFalse(next.ui.value.uncertain)
            assertEquals("", next.pendingGoal)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun mismatchedSourceEvidenceAndUsageAreRejected() = runTest {
        val api = ReviewFixture(); val repo = WorkRepository(api)
        val finding = WorkReviewFinding("warning", "Check claim", listOf(WorkReviewEvidence("report.md", "b".repeat(64), "claim")))
        val invalid = listOf(
            review().copy(sourceRunId = "another-run"),
            review().copy(inputTokens = 100, outputTokens = null),
            review().copy(inputTokens = -1, outputTokens = 0),
            review().copy(report = WorkReviewReport(findings = listOf(finding))),
            review().copy(selection = emptyList()),
            review().copy(report = WorkReviewReport(summary = "x".repeat(4001))),
        )
        invalid.forEach { row ->
            api.rows = listOf(row)
            try { repo.reviews(RUN); fail("Malformed review accepted") } catch (_: IllegalArgumentException) { }
        }
        api.rows = List(6) { review() }
        try { repo.reviews(RUN); fail("Oversized history accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun wireContractAcceptsPendingEmptyReportAndPreservesStructuredEvidence() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(WorkReviewDto::class.java)
        val pending = adapter.toJson(review())
        assertTrue(pending.contains("\"source_run_id\""))
        assertTrue(pending.contains("\"reserved_tokens\""))
        assertEquals(review(), adapter.fromJson(pending))
        val report = WorkReviewReport("needs_changes", "Check selected file", listOf(WorkReviewFinding("warning", "Review claim", listOf(WorkReviewEvidence(FILE.name, FILE.sha256, "<script>literal text</script>")))), listOf("Missing input"))
        val completed = review("succeeded").copy(inputTokens = 100, outputTokens = 30, report = report)
        assertEquals(completed, adapter.fromJson(adapter.toJson(completed)))
        assertEquals(com.we.meet.R.string.work_review_unknown, reviewStatus("upstream-new-state"))
    }
}
