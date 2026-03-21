package com.termux.app;

import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import com.termux.R;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.ThemeUtils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class MaterialYouThemeValidationTest {

    @Test
    public void getAppNightMode_respectsDisabledAndMaterialYouVariants() {
        Assert.assertEquals("true", TermuxThemeUtils.getAppNightMode("true",
            TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DISABLED));
        Assert.assertEquals("false", TermuxThemeUtils.getAppNightMode("false",
            TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DISABLED));
        Assert.assertEquals("system", TermuxThemeUtils.getAppNightMode("system",
            TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DISABLED));

        Assert.assertEquals("false", TermuxThemeUtils.getAppNightMode("true",
            TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_LIGHT));
        Assert.assertEquals("true", TermuxThemeUtils.getAppNightMode("false",
            TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_DARK));
        Assert.assertEquals("true", TermuxThemeUtils.getAppNightMode("false",
            TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_BLACK));
        Assert.assertEquals("system", TermuxThemeUtils.getAppNightMode("false",
            TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_SYSTEM));
    }

    @Test
    public void materialYouThemes_resolveDrawerButtonsToM3Style() {
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_M3_Light_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_M3);
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_M3_DayNight_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_M3);
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_M3_Black_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_M3);
    }

    @Test
    public void legacyTheme_keepsLegacyDrawerButtonStyle() {
        assertButtonBarButtonStyle(R.style.Theme_TermuxActivity_DayNight_NoActionBar,
            R.style.TermuxActivity_Drawer_ButtonBarStyle_Light);
    }

    @Test
    public void blackTheme_resolvesBlackExtraKeysBackground() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
            R.style.Theme_TermuxActivity_M3_Black_NoActionBar);

        int extraKeysBackground = ThemeUtils.getSystemAttrColor(context,
            com.termux.shared.R.attr.extraKeysButtonBackgroundColor);
        int drawerBackground = ThemeUtils.getSystemAttrColor(context, R.attr.termuxActivityDrawerBackground);

        Assert.assertEquals(0xFF000000, extraKeysBackground);
        Assert.assertEquals(0xFF000000, drawerBackground);
    }

    @Test
    public void blackTheme_inflatesBlackExtraKeysBackgrounds() {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
            R.style.Theme_TermuxActivity_M3_Black_NoActionBar);
        LayoutInflater inflater = LayoutInflater.from(context);

        View toolbarExtraKeys = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, null, false);
        Assert.assertTrue(toolbarExtraKeys.getBackground() instanceof ColorDrawable);
        Assert.assertEquals(0xFF000000, ((ColorDrawable) toolbarExtraKeys.getBackground()).getColor());

        View bubbleRoot = inflater.inflate(R.layout.activity_bubble_session, null, false);
        View bubbleExtraKeys = bubbleRoot.findViewById(R.id.bubble_extra_keys_view);
        Assert.assertTrue(bubbleExtraKeys.getBackground() instanceof ColorDrawable);
        Assert.assertEquals(0xFF000000, ((ColorDrawable) bubbleExtraKeys.getBackground()).getColor());
    }

    private void assertButtonBarButtonStyle(int themeRes, int expectedStyleRes) {
        ContextThemeWrapper context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), themeRes);
        TypedValue typedValue = new TypedValue();
        Assert.assertTrue(context.getTheme().resolveAttribute(android.R.attr.buttonBarButtonStyle, typedValue, true));
        Assert.assertEquals(expectedStyleRes, typedValue.resourceId);
    }

}
