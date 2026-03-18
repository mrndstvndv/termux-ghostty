package com.termux.app.bubbles;

import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.BubbleSessionActivity;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.util.Collections;

public final class SessionShortcutHelper {

    private static final int MAX_SHORTCUT_LABEL_LENGTH = 40;
    private static final String SESSION_SHORTCUT_CATEGORY = "com.termux.session";

    private final Context mContext;
    private final SessionBubbleIconFactory mSessionBubbleIconFactory;

    public SessionShortcutHelper(@NonNull Context context) {
        mContext = context;
        mSessionBubbleIconFactory = new SessionBubbleIconFactory(context);
    }

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getShortcutManager() != null;
    }

    public void publishSessionShortcut(@NonNull TerminalSession session, @NonNull String label,
                                       @Nullable Integer sessionIndex) {
        if (!isSupported()) return;

        ShortcutManager shortcutManager = getShortcutManager();
        if (shortcutManager == null) return;

        shortcutManager.pushDynamicShortcut(buildShortcutInfo(session, label, sessionIndex));
    }

    @NonNull
    public Icon createSessionIcon(@NonNull TerminalSession session, @NonNull String label,
                                  @Nullable Integer sessionIndex) {
        return mSessionBubbleIconFactory.createSessionIcon(session, label, sessionIndex);
    }

    public void removeSessionShortcut(@Nullable String sessionHandle) {
        if (!isSupported()) return;
        if (TextUtils.isEmpty(sessionHandle)) return;

        ShortcutManager shortcutManager = getShortcutManager();
        if (shortcutManager == null) return;

        shortcutManager.removeLongLivedShortcuts(Collections.singletonList(sessionHandle));
        shortcutManager.removeDynamicShortcuts(Collections.singletonList(sessionHandle));
    }

    @NonNull
    private ShortcutInfo buildShortcutInfo(@NonNull TerminalSession session, @NonNull String label,
                                           @Nullable Integer sessionIndex) {
        String shortcutLabel = sanitizeShortcutLabel(label);
        Person sessionPerson = buildSessionPerson(session, shortcutLabel);
        Intent shortcutIntent = buildShortcutIntent(session);

        ShortcutInfo.Builder shortcutBuilder = new ShortcutInfo.Builder(mContext, session.mHandle)
            .setShortLabel(shortcutLabel)
            .setLongLabel(shortcutLabel)
            .setIcon(createSessionIcon(session, shortcutLabel, sessionIndex))
            .setIntent(shortcutIntent)
            .setLongLived(true)
            .setCategories(Collections.singleton(SESSION_SHORTCUT_CATEGORY))
            .setPersons(new Person[]{sessionPerson});

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            shortcutBuilder.setLocusId(new LocusId(session.mHandle));

        return shortcutBuilder.build();
    }

    @NonNull
    private Person buildSessionPerson(@NonNull TerminalSession session, @NonNull String label) {
        return new Person.Builder()
            .setName(label)
            .setKey(session.mHandle)
            .setImportant(true)
            .build();
    }

    @NonNull
    private String sanitizeShortcutLabel(@NonNull String label) {
        String shortcutLabel = label.trim();
        if (shortcutLabel.isEmpty()) shortcutLabel = mContext.getString(R.string.label_terminal_session_shell);
        if (shortcutLabel.length() <= MAX_SHORTCUT_LABEL_LENGTH) return shortcutLabel;
        return shortcutLabel.substring(0, MAX_SHORTCUT_LABEL_LENGTH);
    }

    @NonNull
    private Intent buildShortcutIntent(@NonNull TerminalSession session) {
        Intent shortcutIntent;
        if (shouldUseBubbleActivityShortcut()) {
            shortcutIntent = BubbleSessionActivity.newInstance(mContext, session.mHandle);
        } else {
            shortcutIntent = TermuxActivity.newInstance(mContext, session.mHandle);
        }

        shortcutIntent.setAction(Intent.ACTION_VIEW);
        return shortcutIntent;
    }

    private boolean shouldUseBubbleActivityShortcut() {
        // Shortcut-backed bubble metadata expands the shortcut activity itself.
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    @Nullable
    private ShortcutManager getShortcutManager() {
        return mContext.getSystemService(ShortcutManager.class);
    }

}
