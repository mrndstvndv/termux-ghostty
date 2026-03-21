package com.termux.shared.termux.theme;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.NonNull;

import com.google.android.material.color.utilities.Hct;
import com.termux.shared.theme.ThemeUtils;

import java.util.Locale;
import java.util.Properties;

/** Generates terminal colors from the active Material 3 theme palette. */
public final class MaterialYouTerminalColors {

    private static final int DEFAULT_LIGHT_SURFACE = 0xFFFFFBFE;
    private static final int DEFAULT_DARK_SURFACE = 0xFF1C1B1F;
    private static final int DEFAULT_LIGHT_ON_SURFACE = 0xFF1C1B1F;
    private static final int DEFAULT_DARK_ON_SURFACE = 0xFFE6E1E5;
    private static final int DEFAULT_LIGHT_ERROR = 0xFFB3261E;
    private static final int DEFAULT_DARK_ERROR = 0xFFF2B8B5;
    private static final int DEFAULT_LIGHT_PRIMARY = 0xFF6750A4;
    private static final int DEFAULT_DARK_PRIMARY = 0xFFD0BCFF;
    private static final int DEFAULT_LIGHT_SECONDARY = 0xFF625B71;
    private static final int DEFAULT_DARK_SECONDARY = 0xFFCCC2DC;
    private static final int DEFAULT_LIGHT_TERTIARY = 0xFF7D5260;
    private static final int DEFAULT_DARK_TERTIARY = 0xFFEFB8C8;

    private MaterialYouTerminalColors() {}

    @NonNull
    public static Properties generate(@NonNull Context context) {
        boolean isDarkTheme = ThemeUtils.isNightModeEnabled(context);

        int surface = resolveColor(context, com.google.android.material.R.attr.colorSurface,
            isDarkTheme ? DEFAULT_DARK_SURFACE : DEFAULT_LIGHT_SURFACE);
        int onSurface = resolveColor(context, com.google.android.material.R.attr.colorOnSurface,
            isDarkTheme ? DEFAULT_DARK_ON_SURFACE : DEFAULT_LIGHT_ON_SURFACE);
        int onSurfaceVariant = resolveColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant,
            shiftTone(onSurface, isDarkTheme ? 80 : 35));
        int outline = resolveColor(context, com.google.android.material.R.attr.colorOutline,
            shiftTone(onSurface, isDarkTheme ? 60 : 55));
        int surfaceContainerHighest = resolveColor(context, com.google.android.material.R.attr.colorSurfaceContainerHighest,
            shiftTone(surface, isDarkTheme ? 24 : 88));

        int error = resolveColor(context, com.google.android.material.R.attr.colorError,
            isDarkTheme ? DEFAULT_DARK_ERROR : DEFAULT_LIGHT_ERROR);
        int errorContainer = resolveColor(context, com.google.android.material.R.attr.colorErrorContainer,
            shiftTone(error, isDarkTheme ? 30 : 90));

        int primary = resolveColor(context, com.google.android.material.R.attr.colorPrimary,
            isDarkTheme ? DEFAULT_DARK_PRIMARY : DEFAULT_LIGHT_PRIMARY);
        int primaryContainer = resolveColor(context, com.google.android.material.R.attr.colorPrimaryContainer,
            shiftTone(primary, isDarkTheme ? 30 : 90));

        int secondary = resolveColor(context, com.google.android.material.R.attr.colorSecondary,
            isDarkTheme ? DEFAULT_DARK_SECONDARY : DEFAULT_LIGHT_SECONDARY);
        int secondaryContainer = resolveColor(context, com.google.android.material.R.attr.colorSecondaryContainer,
            shiftTone(secondary, isDarkTheme ? 30 : 90));

        int tertiary = resolveColor(context, com.google.android.material.R.attr.colorTertiary,
            isDarkTheme ? DEFAULT_DARK_TERTIARY : DEFAULT_LIGHT_TERTIARY);
        int tertiaryContainer = resolveColor(context, com.google.android.material.R.attr.colorTertiaryContainer,
            shiftTone(tertiary, isDarkTheme ? 30 : 90));

        Properties properties = new Properties();
        putColor(properties, "foreground", onSurface);
        putColor(properties, "background", surface);
        putColor(properties, "cursor", primary);
        putColor(properties, "color0", surfaceContainerHighest);
        putColor(properties, "color1", error);
        putColor(properties, "color2", tertiary);
        putColor(properties, "color3", primaryContainer);
        putColor(properties, "color4", primary);
        putColor(properties, "color5", secondary);
        putColor(properties, "color6", tertiaryContainer);
        putColor(properties, "color7", onSurfaceVariant);
        putColor(properties, "color8", outline);
        putColor(properties, "color9", errorContainer);
        putColor(properties, "color10", shiftTone(tertiary, isDarkTheme ? 88 : 28));
        putColor(properties, "color11", shiftTone(primary, isDarkTheme ? 88 : 28));
        putColor(properties, "color12", primaryContainer);
        putColor(properties, "color13", secondaryContainer);
        putColor(properties, "color14", shiftTone(tertiaryContainer, isDarkTheme ? 92 : 24));
        putColor(properties, "color15", onSurface);
        return properties;
    }

    private static int resolveColor(@NonNull Context context, int attr, int fallback) {
        return ThemeUtils.getSystemAttrColor(context, attr, fallback);
    }

    private static int shiftTone(int color, double tone) {
        Hct hct = Hct.fromInt(color);
        hct.setTone(tone);
        return hct.toInt();
    }

    private static void putColor(@NonNull Properties properties, @NonNull String key, int color) {
        properties.setProperty(key, toTerminalColor(color));
    }

    @NonNull
    private static String toTerminalColor(int color) {
        return String.format(Locale.US, "#%02x%02x%02x", Color.red(color), Color.green(color), Color.blue(color));
    }

}
