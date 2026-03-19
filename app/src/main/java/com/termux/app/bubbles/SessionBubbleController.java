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
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SessionBubbleController {

    private static final int MAX_PROTOCOL_MESSAGES = 20;
    private static final String LOG_TAG = "SessionBubbleController";

    private final TermuxService mService;
    private final NotificationManager mNotificationManager;
    private final SessionShortcutHelper mSessionShortcutHelper;
    private final Map<String, Integer> mSessionNotificationIds = new HashMap<>();
    private final Map<String, SessionConversationState> mSessionConversationStates = new HashMap<>();

    private static final class BubbleMessage {
        @NonNull
        private final String mText;
        private final long mTimestamp;

        private BubbleMessage(@NonNull String text, long timestamp) {
            mText = text;
            mTimestamp = timestamp;
        }
    }

    private static final class SessionConversationState {
        private final long mCreatedAt = System.currentTimeMillis();
        private final ArrayDeque<BubbleMessage> mProtocolMessages = new ArrayDeque<>();
        private boolean mHasUnread;

        private long getCreatedAt() {
            return mCreatedAt;
        }

        private void addProtocolMessage(@NonNull String text) {
            mProtocolMessages.addLast(new BubbleMessage(text, System.currentTimeMillis()));

            while (mProtocolMessages.size() > MAX_PROTOCOL_MESSAGES)
                mProtocolMessages.removeFirst();
        }

        private boolean hasProtocolMessages() {
            return !mProtocolMessages.isEmpty();
        }

        private boolean hasUnread() {
            return mHasUnread;
        }

        private void setUnread(boolean hasUnread) {
            mHasUnread = hasUnread;
        }

        @Nullable
        private BubbleMessage getLatestProtocolMessage() {
            return mProtocolMessages.peekLast();
        }

        @NonNull
        private List<BubbleMessage> getProtocolMessages() {
            return new ArrayList<>(mProtocolMessages);
        }
    }

    public SessionBubbleController(@NonNull TermuxService service) {
        mService = service;
        mNotificationManager = NotificationUtils.getNotificationManager(service);
        mSessionShortcutHelper = new SessionShortcutHelper(service);
        mSessionShortcutHelper.clearAllSessionShortcuts();
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

    public synchronized boolean hasBubbleConversation(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return false;
        return mSessionConversationStates.containsKey(sessionHandle);
    }

    public synchronized void bubbleSession(@Nullable TerminalSession session, boolean autoExpand) {
        if (session == null) return;

        ensureConversationState(session.mHandle).setUnread(false);
        postBubbleNotification(session, autoExpand, true, true);
    }

    public synchronized void updateSessionBubble(@Nullable TerminalSession session) {
        if (session == null) return;
        if (!hasBubbleConversation(session.mHandle)) return;
        postBubbleNotification(session, false, false, true);
    }

    public synchronized void onSessionTitleChanged(@Nullable TerminalSession session) {
        if (session == null) return;
        if (!hasBubbleConversation(session.mHandle)) return;
        postBubbleNotification(session, false, false, true);
    }

    public synchronized void onTerminalProtocolNotification(@Nullable TerminalSession session,
                                                            @Nullable String title,
                                                            @Nullable String body) {
        if (session == null) return;
        if (!session.isRunning()) return;

        String sessionHandle = session.mHandle;
        if (TextUtils.isEmpty(sessionHandle)) return;

        SessionConversationState conversationState = mSessionConversationStates.get(sessionHandle);
        if (conversationState == null) return;

        String protocolMessage = buildProtocolMessage(title, body);
        if (TextUtils.isEmpty(protocolMessage)) return;

        conversationState.addProtocolMessage(protocolMessage);
        if (!isSessionBubbled(sessionHandle)) return;

        conversationState.setUnread(!mService.isTerminalSessionFocused(session));
        postBubbleNotification(session, false, false, false);
    }

    public synchronized void markSessionConversationRead(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return;

        SessionConversationState conversationState = mSessionConversationStates.get(sessionHandle);
        if (conversationState == null) return;
        if (!conversationState.hasUnread()) return;

        conversationState.setUnread(false);
        if (!isSessionBubbled(sessionHandle)) return;

        TerminalSession session = mService.getTerminalSessionForHandle(sessionHandle);
        if (session == null || !session.isRunning()) return;

        postBubbleNotification(session, false, false, false);
    }

    public synchronized void onSessionFinished(@Nullable TerminalSession session) {
        if (session == null) return;
        dismissSessionBubble(session.mHandle);
    }

    public synchronized void refreshAllBubbles() {
        if (mSessionNotificationIds.isEmpty()) return;

        List<String> sessionHandles = new ArrayList<>(mSessionNotificationIds.keySet());
        for (String sessionHandle : sessionHandles) {
            TerminalSession session = mService.getTerminalSessionForHandle(sessionHandle);
            if (session == null || !session.isRunning()) {
                removeSessionBubble(sessionHandle);
                continue;
            }

            postBubbleNotification(session, false, false, true);
        }
    }

    public synchronized void unbubbleSession(@Nullable String sessionHandle) {
        dismissSessionBubble(sessionHandle);
    }

    public synchronized void removeSessionBubble(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return;

        Integer notificationId = mSessionNotificationIds.remove(sessionHandle);
        if (notificationId != null && mNotificationManager != null)
            mNotificationManager.cancel(notificationId);

        mSessionConversationStates.remove(sessionHandle);

        int bubbleSlotId = notificationId != null
            ? notificationId - TermuxConstants.TERMUX_APP_SESSION_BUBBLE_NOTIFICATION_ID_BASE
            : -1;
        if (bubbleSlotId < 1) {
            TerminalSession terminalSession = mService.getTerminalSessionForHandle(sessionHandle);
            if (terminalSession != null) {
                TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
                if (termuxSession != null)
                    bubbleSlotId = termuxSession.getBubbleSlotId();
            }
        }

        if (bubbleSlotId > 0)
            mSessionShortcutHelper.removeSessionShortcut(getBubbleShortcutId(bubbleSlotId));
    }

    public synchronized void clearAll() {
        if (mSessionConversationStates.isEmpty() && mSessionNotificationIds.isEmpty()) return;

        List<String> sessionHandles = new ArrayList<>(mSessionConversationStates.keySet());
        for (String sessionHandle : new ArrayList<>(mSessionNotificationIds.keySet())) {
            if (!sessionHandles.contains(sessionHandle))
                sessionHandles.add(sessionHandle);
        }

        for (String sessionHandle : sessionHandles)
            removeSessionBubble(sessionHandle);
    }

    @NonNull
    public String getSessionLabel(@NonNull TerminalSession session) {
        int bubbleSlotId = getBubbleSlotId(session);
        String sessionName = session.mSessionName;
        if (!TextUtils.isEmpty(sessionName)) {
            String trimmedSessionName = sessionName.trim();
            if (!trimmedSessionName.isEmpty()) {
                if (bubbleSlotId > 0) return "[" + bubbleSlotId + "] " + trimmedSessionName;
                return trimmedSessionName;
            }
        }

        String sessionTitle = mService.getString(R.string.label_terminal_session_shell);
        if (bubbleSlotId > 0) return "[" + bubbleSlotId + "] " + sessionTitle;

        int index = mService.getIndexOfSession(session);
        if (index >= 0)
            return "[" + (index + 1) + "] " + sessionTitle;

        return sessionTitle;
    }

    private void postBubbleNotification(@Nullable TerminalSession session, boolean autoExpand,
                                        boolean reportUsage, boolean publishShortcut) {
        if (session == null) return;
        if (!isSupported()) return;
        if (!session.isRunning()) return;

        String sessionHandle = session.mHandle;
        if (TextUtils.isEmpty(sessionHandle)) return;

        int bubbleSlotId = getBubbleSlotId(session);
        if (bubbleSlotId < 1) {
            Logger.logError(LOG_TAG, "Failed to bubble session " + sessionHandle + ": missing bubble slot");
            return;
        }

        ensureConversationState(sessionHandle);

        String shortcutId = getBubbleShortcutId(bubbleSlotId);
        int notificationId = getOrCreateNotificationId(sessionHandle, bubbleSlotId);
        String sessionLabel = getSessionLabel(session);

        try {
            if (reportUsage)
                mSessionShortcutHelper.reportSessionShortcutUsed(shortcutId);
            if (publishShortcut)
                mSessionShortcutHelper.publishSessionShortcut(shortcutId, session, sessionLabel, bubbleSlotId);

            Notification notification = buildBubbleNotification(session, notificationId, sessionLabel,
                bubbleSlotId, autoExpand);
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

    private void dismissSessionBubble(@Nullable String sessionHandle) {
        if (TextUtils.isEmpty(sessionHandle)) return;

        Integer notificationId = mSessionNotificationIds.remove(sessionHandle);
        if (notificationId != null && mNotificationManager != null)
            mNotificationManager.cancel(notificationId);
    }

    @Nullable
    private Notification buildBubbleNotification(@NonNull TerminalSession session, int notificationId,
                                                 @NonNull String sessionLabel, int bubbleSlotId,
                                                 boolean autoExpand) {
        String shortcutId = getBubbleShortcutId(bubbleSlotId);
        Person sessionPerson = buildSessionPerson(shortcutId, sessionLabel);
        Icon sessionIcon = null;
        if (!shouldUseShortcutBackedBubbleMetadata())
            sessionIcon = mSessionShortcutHelper.createSessionIcon(session, sessionLabel, bubbleSlotId);

        PendingIntent contentIntent = PendingIntent.getActivity(mService, notificationId,
            TermuxActivity.newInstance(mService, session.mHandle), getContentPendingIntentFlags());

        CharSequence previewText = getBubblePreviewText(session);
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(mService,
            TermuxConstants.TERMUX_APP_SESSION_BUBBLE_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_HIGH,
            sessionLabel, previewText, previewText, contentIntent, null,
            NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) return null;

        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setCategory(Notification.CATEGORY_MESSAGE);
        builder.setOnlyAlertOnce(true);
        builder.setShortcutId(shortcutId);
        builder.addPerson(sessionPerson);
        builder.setStyle(buildMessagingStyle(session, sessionPerson, sessionLabel));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            builder.setLocusId(new LocusId(shortcutId));

        builder.setBubbleMetadata(buildBubbleMetadata(session, notificationId, sessionIcon, autoExpand, shortcutId));

        return builder.build();
    }

    private boolean shouldSuppressBubbleNotification(@NonNull String sessionHandle) {
        SessionConversationState conversationState = mSessionConversationStates.get(sessionHandle);
        if (conversationState == null) return true;
        return !conversationState.hasUnread();
    }

    @NonNull
    private Notification.MessagingStyle buildMessagingStyle(@NonNull TerminalSession session,
                                                            @NonNull Person sessionPerson,
                                                            @NonNull String sessionLabel) {
        Notification.MessagingStyle messagingStyle = new Notification.MessagingStyle(sessionPerson)
            .setConversationTitle(sessionLabel);

        SessionConversationState conversationState = ensureConversationState(session.mHandle);
        if (!conversationState.hasProtocolMessages()) {
            messagingStyle.addMessage(getSessionSubtitle(session), conversationState.getCreatedAt(), sessionPerson);
            return messagingStyle;
        }

        for (BubbleMessage protocolMessage : conversationState.getProtocolMessages())
            messagingStyle.addMessage(protocolMessage.mText, protocolMessage.mTimestamp, sessionPerson);

        return messagingStyle;
    }

    @NonNull
    private Notification.BubbleMetadata buildBubbleMetadata(@NonNull TerminalSession session, int notificationId,
                                                            @Nullable Icon sessionIcon, boolean autoExpand,
                                                            @NonNull String shortcutId) {
        PendingIntent deleteIntent = PendingIntent.getService(mService, notificationId,
            new Intent(mService, TermuxService.class)
                .setAction(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_UNBUBBLE_SESSION)
                .putExtra(TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY.EXTRA_SESSION_HANDLE, session.mHandle),
            getDeletePendingIntentFlags());

        boolean suppressNotification = shouldSuppressBubbleNotification(session.mHandle);

        if (shouldUseShortcutBackedBubbleMetadata()) {
            return new Notification.BubbleMetadata.Builder(shortcutId)
                .setDeleteIntent(deleteIntent)
                .setDesiredHeight(getDesiredBubbleHeight())
                .setAutoExpandBubble(autoExpand)
                .setSuppressNotification(suppressNotification)
                .build();
        }

        if (sessionIcon == null)
            throw new IllegalArgumentException("Session bubble icon is required for PendingIntent bubbles");

        PendingIntent bubbleIntent = PendingIntent.getActivity(mService, notificationId,
            BubbleSessionActivity.newBubbleInstance(mService, session.mHandle), getBubblePendingIntentFlags());

        return new Notification.BubbleMetadata.Builder(bubbleIntent, sessionIcon)
            .setDeleteIntent(deleteIntent)
            .setDesiredHeight(getDesiredBubbleHeight())
            .setAutoExpandBubble(autoExpand)
            .setSuppressNotification(suppressNotification)
            .build();
    }

    @NonNull
    private SessionConversationState ensureConversationState(@NonNull String sessionHandle) {
        SessionConversationState conversationState = mSessionConversationStates.get(sessionHandle);
        if (conversationState != null) return conversationState;

        SessionConversationState newConversationState = new SessionConversationState();
        mSessionConversationStates.put(sessionHandle, newConversationState);
        return newConversationState;
    }

    @NonNull
    private CharSequence getBubblePreviewText(@NonNull TerminalSession session) {
        SessionConversationState conversationState = mSessionConversationStates.get(session.mHandle);
        if (conversationState == null) return getSessionSubtitle(session);

        BubbleMessage latestProtocolMessage = conversationState.getLatestProtocolMessage();
        if (latestProtocolMessage == null) return getSessionSubtitle(session);

        return latestProtocolMessage.mText;
    }

    private int getOrCreateNotificationId(@NonNull String sessionHandle, int bubbleSlotId) {
        Integer notificationId = mSessionNotificationIds.get(sessionHandle);
        if (notificationId != null) return notificationId;

        int nextNotificationId = TermuxConstants.TERMUX_APP_SESSION_BUBBLE_NOTIFICATION_ID_BASE + bubbleSlotId;
        mSessionNotificationIds.put(sessionHandle, nextNotificationId);
        return nextNotificationId;
    }

    private int getBubbleSlotId(@NonNull TerminalSession session) {
        return mService.getBubbleSlotId(session);
    }

    @NonNull
    public String getBubbleShortcutId(int bubbleSlotId) {
        if (bubbleSlotId < 1)
            throw new IllegalArgumentException("Invalid bubble slot id: " + bubbleSlotId);

        return TermuxConstants.TERMUX_APP_SESSION_BUBBLE_SHORTCUT_ID_PREFIX + bubbleSlotId;
    }

    @NonNull
    private Person buildSessionPerson(@NonNull String shortcutId, @NonNull String sessionLabel) {
        return new Person.Builder()
            .setName(sessionLabel)
            .setKey(shortcutId)
            .setImportant(true)
            .build();
    }

    @NonNull
    private CharSequence getSessionSubtitle(@NonNull TerminalSession session) {
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) return title;
        return mService.getString(R.string.notification_text_terminal_session);
    }

    @Nullable
    private String buildProtocolMessage(@Nullable String title, @Nullable String body) {
        String normalizedTitle = normalizeText(title);
        String normalizedBody = normalizeText(body);
        if (normalizedTitle == null) return normalizedBody;
        if (normalizedBody == null) return normalizedTitle;
        if (TextUtils.equals(normalizedTitle, normalizedBody)) return normalizedTitle;
        return normalizedTitle + "\n" + normalizedBody;
    }

    @Nullable
    private String normalizeText(@Nullable String text) {
        if (text == null) return null;

        String trimmedText = text.trim();
        if (trimmedText.isEmpty()) return null;
        return trimmedText;
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
