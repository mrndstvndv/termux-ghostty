package com.termux.app.terminal;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Intent;
import android.content.LocusId;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class TermuxTerminalSessionServiceClient extends TermuxTerminalSessionClientBase {

    private static final String LOG_TAG = "TermuxTerminalSessionServiceClient";

    private final TermuxService mService;
    private int mNextTerminalProtocolNotificationId = TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATION_ID_BASE;

    public TermuxTerminalSessionServiceClient(TermuxService service) {
        this.mService = service;
    }

    @Override
    public void onTerminalProtocolNotification(@NonNull TerminalSession session, @Nullable String title, @Nullable String body) {
        mService.onTerminalSessionProtocolNotification(session, title, body);
        if (mService.isSessionBubbled(session.mHandle)) return;

        NotificationManager notificationManager = NotificationUtils.getNotificationManager(mService);
        if (notificationManager == null) return;

        String normalizedTitle = normalizeNotificationText(title);
        String normalizedBody = normalizeNotificationText(body);
        String sessionLabel = getSessionLabel(session);

        CharSequence notificationTitle = !TextUtils.isEmpty(normalizedTitle) ? normalizedTitle : sessionLabel;
        CharSequence notificationText = !TextUtils.isEmpty(normalizedBody) ? normalizedBody : sessionLabel;
        if (TextUtils.equals(notificationTitle, notificationText))
            notificationText = mService.getString(R.string.notification_text_terminal_session);

        int notificationId = getNextTerminalProtocolNotificationId();
        PendingIntent contentIntent = getTerminalProtocolNotificationContentIntent(session, notificationId);

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(mService,
            TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_DEFAULT,
            notificationTitle,
            notificationText,
            normalizedBody,
            contentIntent,
            null,
            NotificationUtils.NOTIFICATION_MODE_ALL);
        if (builder == null) return;

        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setAutoCancel(true);
        builder.setCategory(Notification.CATEGORY_MESSAGE);
        if (!TextUtils.isEmpty(sessionLabel))
            builder.setSubText(sessionLabel);

        decorateBubbleConversationNotification(builder, session, sessionLabel, normalizedTitle, normalizedBody);

        try {
            notificationManager.notify(notificationId, builder.build());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show terminal protocol notification", e);
        }
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }

    private void decorateBubbleConversationNotification(@NonNull Notification.Builder builder,
                                                        @NonNull TerminalSession session,
                                                        @NonNull String sessionLabel,
                                                        @Nullable String normalizedTitle,
                                                        @Nullable String normalizedBody) {
        String shortcutId = getBubbleConversationShortcutId(session);
        if (shortcutId == null) return;

        Person sessionPerson = new Person.Builder()
            .setName(sessionLabel)
            .setKey(shortcutId)
            .setImportant(true)
            .build();

        builder.setShortcutId(shortcutId);
        builder.addPerson(sessionPerson);
        builder.setStyle(new Notification.MessagingStyle(sessionPerson)
            .setConversationTitle(sessionLabel)
            .addMessage(buildProtocolMessageText(normalizedTitle, normalizedBody, sessionLabel),
                System.currentTimeMillis(), sessionPerson));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            builder.setLocusId(new LocusId(shortcutId));
    }

    @NonNull
    private PendingIntent getTerminalProtocolNotificationContentIntent(@NonNull TerminalSession session,
                                                                       int notificationId) {
        return PendingIntent.getActivity(mService, notificationId,
            TermuxActivity.newInstance(mService, session.mHandle),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Nullable
    private String getBubbleConversationShortcutId(@NonNull TerminalSession session) {
        if (!mService.hasSessionBubbleConversation(session.mHandle)) return null;

        int bubbleSlotId = mService.getBubbleSlotId(session);
        if (bubbleSlotId < 1) return null;

        return TermuxConstants.TERMUX_APP_SESSION_BUBBLE_SHORTCUT_ID_PREFIX + bubbleSlotId;
    }

    @NonNull
    private CharSequence buildProtocolMessageText(@Nullable String normalizedTitle,
                                                  @Nullable String normalizedBody,
                                                  @NonNull String sessionLabel) {
        if (normalizedTitle == null && normalizedBody == null)
            return sessionLabel;
        if (normalizedTitle == null)
            return normalizedBody;
        if (normalizedBody == null)
            return normalizedTitle;
        if (TextUtils.equals(normalizedTitle, normalizedBody))
            return normalizedTitle;

        return normalizedTitle + "\n" + normalizedBody;
    }

    private synchronized int getNextTerminalProtocolNotificationId() {
        return mNextTerminalProtocolNotificationId++;
    }

    @NonNull
    private String getSessionLabel(@NonNull TerminalSession session) {
        if (mService.canBubbleSessions())
            return mService.getSessionBubbleLabel(session);

        int sessionIndex = mService.getIndexOfSession(session);

        String sessionName = normalizeNotificationText(session.mSessionName);
        if (!TextUtils.isEmpty(sessionName)) {
            if (sessionIndex >= 0)
                return "[" + (sessionIndex + 1) + "] " + sessionName;

            return sessionName;
        }

        if (sessionIndex >= 0)
            return "[" + (sessionIndex + 1) + "] " + mService.getString(R.string.label_terminal_session_shell);

        return mService.getString(R.string.label_terminal_session_shell);
    }

    @Nullable
    private String normalizeNotificationText(@Nullable String text) {
        if (text == null) return null;

        String trimmedText = text.trim();
        if (trimmedText.isEmpty()) return null;
        return trimmedText;
    }

}
