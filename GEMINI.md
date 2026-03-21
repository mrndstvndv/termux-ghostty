# Termux Project Overview

Termux is an Android terminal emulator and Linux environment. This repository contains the core application and its internal libraries.

## Project Structure

The project is organized as a Gradle monorepo with the following subprojects:

-   **`:app`**: The main Android application module. It manages terminal sessions, the user interface (Drawer, Settings), and integration with other Termux plugins.
-   **`:terminal-emulator`**: Implements the terminal emulation logic (VT100, XTerm sequences). It includes a native component (`libtermux`) written in C for performance.
-   **`:terminal-view`**: A custom Android `View` that renders the terminal screen and handles user input (gestures, soft keyboard).
-   **`:termux-shared`**: A foundational library containing shared constants, utility classes, and common logic used across Termux and its plugin ecosystem (Termux:API, Termux:Tasker, etc.).

## Building and Running

### Prerequisites
-   Android SDK and NDK (configured in `local.properties` or environment variables).
-   Java 11 or higher.

### Key Commands
-   **Build Debug APK**: `./gradlew assembleDebug`
-   **Run Unit Tests**: `./gradlew test` (Uses Robolectric for Android logic tests).
-   **Clean Build**: `./gradlew clean`

### Build Configuration
-   **Bootstrap**: The build process automatically downloads minimal Linux environments (bootstrap zips).
-   **Package Variants**: Supported variants are `apt-android-7` (default) and `apt-android-5`. This can be overridden with the `TERMUX_PACKAGE_VARIANT` environment variable.
-   **Native Build**: Gradle uses `ndk-build` to compile C code in `app/src/main/cpp` and `terminal-emulator/src/main/jni`.

## Development Conventions

### Coding Standards
-   **No Hardcoding**: Never hardcode paths like `/data/data/com.termux/files/usr`. Always use constants from `com.termux.shared.termux.TermuxConstants`.
-   **Shared Logic**: Utilities or constants that could benefit Termux plugins should be placed in the `:termux-shared` module.
-   **Compatibility**: Ensure changes in `:termux-shared` or `:terminal-view` do not break compatibility with plugin apps.

### Commit Guidelines
-   **Format**: Use semantic commit subjects in the exact format `Type: Summary` so changelog tooling can parse them.
-   **Types**: Use `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`.
-   **Style**: Use sentence case for the summary and keep it to one line (e.g., `Removed: Legacy Java terminal backend`).
-   **Avoid**: Do not use lowercase Conventional Commit prefixes like `feat:` or `fix:` in this repository.

### Versioning
-   Follows [Semantic Versioning 2.0.0](https://semver.org/).
-   Version names in `build.gradle` must strictly follow the `major.minor.patch` format.

## Key Files
-   `README.md`: High-level user and contributor information.
-   `termux-shared/.../TermuxConstants.java`: Central source of truth for paths, package names, and Intent extras.
-   `app/build.gradle`: Main build configuration, including bootstrap downloading logic.
-   `terminal-emulator/src/main/jni/termux.c`: Core native terminal logic.
