package com.appatalks.lcars_translator

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "LcarsTranslation"
private const val UNDETERMINED = "und"

class TranslationManager {

    // Cache translators keyed by "sourceLang|targetLang"
    private val translatorCache = mutableMapOf<String, Translator>()

    // Language detector
    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.65f)
            .build()
    )

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Detects the language of [text].
     * Returns a BCP-47 tag (e.g. "ko", "fr") or "und" if uncertain.
     */
    suspend fun detectLanguage(text: String): String = suspendCancellableCoroutine { cont ->
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { lang ->
                Log.d(TAG, "Detected language: $lang")
                cont.resume(lang)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Language detection failed: ${e.message}")
                cont.resume(UNDETERMINED)
            }
    }

    /**
     * Translates [text] from [sourceLang] to [targetLang].
     *
     * @param sourceLang BCP-47 tag, e.g. "ko". Pass "und" to auto-detect first.
     * @param targetLang BCP-47 tag, e.g. "en".
     * @param onModelDownloading Called when a model needs to be downloaded.
     * @param onModelReady Called when the model is ready.
     * @return Translated text, or the original text on error.
     */
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        onModelDownloading: (() -> Unit)? = null,
        onModelReady: (() -> Unit)? = null
    ): String {
        if (text.isBlank()) return text


        // ── On-device (ML Kit) path ────────────────────────────────────────
        // Resolve source language — detect if needed
        val resolvedSource = if (sourceLang == UNDETERMINED || sourceLang.isBlank()) {
            detectLanguage(text)
        } else {
            sourceLang
        }

        if (resolvedSource == UNDETERMINED) {
            Log.w(TAG, "Could not detect language, skipping translation")
            return text
        }

        if (resolvedSource == targetLang) return text // same language, no-op

        val mlkitSource = resolvedSource.toMlKitLanguage() ?: run {
            Log.w(TAG, "Unsupported source language: $resolvedSource")
            return text
        }
        val mlkitTarget = targetLang.toMlKitLanguage() ?: run {
            Log.w(TAG, "Unsupported target language: $targetLang")
            return text
        }

        val translator = getOrCreateTranslator(mlkitSource, mlkitTarget)

        val ready = ensureModelDownloaded(translator, onModelDownloading, onModelReady)
        if (!ready) return text

        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { translated ->
                    Log.d(TAG, "Translated [$resolvedSource→$targetLang]: $translated")
                    cont.resume(translated)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed: ${e.message}")
                    cont.resume(text)
                }
        }
    }

    /** Warms up a translator (downloads model) for a given language pair ahead of use. */
    suspend fun warmUp(
        sourceLang: String,
        targetLang: String,
        onDownloading: (() -> Unit)? = null,
        onReady: (() -> Unit)? = null
    ) {
        val src = sourceLang.toMlKitLanguage() ?: return
        val tgt = targetLang.toMlKitLanguage() ?: return
        val translator = getOrCreateTranslator(src, tgt)
        ensureModelDownloaded(translator, onDownloading, onReady)
    }

    /** Release all cached translators — call from Activity.onDestroy(). */
    fun release() {
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
        languageIdentifier.close()
    }

    // ── Internals ─────────────────────────────────────────────────────────


    private fun getOrCreateTranslator(source: String, target: String): Translator {
        val key = "$source|$target"
        return translatorCache.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(options)
        }
    }

    private suspend fun ensureModelDownloaded(
        translator: Translator,
        onDownloading: (() -> Unit)?,
        onReady: (() -> Unit)?
    ): Boolean = suspendCancellableCoroutine { cont ->
        val conditions = DownloadConditions.Builder().build() // allow download on any network
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                onReady?.invoke()
                cont.resume(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed: ${e.message}")
                cont.resume(false)
            }
            .addOnCanceledListener {
                cont.resume(false)
            }
        // Notify caller that we're downloading
        onDownloading?.invoke()
    }

    /**
     * Maps a BCP-47 language tag to the ML Kit [TranslateLanguage] constant.
     * Returns null if the language is not supported by ML Kit.
     */
    private fun String.toMlKitLanguage(): String? {
        val tag = this.lowercase().trim()
        return when {
            tag.startsWith("af") -> TranslateLanguage.AFRIKAANS
            tag.startsWith("sq") -> TranslateLanguage.ALBANIAN
            tag.startsWith("ar") -> TranslateLanguage.ARABIC
            tag.startsWith("be") -> TranslateLanguage.BELARUSIAN
            tag.startsWith("bn") -> TranslateLanguage.BENGALI
            tag.startsWith("bg") -> TranslateLanguage.BULGARIAN
            tag.startsWith("ca") -> TranslateLanguage.CATALAN
            tag.startsWith("zh") -> TranslateLanguage.CHINESE
            tag.startsWith("hr") -> TranslateLanguage.CROATIAN
            tag.startsWith("cs") -> TranslateLanguage.CZECH
            tag.startsWith("da") -> TranslateLanguage.DANISH
            tag.startsWith("nl") -> TranslateLanguage.DUTCH
            tag.startsWith("en") -> TranslateLanguage.ENGLISH
            tag.startsWith("eo") -> TranslateLanguage.ESPERANTO
            tag.startsWith("et") -> TranslateLanguage.ESTONIAN
            tag.startsWith("fi") -> TranslateLanguage.FINNISH
            tag.startsWith("fr") -> TranslateLanguage.FRENCH
            tag.startsWith("gl") -> TranslateLanguage.GALICIAN
            tag.startsWith("ka") -> TranslateLanguage.GEORGIAN
            tag.startsWith("de") -> TranslateLanguage.GERMAN
            tag.startsWith("el") -> TranslateLanguage.GREEK
            tag.startsWith("gu") -> TranslateLanguage.GUJARATI
            tag.startsWith("ht") -> TranslateLanguage.HAITIAN_CREOLE
            tag.startsWith("he") || tag.startsWith("iw") -> TranslateLanguage.HEBREW
            tag.startsWith("hi") -> TranslateLanguage.HINDI
            tag.startsWith("hu") -> TranslateLanguage.HUNGARIAN
            tag.startsWith("id") -> TranslateLanguage.INDONESIAN
            tag.startsWith("ga") -> TranslateLanguage.IRISH
            tag.startsWith("it") -> TranslateLanguage.ITALIAN
            tag.startsWith("ja") -> TranslateLanguage.JAPANESE
            tag.startsWith("kn") -> TranslateLanguage.KANNADA
            tag.startsWith("ko") -> TranslateLanguage.KOREAN
            tag.startsWith("lv") -> TranslateLanguage.LATVIAN
            tag.startsWith("lt") -> TranslateLanguage.LITHUANIAN
            tag.startsWith("mk") -> TranslateLanguage.MACEDONIAN
            tag.startsWith("ms") -> TranslateLanguage.MALAY
            tag.startsWith("mt") -> TranslateLanguage.MALTESE
            tag.startsWith("mr") -> TranslateLanguage.MARATHI
            tag.startsWith("no") -> TranslateLanguage.NORWEGIAN
            tag.startsWith("fa") -> TranslateLanguage.PERSIAN
            tag.startsWith("pl") -> TranslateLanguage.POLISH
            tag.startsWith("pt") -> TranslateLanguage.PORTUGUESE
            tag.startsWith("ro") -> TranslateLanguage.ROMANIAN
            tag.startsWith("ru") -> TranslateLanguage.RUSSIAN
            tag.startsWith("sk") -> TranslateLanguage.SLOVAK
            tag.startsWith("sl") -> TranslateLanguage.SLOVENIAN
            tag.startsWith("es") -> TranslateLanguage.SPANISH
            tag.startsWith("sw") -> TranslateLanguage.SWAHILI
            tag.startsWith("sv") -> TranslateLanguage.SWEDISH
            tag.startsWith("tl") -> TranslateLanguage.TAGALOG
            tag.startsWith("ta") -> TranslateLanguage.TAMIL
            tag.startsWith("te") -> TranslateLanguage.TELUGU
            tag.startsWith("th") -> TranslateLanguage.THAI
            tag.startsWith("tr") -> TranslateLanguage.TURKISH
            tag.startsWith("uk") -> TranslateLanguage.UKRAINIAN
            tag.startsWith("ur") -> TranslateLanguage.URDU
            tag.startsWith("vi") -> TranslateLanguage.VIETNAMESE
            tag.startsWith("cy") -> TranslateLanguage.WELSH
            else -> null
        }
    }
}


