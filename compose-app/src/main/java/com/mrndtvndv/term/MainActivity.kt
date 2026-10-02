package com.mrndtvndv.term

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrndtvndv.term.clipboard.FileUploadService
import com.mrndtvndv.term.clipboard.UploadController
import com.mrndtvndv.term.clipboard.UploadTarget
import com.mrndtvndv.term.navigation.AppRoute
import com.mrndtvndv.term.server.AppSessionManager
import com.mrndtvndv.term.server.SessionHost
import com.mrndtvndv.term.ui.TermApp
import com.mrndtvndv.term.ui.notification.NotificationState
import com.termux.shared.interact.ShareUtils
import com.termux.terminal.TerminalSession

class MainActivity : ComponentActivity(), SessionHost {

    private val container by lazy { (application as TermApplication).container }
    private val sessionManager: AppSessionManager get() = container.sessionManager

    private val viewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer { AppViewModel(sessionManager, NotificationState(), container.lastSessionStore) }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uploads.onFilePicked(uri) }
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uploads.onFilePicked(uri) }

    private val uploads: UploadController by lazy {
        UploadController(
            context = this,
            scope = lifecycleScope,
            fileUploadService = FileUploadService(applicationContext),
            resolveTarget = ::resolveUploadTarget,
            pickMedia = {
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            pickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            showToast = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() },
        )
    }

    private var windowHasFocus = false
    private var focusedTerminalSession: TerminalSession? = null

    override fun onFrameAvailable(session: TerminalSession) {
        // Backend now observes TerminalSession.FrameCallback directly; no host routing required.
    }

    override fun copyToClipboard(text: String) {
        ShareUtils.copyTextToClipboard(this, text)
    }

    override fun handlePaste(session: TerminalSession?) = uploads.handlePaste(session)

    override fun isAtLeast(state: Lifecycle.State): Boolean =
        lifecycle.currentState.isAtLeast(state)

    override fun showInAppNotification(title: String?, body: String?, serverId: String?) {
        viewModel.notificationState.post(title, body, serverId)
    }

    private fun resolveUploadTarget(session: TerminalSession?): UploadTarget {
        val target = session ?: focusedTerminalSession
        val serverId = target?.let(sessionManager::serverIdForSession)
        val server = serverId?.let(sessionManager.coordinator::getServer)
        val config = server?.config ?: serverId?.let(sessionManager.serverRepository::get)
        return UploadTarget(target, server, config)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        sessionManager.setHost(this)
        requestMissingPermissions()

        setContent {
            TermApp(
                appViewModel = viewModel,
                container = container,
                uploads = uploads,
                onActiveTerminalSessionChanged = ::updateFocusedTerminalSession,
                onOpenUrl = { url -> ShareUtils.openUrl(this, url) },
            )
        }

        handleNotificationTap(intent)
    }

    override fun onDestroy() {
        updateFocusedTerminalSession(null)
        sessionManager.setHost(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowHasFocus = hasFocus
        focusedTerminalSession?.sendTerminalFocus(hasFocus)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationTap(intent)
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestIfDenied(POST_NOTIFICATIONS, 101)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) requestIfDenied(WRITE_EXTERNAL_STORAGE, 102)
    }

    private fun requestIfDenied(permission: String, requestCode: Int) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), requestCode)
        }
    }

    private fun updateFocusedTerminalSession(session: TerminalSession?) {
        if (focusedTerminalSession === session) return

        focusedTerminalSession?.sendTerminalFocus(false)
        focusedTerminalSession = session
        session?.sendTerminalFocus(windowHasFocus)
    }

    private fun handleNotificationTap(intent: Intent?) {
        val serverId = intent?.getStringExtra(AppSessionManager.EXTRA_NOTIFICATION_SERVER_ID) ?: return
        intent.removeExtra(AppSessionManager.EXTRA_NOTIFICATION_SERVER_ID)
        val title = intent.getStringExtra(AppSessionManager.EXTRA_NOTIFICATION_TITLE)
        intent.removeExtra(AppSessionManager.EXTRA_NOTIFICATION_TITLE)
        val body = intent.getStringExtra(AppSessionManager.EXTRA_NOTIFICATION_BODY)
        intent.removeExtra(AppSessionManager.EXTRA_NOTIFICATION_BODY)
        viewModel.focusTerminalNotification(serverId, body, title)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            // Only intercept when on the terminal workspace screen
            if (viewModel.navigator.backStack.lastOrNull() is AppRoute.Workspace) {
                // Hide keyboard BEFORE the IME can consume the event via onKeyPreIme.
                // This way the back event propagates through to OnBackPressedDispatcher
                // and navigator.goBack() fires on the SAME press.
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
