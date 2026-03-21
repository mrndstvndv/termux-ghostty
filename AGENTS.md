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
