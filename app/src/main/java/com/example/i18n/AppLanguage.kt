package com.example.i18n

import android.content.Context
import androidx.annotation.StringRes
import com.example.R

/** Supported app languages. The selected value is persisted in app-private preferences. */
enum class AppLanguage(val code: String, @StringRes val displayNameRes: Int) {
    ARABIC("ar", R.string.language_arabic),
    ENGLISH("en", R.string.language_english),
    FRENCH("fr", R.string.language_french);

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ARABIC
    }
}

class LanguageStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): AppLanguage = AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE, AppLanguage.ARABIC.code))

    fun set(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    private companion object {
        const val PREFS_NAME = "phone_fortress_language"
        const val KEY_LANGUAGE = "selected_language"
    }
}

fun AppLanguage.isRtl(): Boolean = this == AppLanguage.ARABIC
