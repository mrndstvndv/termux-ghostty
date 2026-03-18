package com.termux.app.bubbles;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.content.LocusId;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.BubbleSessionActivity;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SessionBubbleController {

    private final TermuxService mService;
    private final NotificationManager mNotificationManager;
    private final SessionShortcutHelper mSessionShortcutHelper;
    private final Map<String, Integer> mSessionNotificationIds = new HashMap<>();

    private int mNextNotificationId = TermuxConstants.TERMUX_APP_SESSION_BUBBLE_NOTIFICATION_ID_BASE;

    private static final String LOG_TAG = "SessionBubbleController";

    public SessionBubbleController(@NonNull TermuxService service) {
        mService = service;
        mNotificationManager = NotificationUtils.getNotificationManager(service);
        mSessionShortcutHelper = new SessionShortcutHelper(service);
        setupNotificationChannel();
    }

    public boolean isSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        if (mNotificationManager == null) return false;
        return mSessionShortcutHelper.isSupported();
    }

    public synchronized boolean isSessionBubbled(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return false;
        return mSessionNotificationIds.containsKey(sessionHandle);
    }

    public synchronized void bubbleSession(@Nullable TerminalSession session, boolean autoExpand) {
        if (session == null) return;
        if (!isSupported()) return;
        if (!session.isRunning()) return;

        String sessionHandle = session.mHandle;
        int notificationId = getOrCreateNotificationId(sessionHandle);
        String sessionLabel = getSessionLabel(session);
        Integer sessionIndex = getSessionIndex(session);

        try {
            mSessionShortcutHelper.publishSessionShortcut(session, sessionLabel, sessionIndex);
            Notification notification = buildBubbleNotification(session, notificationId, sessionLabel, sessionIndex, autoExpand);
            if (notification == null) {
                mSessionNotificationIds.remove(sessionHandle);
                return;
            }

            mNotificationManager.notify(notificationId, notification);
        } catch (Exception e) {
            mSessionNotificationIds.remove(sessionHandle);
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to bubble session " + sessionHandle, e);
        }
    }

    public synchronized void updateSessionBubble(@Nullable TerminalSession session) {
        if (session == null) return;
        if (!isSessionBubbled(session.mHandle)) return;
        bubbleSession(session, false);
    }

    public synchronized void onSessionTitleChanged(@Nullable TerminalSession session) {
        if (session == null) return;
        if (!isSessionBubbled(session.mHandle)) return;
        bubbleSession(session, false);
    }

    public synchronized void onSessionFinished(@Nullable TerminalSession session) {
        if (session == null) return;
        unbubbleSession(session.mHandle);
    }

    public synchronized void refreshAllBubbles() {
        if (mSessionNotificationIds.isEmpty()) return;

        List<String> sessionHandles = new ArrayList<>(mSessionNotificationIds.keySet());
        for (String sessionHandle : sessionHandles) {
            TerminalSession session = mService.getTerminalSessionForHandle(sessionHandle);
            if (session == null || !session.isRunning()) {
                unbubbleSession(sessionHandle);
                continue;
            }

            bubbleSession(session, false);
        }
    }

    public synchronized void unbubbleSession(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return;

        Integer notificationId = mSessionNotificationIds.remove(sessionHandle);
        if (notificationId != null && mNotificationManager != null)
            mNotificationManager.cancel(notificationId);

        mSessionShortcutHelper.removeSessionShortcut(sessionHandle);
    }

    public synchronized void clearAll() {
        if (mSessionNotificationIds.isEmpty()) return;

        List<String> sessionHandles = new ArrayList<>(mSessionNotificationIds.keySet());
        for (String sessionHandle : sessionHandles)
            unbubbleSession(sessionHandle);
    }

    @NonNull
    public String getSessionLabel(@NonNull TerminalSession session) {
        String sessionName = session.mSessionName;
        if (!TextUtils.isEmpty(sessionName)) {
            String trimmedSessionName = sessionName.trim();
            if (!trimmedSessionName.isEmpty()) return trimmedSessionName;
        }

        int index = mService.getIndexOfSession(session);
        if (index >= 0)
            return "[" + (index + 1) + "] " + mService.getString(R.string.label_terminal_session_shell);

        return mService.getString(R.string.label_terminal_session_shell);
    }

    @Nullable
    private Notification buildBubbleNotification(@NonNull TerminalSession session, int notificationId,
                                                 @NonNull String sessionLabel, @Nullable Integer sessionIndex,
                                                 boolean autoExpand) {
        Person sessionPerson = buildSessionPerson(session, sessionLabel);
        Icon sessionIcon = null;
        if (!shouldUseShortcutBackedBubbleMetadata())
            sessionIcon = mSessionShortcutHelper.createSessionIcon(session, sessionLabel, sessionIndex);

        PendingIntent contentIntent = PendingIntent.getActivity(mService, notificationId,
            TermuxActivity.newInstance(mService, session.mHandle), getContentPendingIntentFlags());

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(mService,
            TermuxConstants.TERMUX_APP_SESSION_BUBBLE_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH,
            sessionLabel, getSessionSubtitle(session), null, contentIntent, null,
            NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) return null;

        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setCategory(Notification.CATEGORY_MESSAGE);
        builder.setOnlyAlertOnce(true);
        builder.setShortcutId(session.mHandle);
        builder.addPerson(sessionPerson);
        builder.setStyle(new Notification.MessagingStyle(sessionPerson)
            .setConversationTitle(sessionLabel)
            .addMessage(getSessionSubtitle(session), System.currentTimeMillis(), sessionPerson));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            builder.setLocusId(new LocusId(session.mHandle));

        builder.setBubbleMetadata(buildBubbleMetadata(session, notificationId, sessionIcon, autoExpand));

        return builder.build();
    }

    @NonNull
    private Notification.BubbleMetadata buildBubbleMetadata(@NonNull TerminalSession session, int notificationId,
                                                            @Nullable Icon sessionIcon, boolean autoExpand) {
        PendingIntent deleteIntent = PendingIntent.getService(mService, notificationId,
            new Intent(mService, TermuxService.class)
                .setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_UNBUBBLE_SESSION)
                .putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_SESSION_HANDLE, session.mHandle),
            getDeletePendingIntentFlags());

        if (shouldUseShortcutBackedBubbleMetadata()) {
            return new Notification.BubbleMetadata.Builder(session.mHandle)
                .setDeleteIntent(deleteIntent)
                .setDesiredHeight(getDesiredBubbleHeight())
                .setAutoExpandBubble(autoExpand)
                .setSuppressNotification(true)
                .build();
        }

        if (sessionIcon == null)
            throw new IllegalArgumentException("Session bubble icon is required for PendingIntent bubbles");

        PendingIntent bubbleIntent = PendingIntent.getActivity(mService, notificationId,
            BubbleSessionActivity.newInstance(mService, session.mHandle), getBubblePendingIntentFlags());

        return new Notification.BubbleMetadata.Builder(bubbleIntent, sessionIcon)
            .setDeleteIntent(deleteIntent)
            .setDesiredHeight(getDesiredBubbleHeight())
            .setAutoExpandBubble(autoExpand)
            .setSuppressNotification(true)
            .build();
    }

    private int getOrCreateNotificationId(@NonNull String sessionHandle) {
        Integer notificationId = mSessionNotificationIds.get(sessionHandle);
        if (notificationId != null) return notificationId;

        int nextNotificationId = mNextNotificationId++;
        mSessionNotificationIds.put(sessionHandle, nextNotificationId);
        return nextNotificationId;
    }

    @Nullable
    private Integer getSessionIndex(@NonNull TerminalSession session) {
        int sessionIndex = mService.getIndexOfSession(session);
        if (sessionIndex < 0) return null;
        return sessionIndex;
    }

    @NonNull
    private Person buildSessionPerson(@NonNull TerminalSession session, @NonNull String sessionLabel) {
        return new Person.Builder()
            .setName(sessionLabel)
            .setKey(session.mHandle)
            .setImportant(true)
            .build();
    }

    @NonNull
    private CharSequence getSessionSubtitle(@NonNull TerminalSession session) {
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) return title;
        return mService.getString(R.string.notification_text_terminal_session);
    }

    private int getDesiredBubbleHeight() {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 400,
            mService.getResources().getDisplayMetrics()));
    }

    private int getContentPendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private int getDeletePendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private int getBubblePendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;

        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private boolean shouldUseShortcutBackedBubbleMetadata() {
        // Android 16+ can crash in SystemUI when expanding this flow as a PendingIntent-backed app
        // bubble. Shortcut-backed metadata keeps the session on the conversation bubble path.
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    private void setupNotificationChannel() {
        NotificationUtils.setupNotificationChannel(mService,
            TermuxConstants.TERMUX_APP_SESSION_BUBBLE_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_SESSION_BUBBLE_NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH, true);
    }

}
