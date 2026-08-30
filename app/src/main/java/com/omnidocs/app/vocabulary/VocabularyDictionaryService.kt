package com.omnidocs.app.vocabulary

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service providing Malaysian acronym expansions, organization name normalization,
 * local place recognition, and user custom terminology for speech-to-text and AI processing.
 */
@Singleton
class VocabularyDictionaryService @Inject constructor() {

    private val builtinAcronyms = mapOf(
        // Government & Regulatory Bodies
        "LHDN" to "Lembaga Hasil Dalam Negeri (Inland Revenue Board)",
        "KWSP" to "Kumpulan Wang Simpanan Pekerja (Employees Provident Fund)",
        "EPF" to "Employees Provident Fund (Kumpulan Wang Simpanan Pekerja)",
        "PERKESO" to "Pertubuhan Keselamatan Sosial (SOCSO)",
        "SOCSO" to "Social Security Organisation (Pertubuhan Keselamatan Sosial)",
        "SPRM" to "Suruhanjaya Pencegahan Rasuah Malaysia (MACC)",
        "MACC" to "Malaysian Anti-Corruption Commission (SPRM)",
        "PDRM" to "Polis Diraja Malaysia (Royal Malaysia Police)",
        "BNM" to "Bank Negara Malaysia (Central Bank of Malaysia)",
        "SSM" to "Suruhanjaya Syarikat Malaysia (Companies Commission of Malaysia)",
        "JPJ" to "Jabatan Pengangkutan Jalan (Road Transport Department)",
        "JKR" to "Jabatan Kerja Raya (Public Works Department)",
        "MKN" to "Majlis Keselamatan Negara (National Security Council)",
        "MDEC" to "Malaysia Digital Economy Corporation",
        "SKMM" to "Suruhanjaya Komunikasi dan Multimedia Malaysia (MCMC)",
        "MCMC" to "Malaysian Communications and Multimedia Commission",
        "KKM" to "Kementerian Kesihatan Malaysia (Ministry of Health)",
        "MOH" to "Ministry of Health (Kementerian Kesihatan Malaysia)",
        "KPM" to "Kementerian Pendidikan Malaysia (Ministry of Education)",
        "MOE" to "Ministry of Education (Kementerian Pendidikan Malaysia)",
        "KPT" to "Kementerian Pendidikan Tinggi (Ministry of Higher Education)",
        "MOHE" to "Ministry of Higher Education",
        "MOF" to "Ministry of Finance (Kementerian Kewangan)",
        "MITI" to "Ministry of Investment, Trade and Industry",

        // Utilities & Conglomerates
        "TNB" to "Tenaga Nasional Berhad",
        "TM" to "Telekom Malaysia Berhad",
        "DBKL" to "Dewan Bandaraya Kuala Lumpur",
        "MBPJ" to "Majlis Bandaraya Petaling Jaya",
        "MBSA" to "Majlis Bandaraya Shah Alam",
        "MBIP" to "Majlis Bandaraya Iskandar Puteri",

        // Higher Education Institutions
        "UM" to "Universiti Malaya",
        "UKM" to "Universiti Kebangsaan Malaysia",
        "UPM" to "Universiti Putra Malaysia",
        "USM" to "Universiti Sains Malaysia",
        "UTM" to "Universiti Teknologi Malaysia",
        "UITM" to "Universiti Teknologi MARA",
        "IIUM" to "International Islamic University Malaysia (UIA)",
        "UIA" to "Universiti Islam Antarabangsa Malaysia (IIUM)",
        "UTP" to "Universiti Teknologi PETRONAS",
        "MMU" to "Multimedia University",
        "TARUMT" to "Tunku Abdul Rahman University of Management and Technology"
    )

    private val commonSlangReplacements = mapOf(
        Regex("(?i)\\butk\\b") to "untuk",
        Regex("(?i)\\bdgn\\b") to "dengan",
        Regex("(?i)\\bsgt\\b") to "sangat",
        Regex("(?i)\\btgk\\b") to "tengok",
        Regex("(?i)\\btk\\b") to "tidak",
        Regex("(?i)\\bsbb\\b") to "sebab",
        Regex("(?i)\\bbkn\\b") to "bukan",
        Regex("(?i)\\bmcm\\b") to "macam",
        Regex("(?i)\\bskg\\b") to "sekarang",
        Regex("(?i)\\bklu\\b") to "kalau"
    )

    private val customUserDictionary = ConcurrentHashMap<String, String>()

    /**
     * Look up an acronym expansion.
     */
    fun expandAcronym(acronym: String): String? {
        val upper = acronym.trim().uppercase()
        return customUserDictionary[upper] ?: builtinAcronyms[upper]
    }

    /**
     * Register a custom user terminology or acronym expansion.
     */
    fun registerCustomTerm(term: String, expansion: String) {
        if (term.isNotBlank() && expansion.isNotBlank()) {
            customUserDictionary[term.trim().uppercase()] = expansion.trim()
        }
    }

    /**
     * Remove a custom user term.
     */
    fun removeCustomTerm(term: String) {
        customUserDictionary.remove(term.trim().uppercase())
    }

    /**
     * Returns all known acronyms (built-in + user defined).
     */
    fun getAllKnownAcronyms(): Map<String, String> {
        val result = LinkedHashMap<String, String>(builtinAcronyms)
        result.putAll(customUserDictionary)
        return result
    }

    /**
     * Normalizes transcript text by cleaning BM shorthand and standardizing known acronyms.
     */
    fun normalizeTranscript(text: String, expandAcronymsInPlace: Boolean = false): String {
        if (text.isBlank()) return text

        var result = text

        // 1. Clean common informal shorthand in BM speech
        for ((regex, replacement) in commonSlangReplacements) {
            result = result.replace(regex, replacement)
        }

        // 2. Optionally expand acronyms or standardize their casing
        if (expandAcronymsInPlace) {
            for ((acronym, expansion) in getAllKnownAcronyms()) {
                val regex = Regex("(?i)\\b${Regex.escape(acronym)}\\b")
                result = result.replace(regex, "$acronym ($expansion)")
            }
        } else {
            // Standardize acronym casing (e.g. "lhdn" -> "LHDN", "kwsp" -> "KWSP")
            for (acronym in getAllKnownAcronyms().keys) {
                val regex = Regex("(?i)\\b${Regex.escape(acronym)}\\b")
                result = result.replace(regex, acronym)
            }
        }

        return result.trim()
    }
}
