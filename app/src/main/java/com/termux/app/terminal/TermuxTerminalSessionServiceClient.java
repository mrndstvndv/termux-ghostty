package com.termux.app.terminal;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
        PendingIntent contentIntent = PendingIntent.getActivity(mService, notificationId,
            TermuxActivity.newInstance(mService, session.mHandle),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

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

    private synchronized int getNextTerminalProtocolNotificationId() {
        return mNextTerminalProtocolNotificationId++;
    }

    @NonNull
    private String getSessionLabel(@NonNull TerminalSession session) {
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
