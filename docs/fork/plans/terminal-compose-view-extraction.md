# Plan: Extract a Reusable Compose Terminal Library

## Outcome and Constraints

Extract the terminal canvas from `compose-app` into a Compose-first Android library that can be used by `compose-app` now and by `termux-app` later. This is infrastructure, not a terminal application and not a replacement for `terminal-view`.

The library must:

- Render terminal frames and provide input, IME, selection, links, scrolling, focus, and accessibility seams without exposing `TerminalView`, `TerminalRenderer`, `TerminalViewLinkLayout`, `TermuxTerminalViewClientBase`, `termux-shared`, or app/workspace classes.
- Accept consumer-defined shader definitions and cursor effects. It must contain no AGSL source, cursor preset, effect enum, default preference, or built-in visual design.
- Use a backend contract so the first Ghostty/`TerminalSession` integration is replaceable. A backend adapter may be app-owned or a separate integration artifact.
- Avoid new Android/Kotlin lint, API-level, opt-in, or suppression annotations. `@Composable` is allowed. Use `Build.VERSION` checks, ordinary control flow, and result/error values instead.
- Keep preferences, persistence, settings labels, shader selection, and policy in the consumer.

The Android platform documents `RuntimeShader` and `RenderEffect.createRuntimeShaderEffect` as API 33 additions ([RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader), [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)); this plan does not rely on an annotation to hide that boundary.

## Repository Baseline

Current `compose-app` puts approximately 1,100 lines in `TerminalCanvas.kt` and couples it to `TerminalSession`, `TerminalView`/`TerminalRenderer`, `ComposeInputTerminalView`, `TextInputService`, `GraphicsLayer`/`LocalGraphicsContext`, selection and popup UI, and app preferences. Rendering/effects are also spread across `TerminalRenderNodeRenderer.kt`, `AnimatedTerminalBitmapRenderer.kt`, `TerminalVisualEffects.kt`, `TerminalEffect.kt`, and `ShaderRepository.kt`.

`terminal-view.TerminalViewClient` is a full interface with no default methods, and `TermuxTerminalViewClientBase` is in `termux-shared`; neither is a core dependency candidate. Current build facts are Compose BOM `2026.06.01`, Java/Kotlin 17, and modules `app`, `termux-shared`, `terminal-emulator`, `terminal-view`, and `compose-app`.

## Boundaries and Artifacts

### Initial repository modules

1. `terminal-compose-view`: the published `com.android.library` AAR. It owns Compose rendering, input/IME host, selection/semantics primitives, shader/effect extension contracts, frame scheduling, and backend-neutral models. It may depend on Compose UI/foundation/runtime/graphics APIs, lifecycle APIs only if the host integration requires them, and explicitly chosen AndroidX dependencies. It must not depend on `terminal-view`, `termux-shared`, `compose-app`, SSH, workspace, or server code.
2. `compose-app`: owns the `TerminalSession` backend adapter initially, Ghostty-specific mouse/resize/input translation, app preferences, built-in shader sources, current cursor trail implementations, and migration wrappers. If the adapter must temporarily call `terminal-view`, keep it in this module or a non-core migration module and do not expose it through the AAR API.
3. `terminal-compose-view-sample` or an equivalent local sample/test consumer: a minimal consumer of the public API, with one consumer-defined shader and one consumer-defined effect. It must not import `compose-app` or `termux-shared`. Add it only if the repository's build size makes a sample practical; otherwise use an instrumented fixture consuming the published/local AAR.
4. Later, optionally `terminal-compose-view-compat`: a separate adapter for existing Java/View `terminal-view` clients. It is not part of the core extraction and must not constrain the core API.

Do not publish the app-specific `TerminalSession` adapter as part of the core artifact. If a reusable Ghostty adapter is desired, make it a separately named artifact with an explicit dependency on `terminal-emulator`; decide this only after the core contract is stable.

## Public API Design

Design and test these contracts before moving the composable. Keep public types small, immutable where possible, and Java-readable where that is useful. Do not expose Compose snapshot state, `SharedPreferences`, Android `Context` as a storage mechanism, or platform renderer implementation classes.

### Host and configuration

Use a composable entry point such as `TerminalCanvas(backend, config, modifier, ...)`. `TerminalCanvasConfig` should contain rendering/input policy only:

- Font size and validated min/max bounds.
- Consumer-supplied `List<ShaderDefinition>` and nullable `CursorEffect`.
- A neutral frame-rate request (`Vsync` or bounded FPS), not a settings enum or preference key.
- Keyboard-on-tap policy, selection policy, optional accessibility labels, and callbacks for font-size changes, opening a URL, copy/share requests, and diagnostics.
- Explicit limits or a `ShaderPolicy` supplied by the consumer, rather than silently accepting unlimited imported content.

Use a separate `ModifierKeyReader` interface for Control/Alt/Shift/Fn. The app adapts `ExtraKeysController`; the library never imports it.

The composable must not create or own a terminal session. A remembered controller may own renderer caches and subscriptions, but the caller owns the backend and supplies a stable identity. Define whether changing backend identity detaches the old backend before attaching the new one, and test it.

### Backend and frame model

Define library-owned interfaces and value types, for example:

- `TerminalBackend`: attach/detach, resize, invalidate/requestFrame, scroll, key/text/IME input, pointer/mouse events, selection/copy, link activation, and release. Document that calls and callbacks are main-thread confined unless explicitly stated otherwise.
- `TerminalFrameSource` or `TerminalBackend.currentFrame`: an immutable snapshot with monotonically increasing content/frame sequence, rows/cells or a renderer-neutral row representation, palette/theme, cursor state, viewport/top row, dimensions, hyperlink spans, and selection metadata. A snapshot must remain valid for the duration of one draw; it must not expose mutable `TerminalSession` objects.
- `TerminalMetrics`: cell width/height, ascent/baseline, viewport size, density-independent font settings, and coordinate conversion methods. Mouse/selection hit testing must use this type, not `mRenderer` fields.
- `TerminalLink`, `TerminalSelection`, `TerminalPointerEvent`, `TerminalKeyInput`, `TerminalTextInput`, `ImeState`, and result/error types. Keep Android `KeyEvent`/`MotionEvent` out of the backend contract where practical so future non-View hosts can translate them.
- A backend event stream or invalidation callback. Specify coalescing, callback thread, and behavior after detach/release.

Do not make the renderer call session methods during drawing. A backend publishes a frame and handles commands; the renderer consumes only the frame. This makes drawing deterministic and testable and prevents UI-thread drawing from becoming a session synchronization boundary.

The first adapter translates the current Ghostty session APIs, including mouse tracking, cursor blink, resize, and scroll. It may initially obtain a frame through existing code, but any temporary `TerminalRenderer`/`TerminalView` delegation must be isolated and have a removal ticket.

### Input, IME, and focus

The library owns Compose focus, pointer/key event translation, selection gestures, and the `ImeEditCommandProcessor` only after its input type is backend-neutral. It sends commands to `TerminalBackend`; it does not call `TerminalSession` directly.

Define IME behavior precisely: focus starts a text-input session, editor state reflects the backend's composing/selection state, edit commands are translated in order, cancellation ends the session, and detach/release always closes it. Prefer the currently supported Compose platform text-input API in the repository; isolate any compatibility with deprecated APIs in one internal file and do not add a new suppression annotation. Test composition, deletion, selection, commit text, and cancellation, not just plain typing.

Hardware keys, software text, dead keys, Control/Alt transformations, and pointer mouse reporting are separate paths. A failed backend command must not leave the IME session or pressed-key state stuck. Every input callback is ignored after release.

### Selection, links, and accessibility

Selection and link hit testing use `TerminalMetrics` plus frame metadata. The library may provide selection state and copy text extraction, but clipboard policy, toast/UI policy, and URL handling remain callbacks to the consumer. Popup/Material3 selection controls should be optional or app-owned, not required by the renderer core.

Compose semantics do not automatically make a custom canvas's cells accessible. Define an accessibility model before claiming feature parity: expose a stable terminal content summary and actions (copy selection, select all, activate link, scroll), and provide a consumer callback or semantics provider for visible text. If TalkBack requires virtual child nodes or granular cursor navigation, implement that in a dedicated host/accessibility layer rather than pretending a single `Canvas` semantic node is equivalent to a `TextView`. Add an instrumentation test with accessibility services or the best available semantics-tree assertions.

## Rendering and Effects

### Rendering ownership

Move the generic portions of `TerminalRenderNodeRenderer.kt`, `AnimatedTerminalBitmapRenderer.kt`, and `TerminalVisualEffects.kt` only after they consume `TerminalFrame`, `TerminalMetrics`, and library-owned selection/link types. `GraphicsLayer`/`LocalGraphicsContext` resources are owned by the renderer controller and released in `DisposableEffect`/controller close. Releasing a controller is idempotent and releases every row layer, parent layer, bitmap, shader, and callback registration.

Keep `TerminalEffect.kt`, `AgslSources`, `CursorTrailEffect`, Warp/Sweep/Tail, and their geometry/easing helpers in `compose-app`. Adapt them to the public effect interfaces instead of moving them.

### Shader extension model

`ShaderDefinition` should describe a consumer-owned ID, source, expected input contract, animation requirement, and uniform schema. Avoid `label` and `isBuiltIn` in the core unless they are genuinely presentation-neutral; an ID and consumer metadata are enough. Define whether a shader is a post-process requiring an input shader named `content`, and validate that contract before compilation.

Provide a result-based validator/compiler, not an exception-prone composable API:

- Validate non-empty ID, UTF-8/source byte limit, maximum number of definitions and chain depth, required entry point (`main`), permitted uniform names/types, and required input shader name.
- On API 33+, construct `RuntimeShader` and catch compilation/argument failures. `RuntimeShader(String)` compiles the source; AGSL has no recursion, but malformed or expensive shader code can still fail or consume substantial GPU time ([AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)).
- On API < 33, do not load, reference, or instantiate `RuntimeShader`/runtime-shader `RenderEffect` on the execution path. Render the normal terminal and report an unavailable/unsupported result. Stored definitions may remain stored only if the consumer's storage policy chooses that behavior.
- Compile only on the main/UI thread if required by the graphics API; never block it with file/network work. Cache by source and relevant configuration, replace old compiled instances atomically, and release/remove the old render effect before dropping references.
- Set resolution/time and consumer-declared uniforms every frame as appropriate. Recreate the `RenderEffect` when platform behavior requires it after uniform changes, following the current implementation and testing on API 33 devices; do not assume uniform mutation alone invalidates every render node.

This is an extension point, not a security sandbox. Treat user AGSL as untrusted input: no network/file access is provided by this API, but GPU denial-of-service, compiler crashes, memory pressure, and thermal cost remain risks. Enforce bounded source/chain/count/animation limits, reject unsupported uniforms, catch failures, rate-limit recompilation, stop repeatedly failing shaders, and expose diagnostics without logging full user source. Do not claim resource limits are a hard security boundary; add a consumer opt-in for imported shaders if appropriate.

### Cursor effects and animation

Expose a consumer-implemented `CursorEffect` that receives a read-only frame, cursor transition state, `DrawScope`, metrics, and elapsed time. The library owns previous/current cursor positions, first-observation behavior, move timestamps, and reset on backend identity/resize. The consumer owns all colors, geometry, easing, and duration. A bounded duration/`needsFrame` result lets the library stop transient animation when idle.

Use one frame scheduler with these rules:

- Static terminal: redraw only for backend invalidation, input/selection changes, or size/config changes.
- Continuous shader: schedule frames while the canvas is active/visible.
- Cursor effect: schedule until its declared duration plus a small grace period, then stop.
- Frame-rate requests are clamped to a documented range and are preferences, not guarantees; display/vsync scheduling remains platform-controlled.
- Reset/cancel scheduling on composition disposal, lifecycle stop if lifecycle integration is enabled, backend detach, and release. A `LaunchedEffect` scope is composition-scoped and is canceled when the composable leaves composition ([Compose coroutines](https://developer.android.com/develop/ui/compose/side-effects)); do not use it as a substitute for host lifecycle visibility when the canvas remains composed but hidden.

## Implementation Sketches

These snippets are deliberately small sketches. They show ownership and dependency direction; the implementor should refine names and result types against the resolved Compose/AGP versions.

### Canvas boundary

```kotlin
data class TerminalCanvasConfig(
    val fontSize: Int,
    val minimumFontSize: Int,
    val maximumFontSize: Int,
    val shaders: List<ShaderDefinition> = emptyList(),
    val cursorEffect: CursorEffect? = null,
    val preferredFrameRate: Float? = null,
    val unconditionalKeyboardOnTap: Boolean = true,
    val onFontSizeChange: (Int) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onDiagnostics: (TerminalDiagnostic) -> Unit = {}
)

interface ModifierKeyReader {
    fun readControl(): Boolean
    fun readAlt(): Boolean
    fun readShift(): Boolean
    fun readFn(): Boolean
}

@Composable
fun TerminalCanvas(
    backend: TerminalBackend,
    modifierKeys: ModifierKeyReader,
    config: TerminalCanvasConfig,
    modifier: Modifier = Modifier
) {
    // The composable owns only UI/render resources. The caller owns `backend`.
}
```

Do not put `Context`, `SharedPreferences`, `TerminalView`, or app settings enums in this configuration. If callback-heavy configuration becomes difficult to use from Java, add a separate Java-friendly builder later rather than coupling the core to app storage.

### Backend and immutable frame

```kotlin
interface TerminalBackend {
    fun attach(listener: TerminalBackendListener)
    fun detach()
    fun resize(widthPx: Int, heightPx: Int)
    fun submit(command: TerminalCommand): TerminalCommandResult
    fun currentFrame(): TerminalFrame?
    fun release()
}

interface TerminalBackendListener {
    fun onFrameInvalidated()
    fun onBackendError(error: TerminalBackendError)
}

data class TerminalFrame(
    val sequence: Long,
    val metrics: TerminalMetrics,
    val rows: List<TerminalRow>,
    val cursor: TerminalCursor,
    val palette: TerminalPalette,
    val links: List<TerminalLink>,
    val viewport: TerminalViewport
)
```

The exact row representation is a key performance decision. Start with an immutable/fakeable representation that has explicit ownership. Optimize to row diffs or retained rows only after profiling, and document when old frames may be released. Drawing must consume one stable frame and never call back into the session.

### Consumer-defined shader

```kotlin
val scanline = ShaderDefinition(
    id = "example.scanlines",
    source = """
        uniform shader content;
        uniform vec2 resolution;

        half4 main(vec2 xy) {
            half4 color = content.eval(xy);
            float line = 0.92 + 0.08 * cos(xy.y * 3.14159265);
            return half4(color.rgb * line, color.a);
        }
    """.trimIndent(),
    usesTimeUniform = false,
    usesResolutionUniform = true
)

val config = TerminalCanvasConfig(
    fontSize = 14,
    minimumFontSize = 8,
    maximumFontSize = 32,
    shaders = listOf(scanline)
)
```

The library should return a structured compile result instead of throwing through composition:

```kotlin
sealed interface ShaderCompileResult {
    data class Success(val shader: CompiledShaderHandle) : ShaderCompileResult
    data class Unsupported(val reason: String) : ShaderCompileResult
    data class Invalid(val reason: String) : ShaderCompileResult
}
```

The final API may keep the compiled handle internal. The important contract is that consumers supply source/metadata while the library owns compilation, replacement, render-effect binding, and release.

### Consumer-defined cursor effect

```kotlin
class GlowCursorEffect(
    private val durationSeconds: Float = 0.18f
) : CursorEffect {
    override val maxDurationSeconds: Float
        get() = durationSeconds

    override fun draw(
        drawScope: DrawScope,
        frame: TerminalFrame,
        state: CursorEffectState,
        timeSeconds: Float
    ) {
        if (!state.hasPreviousPosition || !frame.cursor.visible) return

        // Consumer-owned geometry, color, easing, and drawing.
        // The library only tracks cursor movement and schedules frames.
    }
}
```

The effect must tolerate its first frame, cursor visibility changes, backend replacement, resize, and stale/empty frames. The library must reset `CursorEffectState` when the backend identity changes or the effect instance changes.

### App-side adapter wiring

```kotlin
val selectedDefinitions = selectedShaderIds.mapNotNull(shaderRepository::find)
val cursorEffect = when (cursorPreference) {
    "none" -> null
    "warp" -> AppWarpCursorEffect()
    "sweep" -> AppSweepCursorEffect()
    else -> null
}

TerminalCanvas(
    backend = ghosttyBackend,
    modifierKeys = extraKeysController.asModifierKeyReader(),
    config = TerminalCanvasConfig(
        fontSize = preferences.fontSize,
        minimumFontSize = sizes.minimum,
        maximumFontSize = sizes.maximum,
        shaders = selectedDefinitions,
        cursorEffect = cursorEffect,
        preferredFrameRate = preferences.visualEffectFps,
        onFontSizeChange = { size -> preferences.setFontSize(size) },
        onOpenUrl = onOpenUrl
    ),
    modifier = modifier
)
```

This keeps app-defined shader names, cursor preferences, persistence, and settings UI outside the library.

### Compose resource ownership

```kotlin
@Composable
private fun rememberTerminalController(
    backend: TerminalBackend,
    config: TerminalCanvasConfig
): TerminalController {
    val controller = remember(backend) {
        TerminalController(backend)
    }

    DisposableEffect(controller, backend) {
        controller.attach()
        onDispose {
            controller.detach()
            controller.release()
        }
    }

    return controller
}
```

The actual implementation must avoid double-release when a backend/config changes and must define whether controller replacement occurs before or after old backend detachment. Use stable keys and tests for attach, replacement, and disposal. Required `@Composable` annotations are expected; do not add suppression or API-level annotations around this code.

### Temporary migration adapter

```kotlin
// App-side only. This type must not be exported by terminal-compose-view.
internal class TerminalViewMigrationBackend(
    private val view: TerminalView,
    private val session: TerminalSession
) : TerminalBackend {
    override fun currentFrame(): TerminalFrame? = TODO("Translate to library-owned frame")
    override fun submit(command: TerminalCommand): TerminalCommandResult =
        TODO("Translate to existing view/session input")
}
```

This adapter is allowed only to stage the extraction. It is removed once the native Ghostty backend supplies frames, metrics, links, resize, scrolling, mouse events, and input without `terminal-view`.

## Host Lifecycle and Threading

All UI, Compose state, graphics-layer, shader, and backend command calls have an explicit main-thread rule. Backend computation or I/O may use a caller-owned coroutine scope/dispatcher and must publish immutable frames back to the main thread. Android UI toolkit objects are not thread-safe ([processes and threads](https://developer.android.com/guide/components/processes-and-threads)). The library must not create an unbounded global scope.

For a pure Compose host, composition disposal is the release boundary. Use `DisposableEffect` for attach/detach and release, and ensure replacement ordering is old-detach then new-attach. If visibility throttling is needed, let the host provide a lifecycle/active signal or use lifecycle-aware Compose APIs; do not silently retain a live terminal stream while off-screen.

For a future View-based host, expose a separate `ComposeView` host artifact or let `termux-app` construct `ComposeView` itself. `ComposeView` compositions must use an appropriate `ViewCompositionStrategy`; Android recommends `DisposeOnViewTreeLifecycleDestroyed` for a Fragment view and documents that compositions are live resources requiring disposal ([Compose in Views](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/compose-in-views), [ViewCompositionStrategy](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ViewCompositionStrategy)). A Java app cannot call a Kotlin `@Composable` directly; it needs this host or an app-owned Kotlin bridge. Do not add a View host merely to preserve `terminal-view` compatibility in the first artifact.

## Build, API, and Publishing

Add `:terminal-compose-view` to `settings.gradle` and configure `com.android.library`, Kotlin Android/Compose plugin, and `maven-publish` consistently with this repository. Match compile/target/min SDK, Java/Kotlin 17, Compose BOM, packaging, and repository group/version conventions. Use `api` only for a dependency whose types intentionally appear in the public API; prefer `implementation` for internals. Initially, keep the public API free of `terminal-emulator` types unless the backend contract deliberately adopts them.

Mirror `terminal-view`'s `multipleVariants`, sources jar, and publication only after confirming the current AGP setup. Prefer publishing a local Maven repository/AAR plus POM for the sample rather than testing a loose AAR; Android's publishing guidance explains that repositories preserve identity, version, and dependencies ([Upload a library](https://developer.android.com/build/publish-library/upload-library)). Verify the publication's API and dependency graph, not just that Gradle compiles the module.

Do not add annotations to silence API/lint checks. The module must still pass the repository's configured checks without `@Suppress`, `@OptIn`, `@RequiresApi`, or equivalent additions. If the current compiler flags force a required opt-in, isolate and resolve it through the existing project configuration or revisit the API; do not add a new suppression to this library.

Ship `consumerProguardFiles` only for rules actually needed by reflection/JNI or generated entry points. The library has no reflection requirement by default, so the preferred consumer rules file is empty/minimal and must not blanket-keep the package. Android documents that consumer rules are bundled in the AAR and applied to consuming apps ([library optimization](https://developer.android.com/topic/performance/app-optimization/library-optimization)). Test a minified sample and inspect mapping/output.

## Migration Stages

1. Inventory all `TerminalCanvas` behavior and separate pure coordinate/input/frame helpers from app/session code. Record current behavior and known gaps, especially accessibility and IME.
2. Add the module and a minimal public API plus fake backend. Build a static canvas with immutable fake frames, focus, resize, and disposal tests before moving production rendering.
3. Move the renderer/cache and frame scheduler. Replace `TerminalRenderer`/link-layout references with library types. Add no shaders/effects beyond a test-provided definition.
4. Move neutral IME processing, key/pointer translation, selection state, and semantics seams. Keep Material3 selection UI and URL/preferences in the app.
5. Add the `compose-app` `TerminalSession` adapter. If necessary, use a private adapter that delegates to `terminal-view`, but keep it outside the new AAR and mark every delegation for removal.
6. Adapt app shaders and cursor trails as consumers. App code merges its own built-ins with imported definitions and passes the final list; the library manufactures none.
7. Switch the app screen to the new canvas, run side-by-side/manual checks, and remove duplicated old renderer code only after parity tests pass.
8. Remove the temporary `terminal-view` adapter when the native session backend supplies frames, metrics, links, resize, mouse, and input itself. Removal criteria: no core or compose-app extraction path references `TerminalView`, `TerminalRenderer`, `TerminalViewLinkLayout`, or `TermuxTerminalViewClientBase`; the standalone sample builds against the published core artifact.
9. Only then decide whether a separately published Ghostty adapter or `terminal-view-compat` artifact is worthwhile.

## Testing and Acceptance

### Unit and integration tests

- Fake backend contract: attach/detach/replacement/release ordering, resize, invalidation coalescing, immutable frame lifetime, command-after-release behavior, and callback threading.
- Input: hardware key mapping, modifier reader, text/code-point input, IME commit/composition/delete/cancel, mouse tracking, scroll, selection, link hit testing, and font-size bounds.
- Effects: shader metadata/limits/validation, compilation result handling, API < 33 fallback, API 33 success/failure, shader replacement/release, uniform updates, continuous animation, transient cursor animation, and scheduler stop/reset.
- Renderer: row invalidation/content versions, palette/geometry changes, selection/cursor changes, empty frames, large row counts, and graphics-layer release. Use fake frame/metrics tests for pure logic and device/instrumentation tests for platform graphics.
- Accessibility: semantics actions/content summary and at least one TalkBack/accessibility-service smoke path; document any deliberate limitation on granular cell navigation.
- Compose host: recomposition with stable/changed config, backend replacement, composition disposal without initial attachment, lifecycle stop/start if supported, and no leaked callbacks/layers.

### Consumer and device checks

The minimal consumer supplies one shader source and one cursor effect using only public APIs, builds without `compose-app`/`termux-shared`, and runs minified. On API 33+, verify rendering and animation on a real/emulated device. On API < 33, verify normal rendering and no shader class loading/crash. Manually verify the migrated app's keyboard, IME composition, gestures, scrolling, links, selection/copy/paste, accessibility, resize, cursor blink/effects, background/foreground, and disposal.

Run `./gradlew :terminal-compose-view:assembleDebug`, the sample/consumer build, relevant unit and instrumentation tests, `./gradlew :compose-app:assembleDebug`, `./gradlew ktlintFormat detekt`, and repository lint as applicable. Inspect the AAR/POM/dependencies to confirm no app classes, built-in AGSL sources, or `terminal-view` dependency are present.

## Acceptance Checklist

- [ ] Core AAR is Compose-first infrastructure and is not a `terminal-view` drop-in replacement.
- [ ] Core has no `terminal-view`, `termux-shared`, `compose-app`, workspace, SSH, or server dependency.
- [ ] Core has no built-in AGSL source, cursor effect, visual preset, preference key, or settings policy.
- [ ] Public API uses backend/frame/metrics/link/selection types owned by the library.
- [ ] Consumer-defined shaders/effects work on API 33+; API < 33 safely renders without runtime-shader access.
- [ ] Shader source and animation resource limits, failure handling, and diagnostics are documented and tested.
- [ ] Input, IME, focus, selection, links, scrolling, resize, accessibility, lifecycle, threading, and release contracts are explicit and tested.
- [ ] No new lint/API/opt-in/suppression annotations were added; required `@Composable` annotations are the only intentional exception.
- [ ] App effects/sources/preferences remain app-owned and work through the extension API.
- [ ] Temporary adapter is outside the core artifact and has concrete removal criteria.
- [ ] Minimal independent consumer and minified build pass; AAR/POM/dependency inspection passes.
- [ ] `ktlintFormat`, `detekt`, tests, lint, and app build pass.

## Unresolved Decisions and Risks

- Whether `terminal-compose-view` should expose `terminal-emulator` types, or whether even the first `TerminalSession` adapter must be a separate artifact. Keeping them out maximizes reuse but increases adapter work.
- Whether the backend publishes full cell snapshots, row diffs, or a retained frame object. Full immutable snapshots simplify correctness; diffs may be required for performance and need ownership rules.
- The exact Compose text-input API available in this repository and its future stability. Confirm against the resolved Compose BOM before implementation rather than designing around deprecated `LocalTextInputService` behavior.
- Whether Compose semantics can meet Termux accessibility requirements or a dedicated Android View/accessibility bridge is needed.
- API 33 shader compilation and `RenderEffect` behavior across GPU vendors, including whether uniform changes require effect recreation. Device tests are required; documentation does not guarantee the performance characteristics of this renderer.
- AGSL is programmable GPU code, not a sandbox. Even bounded sources can be expensive or trigger vendor compiler/GPU failures; consumers may need an opt-in and a kill switch.
- `preferredFrameRate` is a request, not a timing guarantee. Battery/thermal policy and lifecycle visibility need consumer decisions.
- Publishing version/group coordinates, repository location, and whether a local-only publication is enough for `termux-app` are repository-release decisions, not API design assumptions.
