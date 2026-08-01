# Terminal shaders

The terminal supports whole-frame AGSL post-processing shaders on Android 13 (API 33) and newer. Shaders are compiled with `android.graphics.RuntimeShader` and applied through a chained `RenderEffect`. Multiple shaders can be enabled at once; they run in selection order.

## Current shaders

Built-in shader sources are defined in:

`src/main/java/com/mrndtvndv/term/ui/workspace/TerminalEffect.kt`

Available effects:

- None
- CRT
- Retro CRT
- Curvature
- Bloom
- Glowing Lines
- Static Noise
- Chromatic
- RGB Shift
- Flicker
- Scanlines
- Vignette
- Glitch
- Matrix

Compilation failures are logged and fall back to no effect. Devices older than Android 13 do not expose terminal shader effects.

## AGSL contract

A terminal post-processing shader must declare the terminal frame as a child shader named `content` and expose an AGSL entry point:

```agsl
uniform shader content;

half4 main(vec2 fragCoord) {
    return content.eval(fragCoord);
}
```

`fragCoord` and `content.eval(...)` use pixel coordinates. The terminal frame is supplied by the renderer through the `content` child shader.

Optional uniforms currently supported by the renderer:

```agsl
uniform float time;
uniform vec2 resolution;
```

- `time` is elapsed animation time in seconds.
- `resolution` is the frame size in pixels.

A shader should only declare uniforms that it uses. The renderer must not attempt to set undeclared uniforms.

When multiple shaders are selected, the output of each shader becomes the `content` input of the next shader.

## Example: scanlines

```agsl
uniform shader content;
uniform vec2 resolution;

half4 main(vec2 fragCoord) {
    half4 color = content.eval(fragCoord);
    float scanline = 0.90 + 0.10 * cos(fragCoord.y * 3.14159265);
    color.rgb *= scanline;
    return color;
}
```

## External shader design

The shader system represents imported shaders by a definition rather than a `TerminalEffect` enum entry:

```kotlin
data class ShaderDefinition(
    val id: String,
    val label: String,
    val source: String,
    val animated: Boolean,
    val usesTimeUniform: Boolean,
    val usesResolutionUniform: Boolean,
    val isBuiltIn: Boolean
)
```

The import flow is:

1. Tap **Import** in the Terminal Shaders settings.
2. Select an `.agsl` file with Android's document picker.
3. Copy its contents into app-private storage.
4. Compile it with `RuntimeShader`.
5. Add it to the multi-select shader list only after successful compilation.
6. Persist the shader ID, not the source URI.

Imported shaders can be deleted directly from the shader list. Selecting **None** clears all other shaders.

Imported shaders should be size-limited and invalid shaders should be rejected without affecting the terminal. AGSL cannot access the filesystem, network, or application APIs; it is shader source, not executable Kotlin or Java code.

## GLSL compatibility

AGSL is not a drop-in replacement for desktop GLSL, Shadertoy, or Ghostty shader files. Common conversions include:

| Source GLSL | AGSL terminal equivalent |
| --- | --- |
| `texture(...)` | `content.eval(pixelCoordinates)` |
| `iTime` | `time` |
| `iResolution` | `resolution` |
| `mainImage(...)` | `main(vec2 fragCoord)` |

AGSL also does not provide arbitrary shader includes, external textures, or every GLSL type/function. Existing Ghostty and Shadertoy shaders generally need to be ported and tested on-device.

## GLSL and Ghostty porting guide

### 1. Start with the shader contract

Remove the desktop GLSL header and replace the entry point with the terminal contract:

```agsl
uniform shader content;
uniform float time;       // only when needed
uniform vec2 resolution; // only when needed

half4 main(vec2 fragCoord) {
    // effect implementation
    return content.eval(fragCoord);
}
```

The `content` child shader is the existing terminal frame. There is no need to declare a `sampler2D` for it.

### 2. Convert texture sampling

GLSL and Shadertoy commonly sample normalized UV coordinates. AGSL's `content.eval` samples pixel coordinates, so use a helper:

```agsl
vec4 sampleContent(vec2 uv) {
    vec2 bounds = max(resolution - vec2(1.0), vec2(0.0));
    vec2 pixel = clamp(uv * resolution, vec2(0.0), bounds);
    return content.eval(pixel);
}
```

Typical conversions:

```glsl
vec4 color = texture(iChannel0, uv);
vec4 blur = textureLod(iChannel0, uv + offset, 0.0);
```

become:

```agsl
vec4 color = sampleContent(uv);
vec4 blur = sampleContent(uv + offset);
```

Use `content.eval` for every sample. Clamp coordinates because samples outside the frame can become transparent black.

### 3. Convert common uniforms

| GLSL, Shadertoy, or Ghostty | AGSL terminal equivalent |
| --- | --- |
| `iTime` | `time` |
| `iResolution.xy` | `resolution` |
| `iChannel0` | `content` |
| `texture(iChannel0, uv)` | `sampleContent(uv)` |
| `textureLod(...)` | `sampleContent(...)` |
| `iFrame` | `floor(time * 60.0)` when a 60 Hz frame estimate is sufficient |
| `mainImage(outColor, fragCoord)` | `half4 main(vec2 fragCoord)` |

Ghostty shaders may use additional channels, mouse input, or custom uniforms. Those inputs are not supplied by this renderer and must be removed, replaced with constants, or added to the app's shader interface first.

### 4. Convert the entry point

A Shadertoy-style entry point:

```glsl
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    fragColor = texture(iChannel0, uv);
}
```

becomes:

```agsl
half4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / max(resolution, vec2(1.0));
    return sampleContent(uv);
}
```

Do not keep `out` parameters, `void mainImage`, `sampler2D`, or `#version` declarations.

### 5. Handle coordinate orientation

The renderer supplies pixel coordinates with the origin at the top-left. Shadertoy and many Ghostty shaders treat the origin as bottom-left. If the port looks vertically mirrored, flip the coordinate used by the effect:

```agsl
vec2 shaderCoord = vec2(fragCoord.x, resolution.y - fragCoord.y);
vec2 shaderUv = shaderCoord / max(resolution, vec2(1.0));
```

Keep source sampling in renderer coordinates unless the original shader intentionally transforms the sampled image. When converting normalized UVs back to a content sample, remember that `content.eval` expects pixels:

```agsl
vec2 sampleUv = vec2(shaderUv.x, 1.0 - shaderUv.y);
vec4 color = sampleContent(sampleUv);
```

Test both orientations with asymmetric content, such as text containing letters with ascenders and descenders.

### 6. Replace unsupported GLSL features

- Remove `#include`, `#define`, and conditional compilation; inline the required code.
- Remove precision qualifiers and desktop-only layout declarations.
- Replace `uint` or bitwise hash functions with float-based hashes.
- Replace unsupported texture functions with `content.eval`.
- Avoid external textures, image stores, atomics, and compute-shader features.
- Keep loops statically bounded; large sampling loops can fail compilation or be too expensive.
- Keep the output alpha explicit, normally `1.0` or the sampled terminal alpha.

### 7. Porting Ghostty shader files

For a Ghostty shader:

1. Copy the shader source into a new `.agsl` file.
2. Identify `mainImage`, `iTime`, `iResolution`, and texture channel usage.
3. Add `uniform shader content;`.
4. Replace channel sampling with `sampleContent`.
5. Convert the entry point and coordinate orientation.
6. Remove Ghostty-only uniforms and preprocessor code.
7. Import the file through **Settings → Terminal Shaders → Import**.
8. Test it alone before combining it with other shaders.

A Ghostty shader that only transforms the existing terminal frame is usually straightforward to port. A shader that generates a new scene, depends on multiple channels, or uses mouse/keyboard state needs an app-specific adaptation rather than a direct translation.

### 8. Validate the port

Check the imported shader on a real Android 13+ device or emulator:

- It compiles without an import error.
- Text remains readable at different terminal sizes.
- The top and bottom rows are not swapped.
- Out-of-bounds samples do not create black borders.
- Animated shaders declare and use `time`.
- Resolution-dependent shaders declare and use `resolution`.
- Combining it with another shader produces the expected selection-order result.

## Relevant implementation files

- `TerminalEffect.kt` — built-in definitions, AGSL sources, and shader compilation
- `ShaderRepository.kt` — imported shader storage, validation, and metadata
- `TerminalVisualEffects.kt` — animation lifecycle and terminal effect integration
- `TerminalRenderNodeRenderer.kt` — frame rendering, uniform updates, and `RenderEffect` binding
- `SettingsScreen.kt` — terminal effect selection UI
- `MainContent.kt` — selected effect persistence
