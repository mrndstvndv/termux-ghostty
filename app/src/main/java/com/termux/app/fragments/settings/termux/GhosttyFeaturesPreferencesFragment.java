package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

@Keep
public class GhosttyFeaturesPreferencesFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(GhosttyFeaturesPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_ghostty_features_preferences, rootKey);
    }

}

class GhosttyFeaturesPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedProperties mProperties;

    private static GhosttyFeaturesPreferencesDataStore mInstance;

    private GhosttyFeaturesPreferencesDataStore(Context context) {
        mContext = context.getApplicationContext();
        mProperties = TermuxAppSharedProperties.getProperties();
    }

    public static synchronized GhosttyFeaturesPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new GhosttyFeaturesPreferencesDataStore(context);
        }
        return mInstance;
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (mProperties == null || key == null) return;

        mProperties.setPropertyValueAndSave(mContext, key, value ? "true" : "false");
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mProperties == null || key == null) return defValue;

        switch (key) {
            case TermuxPropertyConstants.KEY_USE_SESSION_TABS:
                return mProperties.shouldUseSessionTabs();
            case TermuxPropertyConstants.KEY_REMEMBER_SOFT_KEYBOARD_STATE:
                return mProperties.shouldRememberSoftKeyboardState();
            case TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN:
                return mProperties.shouldOpenTerminalTranscriptURLOnClick();
            case TermuxPropertyConstants.KEY_TERMINAL_ONCLICK_URL_OPEN_WHEN_MOUSE_TRACKING_ACTIVE:
                return mProperties.shouldOpenTerminalTranscriptURLOnClickWhenMouseTrackingActive();
            default:
                return defValue;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (mProperties == null || key == null) return;

        mProperties.setPropertyValueAndSave(mContext, key, value != null ? value : "");
    }

    @Override
    public String getString(String key, String defValue) {
        if (mProperties == null || key == null) return defValue;

        switch (key) {
            case TermuxPropertyConstants.KEY_SESSION_TAB_BAR_POSITION:
                return mProperties.getSessionTabBarPosition();
            case TermuxPropertyConstants.KEY_SESSION_TAB_BAR_ALIGN:
                return mProperties.getSessionTabBarAlign();
            case TermuxPropertyConstants.KEY_MATERIAL_YOU_THEME:
                return mProperties.getMaterialYouTheme();
            default:
                return defValue;
        }
    }

}
