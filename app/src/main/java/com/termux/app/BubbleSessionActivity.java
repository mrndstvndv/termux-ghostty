package com.termux.app;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
import com.termux.R;
import com.termux.app.bubbles.BubbleTerminalExtraKeys;
import com.termux.app.bubbles.BubbleTerminalSessionClient;
import com.termux.app.bubbles.BubbleTerminalViewClient;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

public final class BubbleSessionActivity extends AppCompatActivity implements ServiceConnection {

    private TermuxService mTermuxService;
    private View mRootView;
    private TerminalView mTerminalView;
    private ExtraKeysView mExtraKeysView;
    private BubbleTerminalViewClient mTerminalViewClient;
    private BubbleTerminalSessionClient mTerminalSessionClient;
    private BubbleTerminalExtraKeys mTerminalExtraKeys;
    private TermuxAppSharedPreferences mPreferences;
    private TermuxAppSharedProperties mProperties;
    private String mSessionHandle;
    private boolean mIsVisible;
    private boolean mIsInvalidState;
    private boolean mDidCloseTermuxActivityOnBubbleOpen;
    private int mLastMaterialYouWallpaperId;

    private static final float DEFAULT_EXTRA_KEYS_HEIGHT_DP = 37.5f;

    private static final String LOG_TAG = "BubbleSessionActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");

        mProperties = TermuxAppSharedProperties.getProperties();
        mProperties.loadTermuxPropertiesFromDisk();
        mLastMaterialYouWallpaperId = getMaterialYouWallpaperId();
        setActivityTheme();
        applyMaterialYouThemeOverlay();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bubble_session);

        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            mIsInvalidState = true;
            finishActivityIfNotFinishing();
            return;
        }

        mSessionHandle = getSessionHandle(getIntent());
        if (TextUtils.isEmpty(mSessionHandle)) {
            mIsInvalidState = true;
            finishForMissingSession();
            return;
        }

        mRootView = findViewById(R.id.activity_bubble_root_view);
        mTerminalView = findViewById(R.id.bubble_terminal_view);
        mExtraKeysView = findViewById(R.id.bubble_extra_keys_view);
        mTerminalViewClient = new BubbleTerminalViewClient(this);
        mTerminalSessionClient = new BubbleTerminalSessionClient(this);
        mTerminalExtraKeys = new BubbleTerminalExtraKeys(this, mTerminalView, mTerminalViewClient);

        mTerminalView.setTerminalViewClient(mTerminalViewClient);
        mTerminalView.setTextSize(mPreferences.getFontSize());
        mTerminalView.setKeepScreenOn(mPreferences.shouldKeepScreenOn());
        mTerminalSessionClient.onCreate();
        setupExtraKeysView();
        setRootWindowInsetsListener();
        KeyboardUtils.setSoftInputModeAdjustResize(this);

        bindTermuxService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsVisible = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mIsInvalidState) return;

        reloadMaterialYouThemeIfNeeded();
        mTerminalSessionClient.onResume();
        mTerminalViewClient.setSoftKeyboardState();
        updateSessionTitle();
        markCurrentSessionBubbleConversationRead();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mIsInvalidState) return;

        mIsVisible = false;
        mTerminalSessionClient.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (mIsInvalidState) return;
        markCurrentSessionBubbleConversationRead();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mTermuxService != null) {
            mTermuxService.unregisterTerminalSessionClient(mTerminalSessionClient);
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // Ignore stale unbinds.
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");
        mTermuxService = ((TermuxService.LocalBinder) service).service;
        mTermuxService.registerTerminalSessionClient(mTerminalSessionClient);
        attachRequestedSession();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");
        finishActivityIfNotFinishing();
    }

    private void bindTermuxService() {
        try {
            Intent serviceIntent = new Intent(this, TermuxService.class);
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to bind TermuxService for bubble session", e);
            Logger.showToast(this, getString(R.string.error_termux_service_start_failed_general), true);
            mIsInvalidState = true;
            finishActivityIfNotFinishing();
        }
    }

    private void attachRequestedSession() {
        if (mTermuxService == null) return;

        TerminalSession session = mTermuxService.getTerminalSessionForHandle(mSessionHandle);
        if (session == null || !session.isRunning()) {
            finishForMissingSession();
            return;
        }

        mTerminalView.attachSession(session);
        mTerminalSessionClient.applyTerminalStyling();
        updateSessionTitle();
        markCurrentSessionBubbleConversationRead();
        mTerminalView.requestFocus();
        closeTermuxActivityIfLaunchedFromBubble();
    }

    private void setupExtraKeysView() {
        if (mExtraKeysView == null) return;

        ExtraKeysInfo extraKeysInfo = mTerminalExtraKeys.getExtraKeysInfo();
        if (extraKeysInfo == null) {
            mExtraKeysView.setVisibility(View.GONE);
            return;
        }

        mExtraKeysView.setExtraKeysViewClient(mTerminalExtraKeys);
        mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
        mExtraKeysView.reload(extraKeysInfo, getExtraKeysButtonHeight());

        ViewGroup.LayoutParams layoutParams = mExtraKeysView.getLayoutParams();
        layoutParams.height = getExtraKeysViewHeight(extraKeysInfo);
        mExtraKeysView.setLayoutParams(layoutParams);
        mExtraKeysView.setVisibility(View.VISIBLE);
    }

    private void setRootWindowInsetsListener() {
        if (mRootView == null) return;

        final int basePaddingLeft = mRootView.getPaddingLeft();
        final int basePaddingTop = mRootView.getPaddingTop();
        final int basePaddingRight = mRootView.getPaddingRight();
        final int basePaddingBottom = mRootView.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(mRootView, (view, windowInsets) -> {
            Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            Insets systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            int keyboardBottomInset = Math.max(0, imeInsets.bottom - systemBarInsets.bottom);

            view.setPadding(basePaddingLeft, basePaddingTop, basePaddingRight,
                basePaddingBottom + keyboardBottomInset);
            return windowInsets;
        });

        ViewCompat.requestApplyInsets(mRootView);
    }

    private void closeTermuxActivityIfLaunchedFromBubble() {
        if (mDidCloseTermuxActivityOnBubbleOpen) return;
        if (!shouldCloseTermuxActivityIfLaunchedFromBubble()) return;
        if (mTermuxService == null) return;
        if (getCurrentSession() == null) return;

        mDidCloseTermuxActivityOnBubbleOpen = true;
        mTermuxService.finishTermuxActivityIfPresent();
    }

    private void markCurrentSessionBubbleConversationRead() {
        if (mTermuxService == null) return;

        TerminalSession session = getCurrentSession();
        if (session == null) return;

        mTermuxService.markSessionBubbleConversationRead(session.mHandle);
    }

    private boolean shouldCloseTermuxActivityIfLaunchedFromBubble() {
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_LAUNCHED_FROM_BUBBLE, false))
            return true;

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isLaunchedFromBubble();
    }

    private void finishForMissingSession() {
        Logger.showToast(this, getString(R.string.error_termux_session_not_found), true);
        finishActivityIfNotFinishing();
    }

    private void setActivityTheme() {
        String appNightMode = TermuxThemeUtils.getAppNightMode(mProperties.getNightMode(), mProperties.getMaterialYouTheme());
        TermuxThemeUtils.setAppNightMode(appNightMode);
        AppCompatActivityUtils.setNightMode(this, appNightMode, true);
    }

    private void applyMaterialYouThemeOverlay() {
        String materialYouTheme = mProperties.getMaterialYouTheme();
        if (!TermuxThemeUtils.isMaterialYouThemeEnabled(materialYouTheme)) return;

        if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_LIGHT.equals(materialYouTheme)) {
            setTheme(R.style.Theme_TermuxActivity_M3_Light_NoActionBar);
        } else if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_BLACK.equals(materialYouTheme)) {
            setTheme(R.style.Theme_TermuxActivity_M3_Black_NoActionBar);
        } else {
            setTheme(R.style.Theme_TermuxActivity_M3_DayNight_NoActionBar);
        }

        DynamicColors.applyToActivityIfAvailable(this);

        if (TermuxPropertyConstants.IVALUE_MATERIAL_YOU_THEME_BLACK.equals(materialYouTheme)) {
            getTheme().applyStyle(R.style.Theme_TermuxActivity_M3_Black_NoActionBar, true);
        }
    }

    public void onSessionFinished() {
        finishActivityIfNotFinishing();
    }

    public void updateSessionTitle() {
        TerminalSession session = getCurrentSession();
        if (session == null) {
            setTitle(R.string.label_terminal_session_shell);
            return;
        }

        if (mTermuxService != null) {
            setTitle(mTermuxService.getSessionBubbleLabel(session));
            return;
        }

        String sessionName = session.mSessionName;
        if (TextUtils.isEmpty(sessionName)) {
            setTitle(R.string.label_terminal_session_shell);
            return;
        }

        String trimmedSessionName = sessionName.trim();
        if (trimmedSessionName.isEmpty()) {
            setTitle(R.string.label_terminal_session_shell);
            return;
        }

        setTitle(trimmedSessionName);
    }

    public void finishActivityIfNotFinishing() {
        if (!isFinishing())
            finish();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    @NonNull
    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    @NonNull
    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }

    @Nullable
    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    @NonNull
    public BubbleTerminalSessionClient getTerminalSessionClient() {
        return mTerminalSessionClient;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView == null) return null;
        return mTerminalView.getCurrentSession();
    }

    @Nullable
    private String getSessionHandle(@Nullable Intent intent) {
        if (intent == null) return null;
        return intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_SESSION_HANDLE);
    }

    private int getExtraKeysButtonHeight() {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
            DEFAULT_EXTRA_KEYS_HEIGHT_DP, getResources().getDisplayMetrics()));
    }

    private int getExtraKeysViewHeight(@NonNull ExtraKeysInfo extraKeysInfo) {
        int rowCount = extraKeysInfo.getMatrix().length;
        if (rowCount <= 0) return 0;

        return Math.round(getExtraKeysButtonHeight() * rowCount * mProperties.getTerminalToolbarHeightScaleFactor());
    }

    private void reloadMaterialYouThemeIfNeeded() {
        if (!shouldReloadMaterialYouThemeForWallpaperChanges()) return;

        int wallpaperId = getMaterialYouWallpaperId();
        if (wallpaperId == 0) return;
        if (mLastMaterialYouWallpaperId == 0) {
            mLastMaterialYouWallpaperId = wallpaperId;
            return;
        }
        if (mLastMaterialYouWallpaperId == wallpaperId) return;

        Logger.logDebug(LOG_TAG, "Recreating bubble for updated Material You wallpaper colors");
        mLastMaterialYouWallpaperId = wallpaperId;
        recreate();
    }

    private boolean shouldReloadMaterialYouThemeForWallpaperChanges() {
        if (mProperties == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        return TermuxThemeUtils.isMaterialYouThemeEnabled(mProperties.getMaterialYouTheme());
    }

    private int getMaterialYouWallpaperId() {
        if (!shouldReloadMaterialYouThemeForWallpaperChanges()) return 0;
        return WallpaperManager.getInstance(this).getWallpaperId(WallpaperManager.FLAG_SYSTEM);
    }

    public static Intent newInstance(@NonNull Context context, @NonNull String sessionHandle) {
        Intent intent = new Intent(context, BubbleSessionActivity.class);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_SESSION_HANDLE, sessionHandle);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        return intent;
    }

    public static Intent newBubbleInstance(@NonNull Context context, @NonNull String sessionHandle) {
        Intent intent = newInstance(context, sessionHandle);
        intent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_LAUNCHED_FROM_BUBBLE, true);
        return intent;
    }

}
