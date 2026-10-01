package com.we.meet.ui.ai

import com.we.meet.data.api.AssistantTranslationPair
import org.junit.Assert.*
import org.junit.Test

class BilingualLanguagesTest {
    @Test fun allDocumentedSpeechLanguagesCanFormBidirectionalPairs() {
        val expected = "zh en ar de fr es pt id it ko ru th vi ja tr hi ms nl ur nb sv da he fi pl is cs fil fa".split(" ").toSet()
        assertEquals(expected, BilingualLanguages.labels.keys)
        for (first in expected) for (second in expected - first) {
            val pair = AssistantTranslationPair(first, second)
            assertTrue(BilingualLanguages.valid(pair))
            assertEquals(second, BilingualLanguages.opposite(pair, first))
            assertEquals(first, BilingualLanguages.opposite(pair, second))
        }
        for (code in "yue el af ast be bg bn bs ca ceb et gl gu hr hu jv kk kn ky lv mk ml mr pa ro sk sl sw tg az uk".split(" ")) {
            assertFalse(BilingualLanguages.valid(AssistantTranslationPair(code, "en")))
            assertFalse(BilingualLanguages.valid(AssistantTranslationPair("en", code)))
        }
    }

    @Test fun selectingOtherSideSwapsAndNeverCreatesDuplicateLanguages() {
        val pair = AssistantTranslationPair("ja", "fr")
        assertEquals(AssistantTranslationPair("fr", "ja"), BilingualLanguages.select(pair, true, "fr"))
        assertEquals(AssistantTranslationPair("fr", "ja"), BilingualLanguages.select(pair, false, "ja"))
        assertEquals(AssistantTranslationPair("ja", "fa"), BilingualLanguages.select(pair, false, "fa"))
        assertFalse(BilingualLanguages.valid(AssistantTranslationPair("ja", "ja")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun resultsOutsideTheSelectedPairAreRejected() {
        BilingualLanguages.opposite(AssistantTranslationPair("ja", "fr"), "en")
    }
}
