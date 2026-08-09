# Material 3 research — terminal-session progress indicator

**Scope:** Thin, top-edge, non-interactive terminal-session status indicator in the Compose app. Research and implementation policy only; no application code.

**Retrieved:** 2026-08-09. Sources are official Material, Android Developers, or AndroidX documentation/source.

## Conclusion

**Recommendation:** render the standard Material 3 `LinearProgressIndicator` as session-local terminal chrome while Ghostty reports active progress. Use the determinate overload only for a supplied authoritative fraction; use the indeterminate overload for Ghostty's explicit `indeterminate` state or an active state without a fraction. Keep it non-interactive and remove it on protocol removal, stale timeout, reset, cancellation, failure, or session disposal.

A **4dp full-width top-edge strip** is the token-faithful implementation. Add it to the terminal-page layout only while progress is active, so terminal cells never sit beneath the indicator and inactive sessions retain their full viewport. Its appearance or removal intentionally resizes the terminal. This is a status indicator, not a control. [S1](#s1-material-3-progress-indicators) [S2](#s2-android-developers-progress-indicators) [S5](#s5-androidx-material-3-source) [S6](#s6-androidx-linear-token-source)

## Evidence and implications

### Determinate versus indeterminate

**Documented facts**

- Android Developers defines determinate progress as displaying exactly how much progress has been made; indeterminate progress animates continually without regard to progress. Its Compose guide selects the indeterminate form by omitting `progress`. [S2](#s2-android-developers-progress-indicators)
- The Material 3 API has distinct overloads: `LinearProgressIndicator(progress = { ... })` is determinate; the overload without `progress` is indeterminate. Determinate `progress` spans 0.0 (none) through 1.0 (full), and out-of-range values are coerced. [S3](#s3-linearprogressindicator-api-reference)
- AndroidX implements the indeterminate overload with `rememberInfiniteTransition` and repeating head/tail animations. [S5](#s5-androidx-material-3-source)

**Scoped recommendation**

- Bind the determinate form only to an authoritative terminal-supplied fraction. Do not estimate from elapsed time, output lines, or process liveness; that would not satisfy the documented “exactly how much” meaning. This is a product inference from the definitions above.
- Ghostty's explicit `indeterminate` state represents ongoing work without a completion estimate, so map it to Material's indeterminate form rather than inventing a percentage. Material expressly defines that form for processing without a completion estimate. [S2](#s2-android-developers-progress-indicators)

### Semantics and accessibility

**Documented facts**

- Compose semantics provide component meaning and role to accessibility services, autofill, and testing. Material, Compose UI, and Foundation APIs provide role-appropriate semantics by default. [S7](#s7-compose-semantics-guidance)
- The AndroidX determinate linear indicator installs merged `ProgressBarRangeInfo` semantics for `0f..1f`; the indeterminate overload applies `progressSemantics()`. [S5](#s5-androidx-material-3-source)
- Foundation's `progressSemantics()` supplies the required determinate range semantics (coercing values) or `ProgressBarRangeInfo.Indeterminate`. Its source explains that merged descendants help older TalkBack versions that can ignore range-info nodes otherwise. [S8](#s8-androidx-progress-semantics-source)

**Scoped recommendation**

- Prefer the stock component and preserve its semantics. A custom-drawn 3dp treatment must recreate determinate range semantics (or indeterminate semantics if a future approved design uses it), rather than becoming a visual-only line.
- It is status, not an input surface: add no click, long-click, drag, focus action, or button/slider role. A cancellation action belongs in a separate labelled control with its own accessible touch target.
- Do not add a duplicate generic `contentDescription` to a standard progress bar. If surrounding UI does not identify the operation/session, provide concise contextual text in the same semantic grouping. This is a product accessibility recommendation grounded in the role of semantics. [S7](#s7-compose-semantics-guidance)

### Color and theming

**Documented facts**

- `color` is the active/progress portion and `trackColor` is the track behind it; both are parameters of the Material 3 composable. [S2](#s2-android-developers-progress-indicators) [S3](#s3-linearprogressindicator-api-reference)
- AndroidX `ProgressIndicatorDefaults` draws from progress tokens. The current tokens map the active indicator and stop to `Primary`, and the track to `SecondaryContainer`. [S5](#s5-androidx-material-3-source) [S9](#s9-androidx-progress-indicator-tokens-source)

**Scoped recommendation**

- Derive active and track colors from the active `MaterialTheme.colorScheme`, rather than hard-code a terminal accent. Verify the active/track/background combination in light and dark themes; 3dp leaves little visual area, so the active segment must remain clearly distinguishable. Theme derivation is supported by the token mapping; the contrast check is a product-quality requirement.
- Keep error state separate from normal progress coloring and use the app's established error/status treatment. This is a scoped recommendation.

### Size, placement, and touch guidance

**Documented facts**

- AndroidX applies `LinearIndicatorWidth` × `LinearIndicatorHeight` by default; its source defines the width as 240dp and derives height from `LinearProgressIndicatorTokens.Height`. [S5](#s5-androidx-material-3-source)
- The generated current token file defines both `Height` and `TrackThickness` as **4.0dp**. [S6](#s6-androidx-linear-token-source)
- Android's Compose accessibility guidance requires a 48dp minimum size for every clickable/touchable/interactive element and distinguishes non-interactive components, for which that added touch padding is not included. [S10](#s10-compose-api-defaults-and-touch-targets)

**Scoped recommendation**

- Add a 4dp strip above the terminal canvas only while progress is active, full session width, with no pointer input. It must never overlay terminal cells or become a scrubber/cancel target. Its visibility intentionally changes the terminal canvas height and triggers a terminal resize. As expressly non-interactive, it need not be expanded to a 48dp hit region. [S6](#s6-androidx-linear-token-source) [S10](#s10-compose-api-defaults-and-touch-targets)
- Use the stock Material component whenever Ghostty state maps directly to determinate or indeterminate progress. `pause` and `error` are protocol-specific states, not Material progress variants: retain the supplied fraction when present, use a non-animated paused presentation, and apply the theme's error color only for error. State-only variants without a fraction are contextual status chrome and must not be represented as a false percentage.

### Motion and reduced motion

**Documented facts**

- Android Developers says indicators use motion either to show proximity to completion or to signal processing without a completion estimate. [S2](#s2-android-developers-progress-indicators)
- The determinate Material 3 API/source says there is **no animation by default** between progress values; it offers `ProgressIndicatorDefaults.ProgressAnimationSpec` only when an app elects to animate. [S3](#s3-linearprogressindicator-api-reference) [S5](#s5-androidx-material-3-source)
- The indeterminate source uses an infinite 1,750ms cycle. [S5](#s5-androidx-material-3-source)
- AndroidX `MotionDurationScale` specifies that a `0f` scale ends duration motion on the next frame callback, while larger scales slow it. [S11](#s11-androidx-motion-duration-scale-source)

**Scoped recommendation**

- Apply determinate state changes directly by default; this follows the component default and avoids needless terminal-surface motion. If future animation is approved, it must respect Compose duration scaling and remain usable when scale is zero. Do not create an independent timer that bypasses that policy.
- Use the stock indeterminate animation only for Ghostty's explicit indeterminate state or active progress without a fraction. It already follows Compose motion-duration scaling; do not reproduce it with a custom timer. Pause must not animate.

## Recommended implementation policy

1. Add a session-local 4dp Material 3 strip above the terminal canvas only while Ghostty reports active progress. Terminal cells must never be obscured; visibility changes intentionally resize the terminal to reclaim inactive screen space.
2. Use the determinate overload for a valid authoritative fraction. Use the indeterminate overload for an explicit indeterminate state or active progress with no fraction; never estimate completion.
3. Theme ordinary active progress with Material's stock active/track roles. Handle protocol-specific error and pause states without treating either as a fabricated percentage; preserve their semantics.
4. Preserve `ProgressBarRangeInfo` semantics for determinate progress and indeterminate semantics for the indeterminate form; expose cancellation separately through a correctly sized, labelled control.
5. Do not animate determinate updates by default. Use the stock indeterminate animation rather than a custom timer; pause remains static.
6. Remove the indicator at protocol removal, stale-timeout clearing, terminal reset, session disposal, and terminal operation completion/failure. Stale progress must never read as active work.

## Sources

### S1. Material 3 progress indicators

- URL: <https://m3.material.io/components/progress-indicators/overview>
- Retrieved: 2026-08-09
- Direct evidence: Material Design describes progress indicators as informing users about the status of ongoing processes and showing process status in real time.

### S2. Android Developers — Progress indicators

- URL: <https://developer.android.com/develop/ui/compose/components/progress>
- Retrieved: 2026-08-09
- Direct evidence: Defines determinate/indeterminate and linear/circular forms; documents `progress`, `color`, `trackColor`, and the role of motion.

### S3. AndroidX Material 3 `LinearProgressIndicator` API

- URL: <https://developer.android.com/reference/kotlin/androidx/compose/material3/LinearProgressIndicator.composable>
- Retrieved: 2026-08-09
- Direct evidence: Documents determinate/indeterminate overloads, progress range/coercion, colors/track, default non-animation, and optional recommended animation spec.

### S5. AndroidX Material 3 source

- URL: <https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/ProgressIndicator.kt>
- Retrieved: 2026-08-09
- Direct evidence: Shows built-in progress semantics, layout sizing, color defaults, determinate/indeterminate implementation, 240dp default width, and the 1,750ms indeterminate cycle.

### S6. AndroidX linear-token source

- URL: <https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/LinearProgressIndicatorTokens.kt>
- Retrieved: 2026-08-09
- Direct evidence: Generated current tokens specify 4.0dp for `Height` and `TrackThickness`.

### S7. Android Developers — Compose semantics

- URL: <https://developer.android.com/develop/ui/compose/accessibility/semantics>
- Retrieved: 2026-08-09
- Direct evidence: Defines semantics and their use by accessibility services, autofill, and testing; states that Compose APIs have built-in role-appropriate semantics.

### S8. AndroidX progress-semantics source

- URL: <https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/ProgressSemantics.kt>
- Retrieved: 2026-08-09
- Direct evidence: Implements determinate range and indeterminate progress semantics, including merged descendants for older TalkBack behavior.

### S9. AndroidX progress-indicator token source

- URL: <https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/ProgressIndicatorTokens.kt>
- Retrieved: 2026-08-09
- Direct evidence: Maps active indicator/stop colors to `Primary` and track to `SecondaryContainer`.

### S10. Android Developers — Compose API defaults and touch targets

- URL: <https://developer.android.com/develop/ui/compose/accessibility/api-defaults>
- Retrieved: 2026-08-09
- Direct evidence: Requires 48dp minimum size for interactive on-screen elements and distinguishes non-interactive component behavior.

### S11. AndroidX motion-duration-scale source

- URL: <https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/MotionDurationScale.kt>
- Retrieved: 2026-08-09
- Direct evidence: Defines duration scaling: `0f` ends duration motion on the next frame callback; larger values slow it.
