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
-   [Nix](https://nixos.org/) with flakes enabled (or use `nix develop` from the flake).
-   All toolchain (JDK 17, Android SDK/NDK, Zig) is provided by the Nix dev shell.

### Key Commands
-   **Build Debug APK**: `nix develop --command ./gradlew assembleDebug`
-   **Run Unit Tests**: `nix develop --command ./gradlew test` (Uses Robolectric for Android logic tests).
-   **Clean Build**: `nix develop --command ./gradlew clean`

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
-   **Format**: Use Conventional Commits so the release changelog tooling can parse them. Format: `type(scope): description`.
-   **Types**: `feat` (features), `fix` (bug fixes), `update` (changes), `ui` (UI changes), `refactor`, `perf` (performance).
-   **Scope**: Optional. E.g. `feat(bubble): add unread tracking`.
-   **Breaking changes**: Append `!` before `:` (e.g. `feat!: drop Android 5 support`) or include `BREAKING CHANGE:` in the commit body.
-   **Skipped** (not in changelog): `ci`, `chore`, `doc`, `Merge`, `Revert`.
-   **Style**: Use sentence case for the description. Keep it to one line.

### Versioning
-   Follows [Semantic Versioning 2.0.0](https://semver.org/).
-   Version names in `build.gradle` must strictly follow the `major.minor.patch` format.
-   The `publish-release.yml` workflow auto-bumps version based on commit types and creates a GitHub release.

## Key Files
-   `README.md`: High-level user and contributor information.
-   `termux-shared/.../TermuxConstants.java`: Central source of truth for paths, package names, and Intent extras.
-   `app/build.gradle`: Main build configuration, including bootstrap downloading logic.
-   `terminal-emulator/src/main/jni/termux.c`: Core native terminal logic.
