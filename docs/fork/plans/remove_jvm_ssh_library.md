# Implementation Plan: Remove JVM SSH Library and SFTP Explorer

This plan details the steps required to remove the `sshj` JVM SSH library dependency, clean up the duplicate fallback connection layer in `NativeSshSession`, and remove the SFTP file explorer UI from the workspace to focus on a unified, high-performance native terminal experience.

---

## 1. Clean up Domain Interfaces and View Models

- **Remove SFTP Client & File Model**:
  - Delete `com.mrndtvndv.term.domain.SftpClient` ([SftpClient.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/domain/SftpClient.kt)).
  - Remove SFTP client mock helper classes from [MockSshSession.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/domain/MockSshSession.kt).
- **Remove SftpViewModel & Sftp UI Components**:
  - Delete the whole `com.mrndtvndv.term.ui.sftp` package, including:
    - [SftpViewModel.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/ui/sftp/SftpViewModel.kt)
    - [SftpFileBrowser.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/ui/sftp/SftpFileBrowser.kt)

## 2. Refactor Workspace UI to Terminal-Only View

- **Simplify TerminalWorkspaceScreen**:
  - Edit [TerminalWorkspaceScreen.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/ui/workspace/TerminalWorkspaceScreen.kt).
  - Remove `SftpViewModel` argument from `TerminalWorkspaceScreen`.
  - Remove `SplitWorkspace`, `TabbedWorkspace`, `HorizontalPager`, and the tab headers.
  - Make `TerminalWorkspaceScreen` render a single `TerminalFocusWrapper` with an optional `ExtraKeysToolbar`.

## 3. Remove JVM SSH Classes and Dependencies

- **Remove JVM SSH Package**:
  - Delete the entire package `com.mrndtvndv.term.data.ssh.jvm`, containing `JvmSshSession`, `JvmSshShellChannel`, and `JvmSftpClient`.
- **Remove Gradle Dependency**:
  - Open [build.gradle](file:///Volumes/realme/Dev/termux-ghostty/compose-app/build.gradle).
  - Remove `implementation 'com.hierynomus:sshj:0.39.0'`.
- **Remove Proguard Rules**:
  - Open [proguard-rules.pro](file:///Volumes/realme/Dev/termux-ghostty/compose-app/proguard-rules.pro) and remove the net.schmizz.sshj rule-set.

## 4. Refactor Native SSH Session

- **Clean up SshSession Interface**:
  - Remove `openSftpClient` from [SshSession.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/domain/SshSession.kt).
- **Refactor NativeSshSession**:
  - Edit [NativeSshSession.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/data/ssh/native/NativeSshSession.kt).
  - Remove the private `jvmFallback` instance.
  - Simplify `connect(config)` to only establish the raw socket connection.
  - Simplify `authenticate(auth)` to immediately update the connected state.
  - Simplify `disconnect()` to close only the socket and remove `jvmFallback.disconnect()`.

## 5. Refactor MainActivity

- **Remove SFTP initialization**:
  - Edit [MainActivity.kt](file:///Volumes/realme/Dev/termux-ghostty/compose-app/src/main/java/com/mrndtvndv/term/MainActivity.kt).
  - Remove `sftpClient` and `sftpViewModelState` variables.
  - Remove `SftpViewModel` instantiation, cleanups, and parameter passing.
  - Remove the unused `useNativePiping` settings UI checkbox, preference loading, and fallbacks. The app will now exclusively use the high-performance `NativeSshSession`.

---

## Verification Checklist

- [ ] Gradle build succeeds and compiles successfully.
- [ ] No compilation warnings/errors about missing imports in `MainActivity` or UI layer.
- [ ] SSH terminal connection starts up instantly and runs fully on the native `libssh2` backend.
- [ ] Exiting interactive session successfully cleans up native worker thread & JNI socket, returning back to Dashboard.
