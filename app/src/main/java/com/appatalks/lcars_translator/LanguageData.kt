package com.appatalks.lcars_translator

/** A supported language entry for the LCARS Universal Translator. */
data class LanguageEntry(
    val displayName: String,
    val bcp47Tag: String       // BCP-47 tag used by ML Kit and SpeechRecognizer
)

object LanguageData {

    const val AUTO_DETECT = "und"

    /** Languages available as source (includes Auto-Detect). */
    val sourceLanguages: List<LanguageEntry> = listOf(
        LanguageEntry("Auto-Detect", AUTO_DETECT),
        LanguageEntry("Afrikaans", "af"),
        LanguageEntry("Arabic", "ar"),
        LanguageEntry("Bengali", "bn"),
        LanguageEntry("Bulgarian", "bg"),
        LanguageEntry("Catalan", "ca"),
        LanguageEntry("Chinese (Simplified)", "zh"),
        LanguageEntry("Croatian", "hr"),
        LanguageEntry("Czech", "cs"),
        LanguageEntry("Danish", "da"),
        LanguageEntry("Dutch", "nl"),
        LanguageEntry("English", "en"),
        LanguageEntry("Finnish", "fi"),
        LanguageEntry("French", "fr"),
        LanguageEntry("German", "de"),
        LanguageEntry("Greek", "el"),
        LanguageEntry("Gujarati", "gu"),
        LanguageEntry("Hebrew", "he"),
        LanguageEntry("Hindi", "hi"),
        LanguageEntry("Hungarian", "hu"),
        LanguageEntry("Indonesian", "id"),
        LanguageEntry("Italian", "it"),
        LanguageEntry("Japanese", "ja"),
        LanguageEntry("Kannada", "kn"),
        LanguageEntry("Korean", "ko"),
        LanguageEntry("Latvian", "lv"),
        LanguageEntry("Lithuanian", "lt"),
        LanguageEntry("Malay", "ms"),
        LanguageEntry("Marathi", "mr"),
        LanguageEntry("Norwegian", "no"),
        LanguageEntry("Persian", "fa"),
        LanguageEntry("Polish", "pl"),
        LanguageEntry("Portuguese", "pt"),
        LanguageEntry("Romanian", "ro"),
        LanguageEntry("Russian", "ru"),
        LanguageEntry("Slovak", "sk"),
        LanguageEntry("Spanish", "es"),
        LanguageEntry("Swahili", "sw"),
        LanguageEntry("Swedish", "sv"),
        LanguageEntry("Tagalog", "tl"),
        LanguageEntry("Tamil", "ta"),
        LanguageEntry("Telugu", "te"),
        LanguageEntry("Thai", "th"),
        LanguageEntry("Turkish", "tr"),
        LanguageEntry("Ukrainian", "uk"),
        LanguageEntry("Urdu", "ur"),
        LanguageEntry("Vietnamese", "vi"),
        LanguageEntry("Welsh", "cy"),
    )

    /** Languages available as translation target (no Auto-Detect). */
    val targetLanguages: List<LanguageEntry> = sourceLanguages.filter { it.bcp47Tag != AUTO_DETECT }

    fun findByTag(tag: String): LanguageEntry? =
        sourceLanguages.find { it.bcp47Tag == tag }
}

