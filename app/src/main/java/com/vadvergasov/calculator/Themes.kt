package com.vadvergasov.calculator

import com.google.android.material.color.DynamicColors

class Themes {

    companion object {

        // Themes
        private const val DEFAULT_THEME_INDEX = 0
        private const val MATERIAL_YOU_THEME_INDEX = 1

        // used to go from Preference int value to actual theme
        private val themeMap = mapOf(
            DEFAULT_THEME_INDEX to R.style.AppTheme,
            MATERIAL_YOU_THEME_INDEX to R.style.MaterialYouTheme
        )

    }

    fun getTheme(): Int {
        return themeMap[if (DynamicColors.isDynamicColorAvailable()) MATERIAL_YOU_THEME_INDEX else DEFAULT_THEME_INDEX] ?: DEFAULT_THEME_INDEX
    }
}