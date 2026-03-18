package com.termux.app.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxService;
import com.termux.app.bubbles.BubbleTerminalSessionClient;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

public final class TermuxTerminalSessionClientDispatcher extends TermuxTerminalSessionClientBase {

    private final TermuxService mService;
    private final TermuxTerminalSessionServiceClient mServiceClient;
    private final List<TerminalSessionClient> mRegisteredClients = new ArrayList<>();

    public TermuxTerminalSessionClientDispatcher(@NonNull TermuxService service,
                                                 @NonNull TermuxTerminalSessionServiceClient serviceClient) {
        mService = service;
        mServiceClient = serviceClient;
    }

    public synchronized void registerClient(@NonNull TerminalSessionClient client) {
        if (client == this) return;
        if (mRegisteredClients.contains(client)) return;
        mRegisteredClients.add(client);
    }

    public synchronized void unregisterClient(@Nullable TerminalSessionClient client) {
        if (client == null) return;
        mRegisteredClients.remove(client);
    }

    @NonNull
    private synchronized List<TerminalSessionClient> getRegisteredClientsSnapshot() {
        return new ArrayList<>(mRegisteredClients);
    }

    private boolean isSessionFocused(@NonNull TerminalSession session, @NonNull List<TerminalSessionClient> clients) {
        for (TerminalSessionClient client : clients) {
            if (client instanceof TermuxTerminalSessionActivityClient
                && ((TermuxTerminalSessionActivityClient) client).isSessionFocused(session)) {
                return true;
            }

            if (client instanceof BubbleTerminalSessionClient
                && ((BubbleTerminalSessionClient) client).isSessionFocused(session)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onTextChanged(changedSession);
    }

    @Override
    public void onFrameAvailable(@NonNull TerminalSession changedSession) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onFrameAvailable(changedSession);
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        mService.onTerminalSessionTitleChanged(updatedSession);
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onTitleChanged(updatedSession);
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        mService.onTerminalSessionFinished(finishedSession);
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onSessionFinished(finishedSession);
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onCopyTextToClipboard(session, text);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onPasteTextFromClipboard(session);
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onBell(session);
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onColorsChanged(changedSession);
    }

    @Override
    public void onTerminalProtocolNotification(@NonNull TerminalSession session, @Nullable String title, @Nullable String body) {
        List<TerminalSessionClient> clients = getRegisteredClientsSnapshot();
        if (!isSessionFocused(session, clients))
            mServiceClient.onTerminalProtocolNotification(session, title, body);

        for (TerminalSessionClient client : clients)
            client.onTerminalProtocolNotification(session, title, body);
    }

    @Override
    public void onTerminalProgressChanged(@NonNull TerminalSession session) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onTerminalProgressChanged(session);
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.onTerminalCursorStateChange(state);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        mServiceClient.setTerminalShellPid(session, pid);
        for (TerminalSessionClient client : getRegisteredClientsSnapshot())
            client.setTerminalShellPid(session, pid);
    }

    @Override
    public Integer getTerminalCursorStyle() {
        for (TerminalSessionClient client : getRegisteredClientsSnapshot()) {
            Integer cursorStyle = client.getTerminalCursorStyle();
            if (cursorStyle != null) return cursorStyle;
        }

        return null;
    }

}
