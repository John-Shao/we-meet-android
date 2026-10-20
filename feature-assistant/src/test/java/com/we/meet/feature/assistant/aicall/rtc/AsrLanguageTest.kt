package com.we.meet.feature.assistant.aicall.rtc

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrLanguageTest {
    @Test fun pinsMandarinForChineseUi() {
        assertEquals("zh", AsrLanguage.current(Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun keepsAutoDetectionForOtherUiLanguages() {
        assertNull(AsrLanguage.current(Locale.ENGLISH))
        assertNull(AsrLanguage.current(Locale.GERMAN))
        assertNull(AsrLanguage.current(Locale.FRENCH))
        assertNull(AsrLanguage.current(Locale.forLanguageTag("nl-NL")))
    }
}
