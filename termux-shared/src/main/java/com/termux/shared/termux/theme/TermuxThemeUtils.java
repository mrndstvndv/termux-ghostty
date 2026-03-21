package com.termux.shared.termux.theme;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.theme.NightMode;

public class TermuxThemeUtils {

    /** Get the current theme config values from the properties file on disk and set app wide night mode. */
    public static void setAppNightMode(@NonNull Context context) {
        NightMode.setAppNightMode(getAppNightMode(
            TermuxSharedProperties.getNightMode(context),
            TermuxSharedProperties.getMaterialYouTheme(context)));
    }

    /** Set name as app wide night mode value. */
    public static void setAppNightMode(@Nullable String name) {
        NightMode.setAppNightMode(name);
    }

    public static boolean isMaterialYouThemeEnabled(@Nullable String materialYouTheme) {
        if (materialYouTheme == null) return false;
        return !TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DISABLED.equals(materialYouTheme);
    }

    @NonNull
    public static String getAppNightMode(@Nullable String nightMode, @Nullable String materialYouTheme) {
        if (!isMaterialYouThemeEnabled(materialYouTheme))
            return NightMode.modeOf(nightMode, NightMode.SYSTEM).getName();

        if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_LIGHT.equals(materialYouTheme))
            return NightMode.FALSE.getName();
        if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DARK.equals(materialYouTheme))
            return NightMode.TRUE.getName();
        if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_BLACK.equals(materialYouTheme))
            return NightMode.TRUE.getName();
        if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_SYSTEM.equals(materialYouTheme))
            return NightMode.SYSTEM.getName();

        return NightMode.modeOf(nightMode, NightMode.SYSTEM).getName();
    }

}
