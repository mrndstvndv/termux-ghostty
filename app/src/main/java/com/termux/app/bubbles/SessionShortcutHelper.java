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
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class SessionShortcutHelper {

    private static final int MAX_SHORTCUT_LABEL_LENGTH = 40;
    private static final String SESSION_SHORTCUT_CATEGORY = "com.termux.session";
    private static final String LOG_TAG = "SessionShortcutHelper";

    private final Context mContext;
    private final SessionBubbleIconFactory mSessionBubbleIconFactory;

    public SessionShortcutHelper(@NonNull Context context) {
        mContext = context;
        mSessionBubbleIconFactory = new SessionBubbleIconFactory(context);
    }

    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getShortcutManager() != null;
    }

    public void clearAllSessionShortcuts() {
        if (!isSupported()) return;

        try {
            ShortcutManager shortcutManager = getShortcutManager();
            if (shortcutManager == null) return;

            List<ShortcutInfo> shortcuts = shortcutManager.getShortcuts(
                ShortcutManager.FLAG_MATCH_DYNAMIC | ShortcutManager.FLAG_MATCH_CACHED | ShortcutManager.FLAG_MATCH_PINNED | ShortcutManager.FLAG_MATCH_MANIFEST);
            if (shortcuts == null || shortcuts.isEmpty()) return;

            List<String> shortcutIds = new ArrayList<>();
            for (ShortcutInfo shortcut : shortcuts) {
                Set<String> categories = shortcut.getCategories();
                if (categories == null || !categories.contains(SESSION_SHORTCUT_CATEGORY)) continue;
                shortcutIds.add(shortcut.getId());
            }

            if (shortcutIds.isEmpty()) return;

            shortcutManager.removeDynamicShortcuts(shortcutIds);
            shortcutManager.removeLongLivedShortcuts(shortcutIds);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to clear Termux session shortcuts", e);
        }
    }

    public void publishSessionShortcut(@NonNull String shortcutId, @NonNull TerminalSession session,
                                       @NonNull String label, @Nullable Integer bubbleSlotId) {
        if (!isSupported()) return;

        ShortcutManager shortcutManager = getShortcutManager();
        if (shortcutManager == null) return;

        shortcutManager.pushDynamicShortcut(buildShortcutInfo(shortcutId, session, label, bubbleSlotId));
    }

    public void reportSessionShortcutUsed(@NonNull String shortcutId) {
        if (!isSupported()) return;

        ShortcutManager shortcutManager = getShortcutManager();
        if (shortcutManager == null) return;

        shortcutManager.reportShortcutUsed(shortcutId);
    }

    @NonNull
    public Icon createSessionIcon(@NonNull TerminalSession session, @NonNull String label,
                                  @Nullable Integer bubbleSlotId) {
        return mSessionBubbleIconFactory.createSessionIcon(session, label, bubbleSlotId);
    }

    public void removeSessionShortcut(@Nullable String shortcutId) {
        if (!isSupported()) return;
        if (TextUtils.isEmpty(shortcutId)) return;

        ShortcutManager shortcutManager = getShortcutManager();
        if (shortcutManager == null) return;

        shortcutManager.removeLongLivedShortcuts(Collections.singletonList(shortcutId));
        shortcutManager.removeDynamicShortcuts(Collections.singletonList(shortcutId));
    }

    @NonNull
    private ShortcutInfo buildShortcutInfo(@NonNull String shortcutId, @NonNull TerminalSession session,
                                           @NonNull String label, @Nullable Integer bubbleSlotId) {
        String shortcutLabel = sanitizeShortcutLabel(label);
        Person sessionPerson = buildSessionPerson(shortcutId, shortcutLabel);
        Intent shortcutIntent = buildShortcutIntent(session);

        ShortcutInfo.Builder shortcutBuilder = new ShortcutInfo.Builder(mContext, shortcutId)
            .setShortLabel(shortcutLabel)
            .setLongLabel(shortcutLabel)
            .setIcon(createSessionIcon(session, shortcutLabel, bubbleSlotId))
            .setIntent(shortcutIntent)
            .setLongLived(true)
            .setCategories(Collections.singleton(SESSION_SHORTCUT_CATEGORY))
            .setPersons(new Person[]{sessionPerson});

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            shortcutBuilder.setLocusId(new LocusId(shortcutId));

        return shortcutBuilder.build();
    }

    @NonNull
    private Person buildSessionPerson(@NonNull String shortcutId, @NonNull String label) {
        return new Person.Builder()
            .setName(label)
            .setKey(shortcutId)
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
