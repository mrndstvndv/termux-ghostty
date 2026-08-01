# Wallpaper and glass shader plan

Status: design only. Do not implement this document as part of the current shader work.

## Goal

Add customizable wallpapers behind the terminal and support effects such as blurred glass, water droplets, tinting, and subtle distortion while keeping terminal characters fully opaque.

Desired composition:

```text
wallpaper image / gradient
        ↓
wallpaper effects: blur, glass, droplets, tint, dim
        ↓
terminal background with configurable opacity
        ↓
terminal characters, selection, cursor, and trails at full opacity
```

The wallpaper belongs behind the terminal pane only. SFTP and Review panes should remain unaffected unless explicitly supported later.

## Current constraints

- `TerminalRenderNodeRenderer` draws the base terminal background and row layers into one parent layer.
- `TerminalRenderer.renderRow()` currently draws cell backgrounds and glyphs in the same row pass.
- Terminal post shaders receive only the terminal frame through the `content` child shader. They cannot sample a sibling wallpaper layer.
- The existing `ShaderRepository` and shader selection model target terminal post-processing.

The terminal background therefore cannot become independently translucent until cell backgrounds and glyphs have separate rendering passes.

## Proposed rendering architecture

```text
TerminalWorkspaceContainer
└── Box
    ├── WallpaperLayer
    │   ├── wallpaper content
    │   ├── native blur
    │   └── wallpaper AGSL pipeline
    └── TerminalCanvas
        ├── terminal background layer (alpha controlled)
        ├── terminal glyph / selection / cursor layer (alpha = 1)
        └── terminal AGSL pipeline
```

`WallpaperLayer` should be placed around `TerminalCanvas`, not around the entire workspace. This keeps wallpapers out of SFTP and Review panels and works for both narrow tabbed layouts and wide split layouts.

## Data model

Extend shader metadata with an explicit target:

```kotlin
enum class ShaderTarget {
    TERMINAL,
    WALLPAPER
}
```

Add the target to `ShaderDefinition`, defaulting imported and existing shaders to `TERMINAL` for compatibility.

Wallpaper settings should be separate from shader selection:

```kotlin
data class WallpaperSettings(
    val sourceId: String?,
    val opacity: Float,
    val terminalBackgroundOpacity: Float,
    val blurRadius: Float,
    val dimAmount: Float,
    val tint: Color
)
```

Persist wallpaper sources in app-private storage, like imported shaders. Persist the source ID and settings rather than the external document URI.

## Implementation phases

### Phase 1: Wallpaper storage and settings

- Add wallpaper import through Android's document picker.
- Copy selected images into app-private storage.
- Support clearing the current wallpaper.
- Add settings for wallpaper opacity, terminal background opacity, dimming, tint, and blur radius.
- Start with static images and a `None` option.
- Use a safe default that preserves text readability.

Non-goals for this phase: video wallpapers, animated wallpapers, remote URLs, and parallax effects.

### Phase 2: Wallpaper layer

- Add a `WallpaperLayer` composable behind `TerminalCanvas`.
- Render images with a configurable content scale and crop mode.
- Keep the layer non-interactive so terminal gestures and keyboard input continue to work.
- Add a native Android blur stage using `RenderEffect.createBlurEffect`.
- Apply dimming and tinting after the blur.

Native blur should be preferred for ordinary blur because it is more efficient than a large AGSL sampling kernel.

### Phase 3: Separate terminal background and glyphs

Refactor the terminal renderer into two passes:

1. Background pass:
   - Base terminal palette background.
   - Non-default cell background rectangles.
   - Selection background.
2. Foreground pass:
   - Glyphs.
   - Underlines and strike-throughs.
   - Cursor.
   - Selection text and other foreground overlays.

Apply `terminalBackgroundOpacity` only to the background pass. Characters and cursor remain fully opaque.

Likely implementation changes:

- Extend `TerminalRenderer` with background and foreground row methods, or introduce a small render-pass parameter.
- Keep the existing row cache and dirty-row invalidation model.
- Avoid rendering the same glyph data twice.
- Treat cursor and selection semantics explicitly; their foreground must remain readable even when the background is translucent.

### Phase 4: Generalize shader pipelines

Extract the common shader-chain logic from `TerminalRenderNodeRenderer` into a reusable internal `ShaderPipeline` module.

The interface should remain small:

```kotlin
class ShaderPipeline(
    definitions: List<ShaderDefinition>,
    target: ShaderTarget
) {
    fun updateUniforms(timeSeconds: Float, width: Float, height: Float)
    fun asRenderEffect(): ComposeRenderEffect?
}
```

The implementation should own compilation, invalid-shader handling, uniform updates, and ordered `RenderEffect` chaining. Callers should not need to know how individual shader instances are chained.

Use separate selected-ID lists for terminal and wallpaper shaders. Preserve selection order because each shader consumes the output of the previous shader.

### Phase 5: Glass and droplet effects

Add a wallpaper shader contract using the existing `content` child shader:

```agsl
uniform shader content;
uniform float time;
uniform vec2 resolution;

half4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / max(resolution, vec2(1.0));
    vec2 offset = dropletOffset(uv, time);
    return content.eval(clamp(fragCoord + offset, vec2(0.0), resolution - 1.0));
}
```

Initial glass effects should be procedural so they require no additional texture inputs:

- Low-frequency refraction.
- Droplet-shaped local distortion.
- Edge highlights.
- Condensation noise.
- Optional dimming and color shift.

A future version can support imported droplet masks or normal maps through additional shader inputs. That should not be added until the base wallpaper pipeline is stable.

Recommended chain:

```text
wallpaper → native blur → glass distortion → tint / dim
```

Keep sample counts and distortion bounds conservative. Large AGSL blur kernels and excessive multi-pass chains can cause GPU cost and frame drops on mobile devices.

### Phase 6: UI and persistence

Replace the current terminal-only shader section with separate sections:

- Wallpaper
  - Import / clear
  - Preview
  - Opacity and crop controls
  - Blur, dim, and tint controls
- Terminal background
  - Background opacity
- Wallpaper shaders
  - Multi-select imported and built-in effects
- Terminal shaders
  - Existing multi-select effects

Invalid wallpaper shaders should be disabled without removing the wallpaper. Invalid imported files should show an in-app error and never interrupt terminal rendering.

## Shader interface decisions

- Wallpaper and terminal shaders both use `uniform shader content`.
- `content.eval` always receives pixel coordinates.
- `time` and `resolution` remain optional uniforms.
- Native blur handles ordinary blur; AGSL handles stylized blur and glass effects.
- Existing imported shaders default to `ShaderTarget.TERMINAL`.
- A shader must not assume access to the wallpaper when it is running in the terminal pipeline.

## Validation plan

### Unit tests

- Wallpaper settings default and persistence behavior.
- Legacy shader preferences migrate to terminal shader selection.
- Target filtering keeps terminal and wallpaper shaders in their own pipelines.
- Shader chain ordering is preserved.
- Invalid shader sources are rejected without deleting valid sources.

### Rendering tests

- Wallpaper is visible through transparent terminal background areas.
- Glyphs remain fully opaque when terminal background opacity changes.
- Non-default cell backgrounds obey terminal background opacity.
- Selection and cursor remain readable.
- Wallpaper blur does not blur terminal characters.
- Terminal shaders do not process the wallpaper.
- Wallpaper shaders do not process terminal characters.
- Glass distortion does not sample outside the wallpaper bounds.
- Animated wallpaper shaders respect the configured frame rate.

### Device checks

Test on at least one Android 13+ device and one lower-end Android device. Verify:

- Importing and clearing wallpapers.
- Rotation and window resizing.
- Narrow tabbed and wide split workspace layouts.
- IME and navigation-bar insets.
- GPU frame time with blur disabled, native blur enabled, and glass shaders enabled.
- Readability over both dark and bright wallpapers.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Glyphs become translucent with the terminal background | Separate background and foreground render passes |
| Wallpaper shader cannot see wallpaper | Run it on the wallpaper layer, not the terminal layer |
| AGSL blur is too expensive | Use native blur for standard blur and cap custom samples |
| Imported shaders fail on some devices | Compile before registration and degrade per shader |
| Wallpaper reduces terminal readability | Defaults for dimming, opacity, and tint; provide a quick clear action |
| More layers increase GPU work | Keep static layers retained and invalidate only animated pipelines |
