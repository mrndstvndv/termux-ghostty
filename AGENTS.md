# Repository Instructions

## Commit format
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

## Core Behaviors to Preserve

### SFTP & Review Workspace Tracking (Herdr Integration)
- The app integrates with `herdr` to persist navigation state per workspace for the SFTP and Review tabs.
- When tapping the SFTP or Review tabs, the app queries `herdr workspace list` and `herdr pane list`.
- **If the workspace has NOT changed:** The app MUST NOT overwrite `workspaceDirState.value`. It must respect the user's manual navigation state (which is stored in `SharedPreferences` under `sftp_last_dir_<workspace_key>`).
- **If the workspace HAS changed:** The app must update the active workspace key, load the saved directory for the *new* workspace (or default to the active pane's `cwd`), and update the SFTP/Review panels to point to this new location.
