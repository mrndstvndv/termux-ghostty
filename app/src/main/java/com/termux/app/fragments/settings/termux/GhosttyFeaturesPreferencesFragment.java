package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
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

        setupRestartPromptListeners();
    }

    private void setupRestartPromptListeners() {
        String[] restartKeys = {
            "use-session-tabs",
            "session-tab-bar-position",
            "session-tab-bar-align",
            "remember-soft-keyboard-state",
            "terminal-onclick-url-open",
            "terminal-onclick-url-open-when-mouse-tracking-active",
            "material-you-theme"
        };

        for (String key : restartKeys) {
            androidx.preference.Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    showRestartDialog();
                    return true;
                });
            }
        }
    }

    private void showRestartDialog() {
        Context context = getContext();
        if (context == null) return;

        new androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Restart Required")
            .setMessage("This settings change requires a restart of Termux to take effect.")
            .setPositiveButton("Restart Now", (dialog, which) -> {
                restartTermux(context);
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void restartTermux(@NonNull Context context) {
        // Cleanly stop the service
        Intent stopServiceIntent = new Intent(context, com.termux.app.TermuxService.class);
        stopServiceIntent.setAction(com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE);
        context.startService(stopServiceIntent);

        // Relaunch the app
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(launchIntent);

            android.app.Activity activity = getActivity();
            if (activity != null) {
                activity.finishAffinity();
            }

            System.exit(0);
        }
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
