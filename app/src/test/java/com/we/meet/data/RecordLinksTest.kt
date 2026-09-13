package com.we.meet.data

import com.we.meet.ui.records.RecordLink
import com.we.meet.ui.records.RecordLinks
import org.junit.Assert.*
import org.junit.Test

class RecordLinksTest {
    private val record = "11111111-1111-4111-8111-111111111111"
    private val summary = "22222222-2222-4222-8222-222222222222"
    private val base = "https://meet.example"
    private val path = "/meeting/records/$record"

    @Test fun exactRecordAndSummarySelectors() {
        assertEquals(RecordLink(record), RecordLinks.parse("$base$path", base))
        assertEquals(RecordLink(record, summary), RecordLinks.parse("$base$path?summary=$summary", base))
        assertEquals(RecordLink(record), RecordLinks.parse("https://MEET.example:443$path/", base))
    }

    @Test fun foreignOriginsCredentialsAndFragmentsAreRejected() {
        listOf("http://meet.example$path", "https://meet.example.evil$path", "https://evil.example$path",
            "https://meet.example:444$path", "https://user@meet.example$path", "$base$path#summary=$summary",
            "//$base$path").forEach { assertNull(it, RecordLinks.parse(it, base)) }
    }

    @Test fun invalidOrAmbiguousVersionNeverFallsBackToLatest() {
        listOf("?summary=", "?summary=bad", "?summary=$summary&summary=$summary", "?summary=$summary&tab=other",
            "?other=$summary", "?summary=%", "?summary=$summary&").forEach { assertNull(it, RecordLinks.parse("$base$path$it", base)) }
    }

    @Test fun malformedRecordPathsAreRejected() {
        listOf("/meeting/records/1-1-1-1-1", "$path/extra", "$path//", "/meeting/records/%31${record.drop(1)}",
            "/meeting/records/../$record", "/meeting/records/").forEach { assertNull(it, RecordLinks.parse("$base$it", base)) }
    }

    @Test fun configuredPrefixAndBoundedInput() {
        assertEquals(RecordLink(record), RecordLinks.parse("$base/prefix$path", "$base/prefix/"))
        assertNull(RecordLinks.parse("$base$path", "$base/prefix"))
        assertNull(RecordLinks.parse("$base$path?summary=" + "a".repeat(5000), base))
    }
}
