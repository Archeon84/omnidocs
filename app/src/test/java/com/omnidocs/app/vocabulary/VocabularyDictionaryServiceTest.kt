package com.omnidocs.app.vocabulary

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VocabularyDictionaryServiceTest {

    private lateinit var vocabService: VocabularyDictionaryService

    @Before
    fun setUp() {
        vocabService = VocabularyDictionaryService()
    }

    @Test
    fun testExpandAcronym_findsBuiltinMalaysianEntities() {
        val lhdn = vocabService.expandAcronym("LHDN")
        assertNotNull(lhdn)
        assertTrue(lhdn!!.contains("Lembaga Hasil Dalam Negeri"))

        val kwsp = vocabService.expandAcronym("kwsp") // Case-insensitive
        assertNotNull(kwsp)
        assertTrue(kwsp!!.contains("Kumpulan Wang Simpanan Pekerja"))

        val tnb = vocabService.expandAcronym("TNB")
        assertEquals("Tenaga Nasional Berhad", tnb)

        val uitm = vocabService.expandAcronym("UiTM")
        assertEquals("Universiti Teknologi MARA", uitm)
    }

    @Test
    fun testRegisterCustomTerm_overridesAndExpands() {
        vocabService.registerCustomTerm("AI-LAB", "Advanced Agentic Intelligence Laboratory")

        val custom = vocabService.expandAcronym("ai-lab")
        assertNotNull(custom)
        assertEquals("Advanced Agentic Intelligence Laboratory", custom)

        vocabService.removeCustomTerm("AI-LAB")
        assertNull(vocabService.expandAcronym("AI-LAB"))
    }

    @Test
    fun testNormalizeTranscript_cleansBmShorthandAndStandardizesCasing() {
        val raw = "Semalam saya panggil lhdn dan kwsp utk semak borang cukai dgn borang caruman."
        val normalized = vocabService.normalizeTranscript(raw, expandAcronymsInPlace = false)

        // Verifies shorthand 'utk' -> 'untuk', 'dgn' -> 'dengan'
        assertTrue(normalized.contains("untuk semak borang cukai"))
        assertTrue(normalized.contains("dengan borang caruman"))

        // Verifies acronym uppercase standardization
        assertTrue(normalized.contains("LHDN"))
        assertTrue(normalized.contains("KWSP"))
    }

    @Test
    fun testNormalizeTranscript_expandsAcronymsWhenRequested() {
        val raw = "Sila hubungi LHDN berkenaan taksiran."
        val expanded = vocabService.normalizeTranscript(raw, expandAcronymsInPlace = true)

        assertTrue(expanded.contains("LHDN (Lembaga Hasil Dalam Negeri"))
    }
}
