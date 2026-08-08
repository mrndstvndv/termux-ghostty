# Repository Instructions

## Commit format
- **Never commit unless the user explicitly asks.** Do not stage or commit changes on your own initiative.
- Use Conventional Commits so the release changelog tooling can parse them.
- Required format: `type(scope): description`
- Allowed types: `feat`, `fix`, `update`, `ui`, `refactor`, `perf`
- Scope is optional, e.g. `feat(bubble): add unread tracking`
- Use sentence case for the description. Keep it to one line.
- Breaking changes: append `!` before `:`, e.g. `feat!: remove legacy backend`, or include `BREAKING CHANGE:` in the commit body.
- Skipped (not in changelog): `ci`, `chore`, `doc`, `Merge`, `Revert`

### Version bump rules
- `feat` → minor bump (0.X.0)
- `fix`, `update`, `ui`, `refactor`, `perf` → patch bump (0.0.X)
- Breaking change (`!`) → major bump (X.0.0)

## Examples
- `feat(bubble): add unread session tracking`
- `fix(terminal): crash on empty input`
- `ui(settings): update theme picker layout`
- `perf(emulator): reduce frame allocation overhead`
- `feat!: drop Android 5 support`

## Documentation

All fork-specific docs go under `docs/fork/`. Do not add `.md` files to the repo root.

- `docs/fork/` — research, comparisons, guides, architecture docs
- `docs/fork/plans/` — implementation plans, checklists, stabilization docs

**Note on plans:** Do NOT commit plan files (`.md` files outlining implementation steps/checklists) or updates to plans to the Git repository. Keep plan documents untracked or locally edited only. Do not stage them for commits.

## Testing workflow

- **Unit tests: run them.** Fast, local, zero device time — run proactively after code changes (e.g. `./gradlew :compose-app:testUniversalDebugUnitTest`) if a test suite covers the change.
- **Live/device testing: never.** Do not install builds, launch emulators, connect devices, or run manual validation — that burns tokens and time. Leave manual validation to the user unless they explicitly ask otherwise.

## Linting & Static Analysis

Two Kotlin linters are configured in `compose-app/build.gradle` (the only Kotlin module):

| Tool | Purpose | Command |
|---|---|---|
| **ktlint** | Formatting & style (indentation, spacing, naming) | `./gradlew ktlintCheck` (check), `./gradlew ktlintFormat` (auto-fix) |
| **detekt** | Static analysis (complexity, bugs, smells) | `./gradlew detekt` |
| **Android Lint** | XML, manifest, perf, accessibility | `./gradlew lint` |

**Quick combo:** `./gradlew ktlintFormat detekt` — fix style then run analysis.

**Required after any code changes:** Run `./gradlew ktlintFormat detekt` before committing. ktlint auto-fixes formatting; detekt catches code smells. Both must pass.

Config files:
- `.editorconfig` — ktlint rules (shared with EditorConfig)
- `config/detekt/detekt.yml` — detekt rules

## Code Hygiene

- **No legacy shims or migration code unless explicitly requested.** Do not add backward-compat fallbacks, legacy preference keys, deprecated-API wrappers, or one-off data migrations to support old app versions.
- If existing legacy code is found (e.g. dual-writes to old preference keys, migration fallbacks), prefer removing it outright. If user data is at stake, do a one-time conversion that deletes the legacy key afterwards — never keep it alive.

## UI Hygiene

- Before adding a UI element, indicator, icon, label, or status affordance, check the surrounding UI for an existing equivalent. Avoid redundant elements and duplicate labels or indicators; prefer one clear source of feedback.

## Core Behaviors to Preserve

### SFTP & Review Workspace Tracking (Herdr Integration)
- The app integrates with `herdr` to persist navigation state per workspace for the SFTP and Review tabs.
- When tapping the SFTP or Review tabs, the app queries `herdr workspace list` and `herdr pane list`.
- **If the workspace has NOT changed:** The app MUST NOT overwrite `workspaceDirState.value`. It must respect the user's manual navigation state (which is stored in `SharedPreferences` under `sftp_last_dir_<workspace_key>`).
- **If the workspace HAS changed:** The app must update the active workspace key, load the saved directory for the *new* workspace (or default to the active pane's `cwd`), and update the SFTP/Review panels to point to this new location.
