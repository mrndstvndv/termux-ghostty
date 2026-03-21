# Material You Theming Plan

## Overview

Add Material You (Material 3 DynamicColors) theming to the Termux fork, covering both the app UI chrome (drawer, buttons, dialogs) and terminal content colors (foreground, background, ANSI palette). Gated behind a `material-you-theme` property in `termux.properties` so it doesn't break existing `colors.properties` workflows.

## Architecture

### Current System

Two independent theming layers:

| Layer | Mechanism | Config |
|-------|-----------|--------|
| **App chrome** | `Theme.MaterialComponents.*` XML themes (`values/themes.xml` + `values-night/themes.xml`) | `night-mode` in `termux.properties` |
| **Terminal colors** | `colors.properties` → `TerminalColorScheme.updateWith()` | `~/.termux/colors.properties` file |

### Target System

Add Material You as a third option that supersedes both layers when enabled:

```
termux.properties
  └─ material-you-theme=disabled|light|dark|black|system
       │
       ├─ disabled → current behavior (MDC2 themes + colors.properties)
       │
       └─ light|dark|black|system → Material 3 themes
            │
            ├─ App chrome: M3 theme + DynamicColors.applyToActivityIfAvailable()
            │
            └─ Terminal: generate ANSI palette from M3 tonal palette
                 (colors.properties ignored when active)
```

## Config Property

Add to `termux.properties`:

```properties
# Material You theme variant.
# "disabled"  - Default. Use Material Components themes + colors.properties.
# "light"     - Material You light theme, wallpaper-derived colors.
# "dark"      - Material You dark theme, wallpaper-derived colors.
# "black"     - Material You dark theme with pure black backgrounds (OLED).
# "system"    - Follow system dark/light mode, Material You colors.
#
# Requires Android 12+ for wallpaper-based colors.
# Falls back to static M3 colors on older versions.
# When enabled, colors.properties is ignored for terminal colors.
material-you-theme=disabled
```

## Implementation

### 1. Config plumbing

**`TermuxPropertyConstants.java`**
- Add `KEY_MATERIAL_YOU_THEME = "material-you-theme"`
- Add values: `IVALUE_MATERIAL_YOU_THEME_DISABLED`, `_LIGHT`, `_DARK`, `_BLACK`, `_SYSTEM`
- Add `DEFAULT_IVALUE_MATERIAL_YOU_THEME = IVALUE_MATERIAL_YOU_THEME_DISABLED`
- Add `MAP_MATERIAL_YOU_THEME` ImmutableBiMap
- Add to `TERMUX_APP_PROPERTIES_LIST`

**`TermuxSharedProperties.java`**
- Add `case TermuxPropertyConstants.KEY_MATERIAL_YOU_THEME:` in `getInternalTermuxPropertyValueFromValue()`
- Add `getMaterialYouThemeInternalPropertyValueFromValue()` method
- Add `getMaterialYouTheme()` accessor method

### 2. M3 theme resources

**`termux-shared/src/main/res/values/themes.xml`** (add)
```xml
<!-- M3 parent themes (day) -->
<style name="Theme.BaseActivity.M3.Light.DarkActionBar"
       parent="Theme.Material3.Light.DarkActionBar">
    <!-- Same secondary/status bar attrs as current Light theme -->
</style>

<style name="Theme.BaseActivity.M3.Light.NoActionBar"
       parent="Theme.BaseActivity.M3.Light.DarkActionBar">
    <item name="windowActionBar">false</item>
    <item name="windowNoTitle">true</item>
</style>

<!-- M3 DayNight (follows system) -->
<style name="Theme.BaseActivity.M3.DayNight.DarkActionBar"
       parent="Theme.Material3.DayNight.DarkActionBar"/>
<style name="Theme.BaseActivity.M3.DayNight.NoActionBar"
       parent="Theme.BaseActivity.M3.DayNight.DarkActionBar">
    <item name="windowActionBar">false</item>
    <item name="windowNoTitle">true</item>
</style>
```

**`termux-shared/src/main/res/values-night/themes.xml`** (add)
```xml
<!-- M3 Dark parent themes (night) -->
<style name="Theme.BaseActivity.M3.DayNight.DarkActionBar"
       parent="Theme.Material3.Dark.DarkActionBar"/>
<style name="Theme.BaseActivity.M3.DayNight.NoActionBar"
       parent="Theme.BaseActivity.M3.DayNight.DarkActionBar">
    <item name="windowActionBar">false</item>
    <item name="windowNoTitle">true</item>
</style>
```

**`app/src/main/res/values/themes.xml`** (add)
```xml
<!-- M3 TermuxActivity variants -->
<style name="Theme.TermuxActivity.M3.Light.NoActionBar"
       parent="Theme.BaseActivity.M3.Light.NoActionBar">
    <!-- Drawer, extra keys attrs - same structure as current but using M3 color attrs -->
    <item name="android:windowBackground">?attr/colorSurface</item>
    <item name="android:windowActionModeOverlay">true</item>
    <item name="android:windowTranslucentStatus">true</item>
    <item name="android:windowTranslucentNavigation">true</item>
    <item name="android:windowAllowReturnTransitionOverlap">true</item>
    <item name="android:windowAllowEnterTransitionOverlap">true</item>
    <item name="termuxActivityDrawerBackground">?attr/colorSurfaceContainerLow</item>
    <item name="termuxActivityDrawerImageTint">?attr/colorOnSurface</item>
    <item name="extraKeysButtonTextColor">?attr/colorOnSurface</item>
    <item name="extraKeysButtonActiveTextColor">?attr/colorError</item>
    <item name="extraKeysButtonBackgroundColor">?attr/colorSurfaceContainerHigh</item>
    <item name="extraKeysButtonActiveBackgroundColor">?attr/colorSurfaceContainerHighest</item>
</style>

<style name="Theme.TermuxActivity.M3.DayNight.NoActionBar"
       parent="Theme.BaseActivity.M3.DayNight.NoActionBar">
    <!-- Same attrs as Light variant -->
</style>

<!-- Black (OLED) variant - dark with pure black surfaces -->
<style name="Theme.TermuxActivity.M3.Black.NoActionBar"
       parent="Theme.BaseActivity.M3.DayNight.NoActionBar">
    <item name="android:windowBackground">#000000</item>
    <item name="colorSurface">#000000</item>
    <item name="colorSurfaceContainer">#0D0D0D</item>
    <item name="colorSurfaceContainerLow">#080808</item>
    <item name="colorSurfaceContainerHigh">#141414</item>
    <item name="colorSurfaceContainerHighest">#1A1A1A</item>
    <item name="termuxActivityDrawerBackground">#000000</item>
    <item name="termuxActivityDrawerImageTint">@color/white</item>
    <item name="extraKeysButtonTextColor">@color/white</item>
    <item name="extraKeysButtonActiveTextColor">@color/red_400</item>
    <item name="extraKeysButtonBackgroundColor">#1A1A1A</item>
    <item name="extraKeysButtonActiveBackgroundColor">#333333</item>
</style>
```

**`app/src/main/res/values-night/themes.xml`** (add)
```xml
<!-- M3 dark variants for night qualifier -->
<style name="Theme.TermuxActivity.M3.DayNight.NoActionBar"
       parent="Theme.BaseActivity.M3.DayNight.NoActionBar">
    <item name="android:windowBackground">?attr/colorSurface</item>
    <item name="android:windowActionModeOverlay">true</item>
    <item name="android:windowTranslucentStatus">true</item>
    <item name="android:windowTranslucentNavigation">true</item>
    <item name="android:windowAllowReturnTransitionOverlap">true</item>
    <item name="android:windowAllowEnterTransitionOverlap">true</item>
    <item name="termuxActivityDrawerBackground">?attr/colorSurfaceContainerLow</item>
    <item name="termuxActivityDrawerImageTint">?attr/colorOnSurface</item>
    <item name="extraKeysButtonTextColor">?attr/colorOnSurface</item>
    <item name="extraKeysButtonActiveTextColor">?attr/colorError</item>
    <item name="extraKeysButtonBackgroundColor">?attr/colorSurfaceContainerHigh</item>
    <item name="extraKeysButtonActiveBackgroundColor">?attr/colorSurfaceContainerHighest</item>
</style>
```

**`app/src/main/res/values/styles.xml`** (add)
```xml
<!-- M3 button bar styles for drawer -->
<style name="TermuxActivity.Drawer.ButtonBarStyle.M3"
       parent="@style/Widget.Material3.Button.TextButton"/>
```

### 3. Theme selection logic

**`TermuxActivity.java` — `setActivityTheme()`**

Replace current logic with:

```java
private void setActivityTheme() {
    String materialYouTheme = mProperties.getMaterialYouTheme();
    String nightMode = mProperties.getNightMode();

    if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DISABLED.equals(materialYouTheme)) {
        // Current behavior
        TermuxThemeUtils.setAppNightMode(nightMode);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    } else {
        // Material You active
        switch (materialYouTheme) {
            case "light":
                AppCompatActivityUtils.setNightMode(this, NightMode.FALSE.getName(), true);
                break;
            case "dark":
            case "black":
                AppCompatActivityUtils.setNightMode(this, NightMode.TRUE.getName(), true);
                break;
            case "system":
                TermuxThemeUtils.setAppNightMode(nightMode);
                AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
                break;
        }
    }
}
```

Override `getTheme()` or set theme resource in `onCreate()` before `super.onCreate()`:

```java
private void applyMaterialYouThemeOverlay() {
    String materialYouTheme = mProperties.getMaterialYouTheme();
    if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DISABLED.equals(materialYouTheme))
        return;

    switch (materialYouTheme) {
        case "light":
            setTheme(R.style.Theme_TermuxActivity_M3_Light_NoActionBar);
            break;
        case "black":
            setTheme(R.style.Theme_TermuxActivity_M3_Black_NoActionBar);
            break;
        case "dark":
        case "system":
            setTheme(R.style.Theme_TermuxActivity_M3_DayNight_NoActionBar);
            break;
    }

    // Apply wallpaper-derived colors (no-op on < API 31, uses static M3)
    DynamicColors.applyToActivityIfAvailable(this);
}
```

Call `applyMaterialYouThemeOverlay()` in `onCreate()` before `super.onCreate()`.

### 4. Terminal color generation from M3 palette

**New class: `MaterialYouTerminalColors.java`**

Location: `termux-shared/src/main/java/com/termux/shared/termux/theme/MaterialYouTerminalColors.java`

```java
package com.termux.shared.termux.theme;

import android.content.Context;
import android.content.res.Configuration;

import com.google.android.material.color.DynamicColors;

import java.util.Properties;

/**
 * Generates terminal ANSI color properties from the Material 3 tonal palette.
 *
 * Mapping strategy:
 * - Uses M3 color roles (ColorRoles from DynamicColors)
 * - Maps ANSI 0-15 (the standard colors) to M3 tonal palette stops
 * - Sets foreground/background/cursor from M3 surface/onSurface colors
 *
 * On API < 31, DynamicColors returns the default static M3 palette
 * (no wallpaper extraction), so this still works everywhere.
 */
public class MaterialYouTerminalColors {

    /**
     * Generate a Properties object suitable for TerminalColorScheme.updateWith()
     * from the current Material You palette.
     *
     * @param context Application context
     * @return Properties with foreground, background, cursor, color0-color15
     */
    public static Properties generate(Context context) {
        // Implementation details below
    }
}
```

**Color mapping** (ANSI standard 16 colors from M3 tonal palette):

| ANSI | Role | M3 Source | Notes |
|------|------|-----------|-------|
| 0 | black | `colorSurfaceContainerHighest` | Darkest surface |
| 1 | red | `colorError` | |
| 2 | green | `colorTertiary` | Greenest M3 role |
| 3 | yellow | `colorPrimaryContainer` | Warm accent |
| 4 | blue | `colorPrimary` | Primary = blue-ish |
| 5 | magenta | `colorSecondary` | |
| 6 | cyan | `colorTertiaryContainer` | |
| 7 | white | `colorOnSurfaceVariant` | Muted text |
| 8 | bright black | `colorOutline` | |
| 9 | bright red | `colorErrorContainer` | |
| 10 | bright green | `colorTertiary` (lighter stop) | |
| 11 | bright yellow | `colorPrimary` (lighter stop) | |
| 12 | bright blue | `colorPrimaryContainer` | |
| 13 | bright magenta | `colorSecondaryContainer` | |
| 14 | bright cyan | `colorTertiary` (lighter stop) | |
| 15 | bright white | `colorOnSurface` | Primary text |
| fg | foreground | `colorOnSurface` | |
| bg | background | `colorSurface` | |
| cursor | cursor | `colorPrimary` | |

The exact tonal palette stops can be extracted via `ColorRoles` from the `DynamicColors` API, or by resolving theme attributes at runtime using `ThemeUtils.getSystemAttrColor()`.

### 5. Integration point

**`TermuxTerminalSessionActivityClient.java` — `checkForFontAndColors()`**

```java
public void checkForFontAndColors() {
    try {
        String materialYouTheme = mActivity.getProperties().getMaterialYouTheme();
        boolean isMaterialYou = !TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DISABLED.equals(materialYouTheme);

        final Properties props = new Properties();

        if (isMaterialYou) {
            // Generate colors from Material You palette
            Properties m3Props = MaterialYouTerminalColors.generate(mActivity);
            props.putAll(m3Props);
        } else {
            // Current behavior: load colors.properties
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }
        }

        TerminalColors.COLOR_SCHEME.updateWith(props);

        TerminalSession session = mActivity.getCurrentSession();
        if (session != null) {
            session.reloadColorScheme();
        }

        // Font loading unchanged
        final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0)
            ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
        mActivity.getTerminalView().setTypeface(newTypeface);
    } catch (Exception e) {
        Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e);
    }
}
```

**`BubbleTerminalSessionClient.java`** — same pattern.

### 6. Reload on config change

`reloadActivityStyling()` in `TermuxActivity.java` already calls `recreate()` when needed. The property reload path (`reloadProperties()` → `loadTermuxPropertiesFromDisk()`) will pick up changes. User edits `termux.properties` → broadcast `ACTION_RELOAD_STYLE` → activity recreates with new theme.

## Files Modified

| File | Type |
|------|------|
| `termux-shared/.../TermuxPropertyConstants.java` | Add constants |
| `termux-shared/.../TermuxSharedProperties.java` | Add parser case + accessor |
| `termux-shared/.../theme/MaterialYouTerminalColors.java` | **New file** |
| `termux-shared/src/main/res/values/themes.xml` | Add M3 parent themes |
| `termux-shared/src/main/res/values-night/themes.xml` | Add M3 dark parent themes |
| `app/src/main/res/values/themes.xml` | Add M3 activity themes |
| `app/src/main/res/values-night/themes.xml` | Add M3 dark activity theme |
| `app/src/main/res/values/styles.xml` | Add M3 button bar style |
| `app/.../TermuxActivity.java` | Theme selection + DynamicColors |
| `app/.../TermuxTerminalSessionActivityClient.java` | M3 terminal color path |
| `app/.../BubbleTerminalSessionClient.java` | M3 terminal color path |

## Edge Cases

- **API < 31**: `DynamicColors.applyToActivityIfAvailable()` is a no-op (uses static M3 palette). Works fine, just no wallpaper extraction.
- **`colors.properties` exists + Material You enabled**: Material You wins. User must set `material-you-theme=disabled` to use `colors.properties` again.
- **Night mode + Material You "system"**: Both the M3 theme and AppCompatDelegate night mode follow system. No conflict.
- **Night mode + Material You "light"/"dark"/"black"**: The explicit variant overrides night-mode for the app chrome. Terminal colors follow the variant's light/dark state.
- **Black variant + DynamicColors**: The black theme overlay manually overrides surface colors to `#000000` after DynamicColors applies. The override wins because it's set in the theme resource.

---

## Checklist

### Phase 1: Config plumbing
- [ ] Add `KEY_MATERIAL_YOU_THEME` and constants to `TermuxPropertyConstants.java`
- [ ] Add `MAP_MATERIAL_YOU_THEME` ImmutableBiMap
- [ ] Add key to `TERMUX_APP_PROPERTIES_LIST`
- [ ] Add `getMaterialYouThemeInternalPropertyValueFromValue()` to `TermuxSharedProperties.java`
- [ ] Add `case KEY_MATERIAL_YOU_THEME:` to switch in `getInternalTermuxPropertyValueFromValue()`
- [ ] Add `getMaterialYouTheme()` accessor to `TermuxSharedProperties`

### Phase 2: M3 theme resources
- [ ] Add M3 parent themes to `termux-shared/src/main/res/values/themes.xml`
- [ ] Add M3 dark parent themes to `termux-shared/src/main/res/values-night/themes.xml`
- [ ] Add M3 TermuxActivity themes to `app/src/main/res/values/themes.xml`
- [ ] Add M3 dark TermuxActivity theme to `app/src/main/res/values-night/themes.xml`
- [ ] Add M3 black (OLED) theme variant to `app/src/main/res/values/themes.xml`
- [ ] Add M3 button bar style to `app/src/main/res/values/styles.xml`

### Phase 3: Theme selection logic
- [ ] Create `applyMaterialYouThemeOverlay()` in `TermuxActivity.java`
- [ ] Update `setActivityTheme()` to branch on `material-you-theme`
- [ ] Call `applyMaterialYouThemeOverlay()` before `super.onCreate()`
- [ ] Call `DynamicColors.applyToActivityIfAvailable()` when M3 is active
- [ ] Verify `reloadActivityStyling()` picks up theme changes

### Phase 4: Terminal colors from M3
- [ ] Create `MaterialYouTerminalColors.java` with `generate(Context)` method
- [ ] Implement ANSI 0-15 mapping from M3 color attributes
- [ ] Implement foreground/background/cursor from M3 surface/onSurface/primary
- [ ] Update `checkForFontAndColors()` in `TermuxTerminalSessionActivityClient`
- [ ] Update `checkForFontAndColors()` in `BubbleTerminalSessionClient`

### Phase 5: Testing
- [ ] Test `material-you-theme=disabled` (unchanged behavior)
- [ ] Test `material-you-theme=light` on API 31+ (wallpaper colors)
- [ ] Test `material-you-theme=light` on API < 31 (static M3 fallback)
- [ ] Test `material-you-theme=dark` on API 31+
- [ ] Test `material-you-theme=black` — verify pure black backgrounds
- [ ] Test `material-you-theme=system` — verify follows system dark mode
- [ ] Test with existing `colors.properties` — verify M3 takes precedence
- [ ] Test hot-reload: edit `termux.properties`, send `ACTION_RELOAD_STYLE` broadcast
- [ ] Test Termux:Styling app interaction — verify it doesn't conflict
- [ ] Test bubbles with M3 theme active

### Phase 6: Polish
- [ ] Document `material-you-theme` in fork docs
- [ ] Add to `docs/fork/` theming guide if one exists
- [ ] Consider adding UI toggle in settings (optional, not required)
