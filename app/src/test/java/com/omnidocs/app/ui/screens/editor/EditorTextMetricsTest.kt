package com.omnidocs.app.ui.screens.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTextMetricsTest {

    @Test
    fun testWordCount_english() {
        val text = "OmniDocs is a privacy-first note taking application."
        assertEquals(7, EditorViewModel.calculateWordCount(text))
    }

    @Test
    fun testWordCount_emptyOrWhitespace() {
        assertEquals(0, EditorViewModel.calculateWordCount(""))
        assertEquals(0, EditorViewModel.calculateWordCount("   \n\t  "))
    }

    @Test
    fun testWordCount_cjkChineseWithoutSpaces() {
        // 17 Chinese characters with no whitespace
        val text = "这是一个非常重要的系统架构设计文档。"
        // Punctuation "。" is non-CJK symbol; 16 CJK ideographs
        val count = EditorViewModel.calculateWordCount(text)
        assertTrue("Chinese characters should each count as words/units", count >= 16)
    }

    @Test
    fun testWordCount_cjkJapaneseMixed() {
        // Japanese Kanji and Hiragana
        val text = "今日の会議は午後三時からです" // 14 characters
        val count = EditorViewModel.calculateWordCount(text)
        assertEquals(14, count)
    }

    @Test
    fun testWordCount_cjkKoreanHangul() {
        // Korean Hangul with spaces (4 eojeol/words)
        val text = "새로운 프로젝트 계획서 작성"
        val count = EditorViewModel.calculateWordCount(text)
        assertEquals(4, count)
    }

    @Test
    fun testWordCount_mixedCjkAndLatin() {
        // 2 Latin words ("Q3 report") + 4 Chinese characters ("财务预算")
        val text = "Q3 report 财务预算"
        val count = EditorViewModel.calculateWordCount(text)
        assertEquals(6, count) // 2 latin words + 4 Chinese chars
    }

    @Test
    fun testWordCount_arabicText() {
        val text = "مرحبا بكم في تطبيق تدوين الملاحظات"
        assertEquals(6, EditorViewModel.calculateWordCount(text))
    }

    @Test
    fun testReadingTime_empty() {
        assertEquals(0, EditorViewModel.calculateReadingTime(""))
    }

    @Test
    fun testReadingTime_shortTextIsAtLeastOneMinute() {
        assertEquals(1, EditorViewModel.calculateReadingTime("Hello world"))
        assertEquals(1, EditorViewModel.calculateReadingTime("短文"))
    }

    @Test
    fun testReadingTime_longTextScalesProperly() {
        // 600 English words -> ~3 minutes at 200 WPM
        val longEnglish = (1..600).joinToString(" ") { "word" }
        assertEquals(3, EditorViewModel.calculateReadingTime(longEnglish))

        // 700 Chinese characters -> ~2 minutes at 350 CPM
        val longChinese = "字".repeat(700)
        assertEquals(2, EditorViewModel.calculateReadingTime(longChinese))
    }
}
