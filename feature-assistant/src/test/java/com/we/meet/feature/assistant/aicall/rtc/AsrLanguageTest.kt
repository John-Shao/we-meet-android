package com.we.meet.feature.assistant.aicall.rtc

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrLanguageTest {
    @Test fun usesSupportedUiLocale() {
        assertEquals("zh", AsrLanguage.current(Locale.SIMPLIFIED_CHINESE))
        assertEquals("en", AsrLanguage.current(Locale.ENGLISH))
        assertEquals("de", AsrLanguage.current(Locale.GERMAN))
        assertEquals("fr", AsrLanguage.current(Locale.FRENCH))
    }

    @Test fun leavesUnsupportedLocaleOnAutoDetect() {
        assertNull(AsrLanguage.current(Locale.forLanguageTag("nl-NL")))
    }
}
