package com.taskrx.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

private fun parse(text: String) = Json.parseToJsonElement(text)

class NoticeTest {
    @Test
    fun `wire words round-trip and an unknown one is null`() {
        for (kind in SourceKind.entries) assertEquals(kind, SourceKind.fromWire(kind.wire))
        for (resolution in NoticeResolution.entries) assertEquals(resolution, NoticeResolution.fromWire(resolution.wire))
        assertNull(SourceKind.fromWire("sms"))
        assertNull(NoticeResolution.fromWire("maybe"))
    }

    @Test
    fun `an email is case-insensitive and must look like an address`() {
        assertEquals("waste@city.example", normalizeSourceValue(SourceKind.Email, "  Waste@City.Example  "))
        assertNull(normalizeSourceValue(SourceKind.Email, "not an address"))
        assertNull(normalizeSourceValue(SourceKind.Email, "   "))
    }

    @Test
    fun `an app package and a share keep their spelling, minus surrounding space`() {
        assertEquals("com.example.app", normalizeSourceValue(SourceKind.App, " com.example.app "))
        assertEquals("some text", normalizeSourceValue(SourceKind.Share, "  some text "))
        assertNull(normalizeSourceValue(SourceKind.App, "   "))
    }

    @Test
    fun `terms are split on commas, trimmed, and blanks dropped`() {
        assertEquals(listOf("collection", "bins"), parseNoticeTerms(" collection , , bins "))
        assertEquals(emptyList(), parseNoticeTerms("  ,  "))
    }

    @Test
    fun `rules round-trip through JSON`() {
        val rules =
            listOf(
                NoticeRule(SourceKind.Email, "waste@city.example", listOf("collection", "bins")),
                NoticeRule(SourceKind.App, "com.example.app"),
            )

        assertEquals(rules, noticeRulesFromJson(noticeRulesToJson(rules)))
    }

    @Test
    fun `a value that is not an array of rules reads as no rules`() {
        assertEquals(emptyList(), noticeRulesFromJson(null))
        assertEquals(emptyList(), noticeRulesFromJson(JsonPrimitive("x")))
        assertEquals(emptyList(), noticeRulesFromJson(parse("""[1]""")))
        assertEquals(emptyList(), noticeRulesFromJson(parse("""[{"sourceKind":"sms","sourceValue":"x"}]""")))
        assertEquals(emptyList(), noticeRulesFromJson(parse("""[{"sourceValue":"x"}]""")))
        assertEquals(emptyList(), noticeRulesFromJson(parse("""[{"sourceKind":"email","sourceValue":"not an address"}]""")))
        assertEquals(emptyList(), noticeRulesFromJson(parse("""[{"sourceKind":"email","sourceValue":"a@b","terms":"collection"}]""")))
        assertEquals(emptyList(), noticeRulesFromJson(parse("""[{"sourceKind":"email","sourceValue":"a@b","terms":[1]}]""")))
    }

    @Test
    fun `a rule with no terms means any notice from that source`() {
        assertEquals(listOf(NoticeRule(SourceKind.Email, "a@b", emptyList())), noticeRulesFromJson(parse("""[{"sourceKind":"email","sourceValue":"A@B"}]""")))
    }
}
