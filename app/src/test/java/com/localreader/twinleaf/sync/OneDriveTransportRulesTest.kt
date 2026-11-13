package com.localreader.twinleaf.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OneDriveTransportRulesTest {
    @Test fun jsonBudgetBoundsCumulativeUtf8Bytes() {
        val budget=CloudJsonBudget(maxBytes=10)
        budget.consume("\"中\"") // Five UTF-8 bytes, despite only three UTF-16 characters.
        budget.consume("\"中\"")
        assertThrows(IllegalArgumentException::class.java) { budget.consume("0") }
        assertThrows(IllegalArgumentException::class.java) { CloudJsonBudget(maxBytes=4).consume("\"中\"") }
    }

    @Test fun jsonBudgetRejectsDeepInputBeforeParsingButIgnoresQuotedBrackets() {
        val budget=CloudJsonBudget(maxBytes=200,maxDepth=2)
        budget.consume("{\"text\":\"[[[\\\"{{{\",\"data\":[]}")
        assertThrows(IllegalArgumentException::class.java) { budget.consume("[[[]]]") }
        assertThrows(IllegalArgumentException::class.java) { CloudJsonBudget().consume("{\"text\":\"unfinished") }
        assertThrows(IllegalArgumentException::class.java) { CloudJsonBudget().consume("/*\"*/[[[]]]/*\"*/") }
    }

    @Test fun headRelationUsesAncestryInsteadOfDeviceClock() {
        val ancestor=CloudHead("a".repeat(64),"data-${"b".repeat(64)}.json",999999L)
        val descendant=CloudHead("c".repeat(64),"data-${"d".repeat(64)}.json",1L,listOf(ancestor.hash))
        assertEquals(HeadRelation.REMOTE_NEWER,headRelation(ancestor,descendant))
        assertEquals(HeadRelation.LOCAL_NEWER,headRelation(descendant,ancestor))
        assertEquals(HeadRelation.CONFLICT,headRelation(ancestor,descendant.copy(ancestors=emptyList())))
    }

    @Test fun generatedNamesRejectPathsAndUnsupportedExtensions() {
        val sha="a".repeat(64)
        listOf("source-$sha.pdf","source-$sha.epub","data-$sha.json","v2-manifest-device-1.json","snapshot-device-1.json").forEach(OneDriveTransportRules::requireName)
        listOf("../data-$sha.json","source-$sha.exe","source-short.pdf","v2-manifest-a/b.json","data-$sha.json?x=1").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { OneDriveTransportRules.requireName(name) }
        }
    }

    @Test fun bearerUrlsCannotEscapeGraph() {
        OneDriveTransportRules.requireGraphUrl("https://graph.microsoft.com/v1.0/me/drive?skiptoken=abc")
        listOf("http://graph.microsoft.com/v1.0/me","https://graph.microsoft.com.evil.test/v1.0/me","https://user@graph.microsoft.com/v1.0/me","https://graph.microsoft.com:444/v1.0/me").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { OneDriveTransportRules.requireGraphUrl(url) }
        }
    }

    @Test fun retryAfterSupportsSecondsAndHttpDate() {
        assertEquals(12_000L,OneDriveTransportRules.retryAfterMillis("12"))
        assertEquals(10_000L,OneDriveTransportRules.retryAfterMillis("Thu, 01 Jan 1970 00:00:10 GMT",0))
        assertEquals(null,OneDriveTransportRules.retryAfterMillis("invalid"))
    }

    @Test fun resumeRangeMustBeWithinFile() {
        assertEquals(5_242_880L,OneDriveTransportRules.rangeStart("5242880-",8_000_000))
        assertEquals(0L,OneDriveTransportRules.rangeStart("0-5242879",8_000_000))
        listOf("-1-","8-","nonsense","999999999999999999999-").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { OneDriveTransportRules.rangeStart(value,8) }
        }
    }
}
