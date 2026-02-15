package app.secondway.lock

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

data class LanguageOption(val tag: String, val label: String)

object LanguageHelper {

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun getLanguageOptions(context: Context): List<LanguageOption> {
        return listOf(
            LanguageOption("", context.getString(R.string.language_system_default)),
            LanguageOption("en", "English"),
            LanguageOption("tr", "Türkçe"),
            LanguageOption("pt-BR", "Português (Brasil)"),
            LanguageOption("hi", "हिन्दी"),
            LanguageOption("id", "Bahasa Indonesia"),
            LanguageOption("es", "Español"),
            LanguageOption("fil", "Filipino"),
            LanguageOption("ur", "اردو"),
            LanguageOption("ar", "العربية"),
            LanguageOption("de", "Deutsch")
        )
    }

    fun getSavedLanguageTag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE_TAG, "") ?: ""
    }

    fun setAppLanguage(context: Context, tag: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE_TAG, tag).apply()
        applyLanguageTag(tag)
    }

    fun applySavedLocale(context: Context) {
        val tag = getSavedLanguageTag(context)
        applyLanguageTag(tag)
    }

    fun getCurrentLanguageLabel(context: Context): String {
        val tag = getSavedLanguageTag(context)
        val option = getLanguageOptions(context).firstOrNull { it.tag == tag }
        return option?.label ?: context.getString(R.string.language_system_default)
    }

    private fun applyLanguageTag(tag: String) {
        if (tag.isBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
