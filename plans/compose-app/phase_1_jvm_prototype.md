# Detailed Plan: Phase 1 — JVM-Based Prototype

This document outlines the detailed action items for **Phase 1**, focusing on configuring the Android `:compose-app` module under package `com.mrndtvndv.term`, implementing the domain interfaces, and building a prototype utilizing a JVM-based SSH client (SSHJ).

---

## 1. Gradle & Multi-Module Setup

To integrate Kotlin and Compose without breaking the existing modules, we must configure both the root build file and the new module's Gradle configurations.

### 1.1 Root `build.gradle` Modifications
Add the Kotlin Gradle Plugin classpath and configure a global `resolutionStrategy` to resolve Guava conflicts (forcing the `-android` flavor and preventing JRE-specific crashes) and packaging configurations.

[build.gradle](file:///Volumes/realme/Dev/termux-ghostty/build.gradle):
```groovy
buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath "com.android.tools.build:gradle:8.13.2"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24"
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" }
    }
    
    // Resolve global dependency conflicts
    configurations.all {
        resolutionStrategy {
            // Force single version of Guava Android variant to prevent JVM/Android API mismatch
            force 'com.google.guava:guava:33.0.0-android'
            
            // Handle Conscrypt/Bouncy Castle version conflicts
            force 'org.bouncycastle:bcprov-jdk18on:1.78'
            force 'org.bouncycastle:bcpkix-jdk18on:1.78'
        }
    }
}
```

### 1.2 Module-Level `:compose-app/build.gradle` Setup
Create `:compose-app/build.gradle` using the same SDK configurations defined in `gradle.properties`:

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.mrndtvndv.term'
    compileSdk project.properties.compileSdkVersion.toInteger()

    defaultConfig {
        applicationId "com.mrndtvndv.term"
        minSdk project.properties.minSdkVersion.toInteger()
        targetSdk project.properties.targetSdkVersion.toInteger()
        versionCode 1
        versionName "1.0.0"
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += ["-opt-in=kotlin.RequiresOptIn"]
    }

    buildFeatures {
        compose true
    }

    composeOptions {
        kotlinCompilerExtensionVersion '1.5.14' // Matches Kotlin 1.9.24
    }

    packaging {
        resources {
            // Exclude signature files from Bouncy Castle to avoid packaging/signing errors
            excludes += [
                'META-INF/INDEX.LIST',
                'META-INF/DEPENDENCIES',
                'META-INF/LICENSE',
                'META-INF/LICENSE.txt',
                'META-INF/NOTICE',
                'META-INF/NOTICE.txt',
                'META-INF/ASL2.0',
                'META-INF/*.kotlin_module'
            ]
        }
    }
}

dependencies {
    implementation project(':terminal-view')
    implementation project(':terminal-emulator')
    implementation project(':termux-shared')

    // Compose & Activity-Compose
    def composeBom = platform('androidx.compose:compose-bom:2024.06.00')
    implementation composeBom
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.activity:activity-compose:1.9.0'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2'
    implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.8.2'

    // JVM SSH Library
    implementation 'com.hierynomus:sshj:0.39.0'
    
    // Conscrypt for faster & secure cryptographic operations on Android
    implementation 'org.conscrypt:conscrypt-android:2.5.2'
}
```

Add the module in [settings.gradle](file:///Volumes/realme/Dev/termux-ghostty/settings.gradle):
```groovy
include ':app', ':termux-shared', ':terminal-emulator', ':terminal-view', ':compose-app'
```

---

## 2. Define Core Domain Contracts (Interfaces)

Create these interfaces and data models under `com.mrndtvndv.term.domain`:

### `SshConfig` & `SshAuth`
```kotlin
data class SshConfig(
    val host: String,
    val port: Int,
    val username: String,
    val connectionTimeoutMs: Int = 10000
)

sealed interface SshAuth {
    data class Password(val password: CharArray) : SshAuth
    data class PublicKey(val privateKeyPem: String, val passphrase: CharArray? = null) : SshAuth
}
```

### `SshSession`
```kotlin
import kotlinx.coroutines.flow.StateFlow

interface SshSession {
    val isConnected: StateFlow<Boolean>
    suspend fun connect(config: SshConfig)
    suspend fun authenticate(auth: SshAuth)
    suspend fun openShellChannel(termType: String, cols: Int, rows: Int): SshShellChannel
    suspend fun openSftpClient(): SftpClient
    fun disconnect()
}
```

### `SshShellChannel`
```kotlin
import java.io.InputStream
import java.io.OutputStream

interface SshShellChannel {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int)
    fun close()
}
```

### `SftpFile` & `SftpClient`
```kotlin
import java.io.File

data class SftpFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: Int,
    val modifiedTime: Long
)

interface SftpClient {
    suspend fun listFiles(path: String): List<SftpFile>
    suspend fun createDirectory(path: String)
    suspend fun deleteFile(path: String)
    suspend fun downloadFile(remotePath: String, destination: File, onProgress: (Long) -> Unit)
    suspend fun uploadFile(source: File, remotePath: String, onProgress: (Long) -> Unit)
    fun close()
}
```

---

## 3. Extending `TerminalSession` for Custom I/O

To avoid spawning a local dummy PTY process on Android, we must modify the Java-based `TerminalSession` in `:terminal-emulator` to support direct custom stream piping.

### 3.1 Custom I/O Interface
Add this interface in `com.termux.terminal`:
```java
public interface TerminalSessionIO {
    void write(byte[] data, int offset, int count);
    void onResize(int columns, int rows, int cellWidth, int cellHeight);
    void onClose();
}
```

### 3.2 `TerminalSession` Code Changes
In [TerminalSession.java](file:///Volumes/realme/Dev/termux-ghostty/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java):
1. Add fields:
   ```java
   private TerminalSessionIO mIoHandler;
   private boolean mIsCustomIO = false;
   ```
2. Add a new constructor:
   ```java
   public TerminalSession(Integer transcriptRows, TerminalSessionClient client, TerminalSessionIO ioHandler) {
       this.mTranscriptRows = transcriptRows;
       this.mClient = client;
       this.mIoHandler = ioHandler;
       this.mIsCustomIO = true;
   }
   ```
3. Expose output injection:
   ```java
   public void appendOutput(byte[] data, int offset, int count) {
       if (mProcessToTerminalIOQueue.write(data, offset, count)) {
           if (mGhosttySessionWorker != null) {
               mGhosttySessionWorker.onOutputAvailable();
           }
       }
   }
   ```
4. Guard native subprocess creation in `initializeTerminalBackend`:
   ```java
   if (mIsCustomIO) {
       // Only setup the local Ghostty emulator worker
       mGhosttyTerminalContent = new GhosttyTerminalContent(columns, rows, resolveTranscriptRows(), cellWidthPixels, cellHeightPixels);
       mGhosttySessionWorker = new GhosttySessionWorker(this, mGhosttyTerminalContent, mProcessToTerminalIOQueue, mMainThreadHandler, cellWidthPixels, cellHeightPixels);
       mGhosttySessionWorker.start();
       mShellPid = 1; // Dummy positive PID to pass isRunning() check
       return;
   }
   ```
5. Route keypress events inside `write(byte[] data, int offset, int count)`:
   ```java
   @Override
   public void write(byte[] data, int offset, int count) {
       if (mIsCustomIO) {
           if (mIoHandler != null) mIoHandler.write(data, offset, count);
       } else if (mShellPid > 0) {
           mTerminalToProcessIOQueue.write(data, offset, count);
       }
   }
   ```
6. Route resize events inside `updateSize(...)`:
   ```java
   if (mIsCustomIO) {
       this.mColumns = columns;
       this.mRows = rows;
       this.mCellWidthPixels = cellWidthPixels;
       this.mCellHeightPixels = cellHeightPixels;
       if (mGhosttySessionWorker != null) {
           mGhosttySessionWorker.resize(columns, rows, cellWidthPixels, cellHeightPixels);
       }
       if (mIoHandler != null) {
           mIoHandler.onResize(columns, rows, cellWidthPixels, cellHeightPixels);
       }
       return;
   }
   ```
7. Guard resource cleanups in `cleanupResources`:
   - Only call `JNI.close(mTerminalFileDescriptor)` if `!mIsCustomIO`.

---

## 4. Implement JVM Wrapper Classes (SSHJ Integration)

Implement these classes under `com.mrndtvndv.term.data.ssh.jvm`:

1. **Security Provider Initialization:** Before connecting, initialize Conscrypt to ensure modern cipher suite compatibility and maximum cryptographic performance:
   ```kotlin
   import org.conscrypt.Conscrypt
   import java.security.Security
   
   Security.insertProviderAt(Conscrypt.newProvider(), 1)
   ```
2. **`JvmSshSession`:** Wraps `net.schmizz.sshj.SSHClient`.
3. **`JvmSshShellChannel`:** Wraps `net.schmizz.sshj.connection.channel.direct.Session.Shell`. Implements `resizeWindow` using `Shell.changeWindowDimensions(cols, rows, widthPx, heightPx)`.
4. **`JvmSftpClient`:** Wraps `net.schmizz.sshj.sftp.SFTPClient`. Implements file listing mapped to `SftpFile`.

---

## 5. Shell Integration, Piping & Interop Considerations

To pipe SSH traffic to the emulator:
1. When the shell channel is opened, instantiate `TerminalSession` using the custom I/O constructor.
2. In the `TerminalSessionIO` implementation:
   - `write(data)` maps to writing to the `SshShellChannel.outputStream`.
   - `onResize(cols, rows, wPx, hPx)` maps to calling `SshShellChannel.resizeWindow(cols, rows, wPx, hPx)`.
3. Launch a background coroutine (on `Dispatchers.IO`) to read from `SshShellChannel.inputStream` and forward bytes to `TerminalSession.appendOutput`.

### Java-Kotlin Interop & Threading Rules:
* **Checked Exceptions:** SSHJ and JVM streams throw standard checked exceptions (e.g. `IOException`, `SSHException`). Since Kotlin does not enforce checked exceptions at compile-time, wrap all network calls in `runCatching` or `try-catch` blocks to prevent unexpected crashes.
* **Platform Types:** APIs inside `:terminal-emulator` (Java) return platform types. Kotlin code should explicitly declare types or perform null-checks when consuming values like `TerminalSession.getCwd()` or `TerminalSession.getTitle()`.
* **Threading:** Run all SSH network interactions on `Dispatchers.IO`. Do not perform socket I/O on `Dispatchers.Main`. Any UI calls or Toast alerts must be posted back to `Dispatchers.Main`.
* **SAM Interface Implementations:** Since `TerminalSessionClient` has more than 15 methods, subclass `TermuxTerminalSessionClientBase` in Kotlin to avoid verbose inline object overrides.

---

## 6. Phase 1 Verification Checklist
* [ ] Gradle sync completes successfully.
* [ ] `:compose-app` compiles and builds an APK.
* [ ] Establishing a test SSH connection does not crash the app.
* [ ] Character inputs typed into `TerminalView` are sent over the SSH channel.
* [ ] Remote server outputs render correctly inside `TerminalView`.
* [ ] Rotating screen or changing window size updates the remote shell dimensions.
