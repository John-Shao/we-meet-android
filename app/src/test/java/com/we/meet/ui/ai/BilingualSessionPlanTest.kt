package com.we.meet.ui.ai

import com.we.meet.data.api.AssistantTranslationPair
import org.junit.Assert.*
import org.junit.Test

class BilingualSessionPlanTest {
    private val pair = AssistantTranslationPair("zh", "en")
    @Test fun automaticRoutingRequiresTwoTranslatorsAndOneDetector() {
        assertTrue(BilingualSessionPlan(pair).automatic)
        assertEquals(3, BilingualSessionPlan(pair).connections)
    }
    @Test fun fixedDirectionsUseOneTranslatorWithTheCorrectTarget() {
        for ((source, target) in listOf("zh" to "en", "en" to "zh")) {
            val plan = BilingualSessionPlan(pair, source)
            assertFalse(plan.automatic)
            assertEquals(1, plan.connections)
            assertEquals(source, plan.inputLanguage)
            assertEquals(target, plan.outputLanguage)
        }
    }
    @Test fun unrelatedSourceIsRejectedBeforeAllocation() {
        assertTrue(runCatching { BilingualSessionPlan(pair, "fr") }.isFailure)
    }
}
