package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val ShaderDirectoryName = "shaders"
private const val CustomShaderIdsPreference = "custom_shader_ids"
private const val SelectedShadersPreference = "terminal_effects"
private const val ShaderIdSeparator = "\u001f"
private const val CustomShaderNamePrefix = "custom_shader_name_"
private const val MaxShaderBytes = 256 * 1024

/** Stores built-in and user-imported AGSL terminal shaders. */
class ShaderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
    private val shaderDirectory = File(appContext.filesDir, ShaderDirectoryName)

    fun definitions(): List<ShaderDefinition> {
        val builtIns = builtInDefinitions()
        val imported = customShaderIds().mapNotNull(::loadCustomShader)
        return builtIns + imported
    }

    fun find(id: String?): ShaderDefinition? {
        if (id == null) return null
        return definitions().firstOrNull { it.id == id }
    }

    fun import(sourceName: String?, source: InputStream): ShaderDefinition =
        import(sourceName, readSource(source))

    /** Validates and persists an imported shader source. */
    fun import(sourceName: String?, source: String): ShaderDefinition {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Terminal shaders require Android 13 or newer"
        }
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MaxShaderBytes) {
            "Shader source must be 256 KiB or smaller"
        }
        validateSource(source)

        val id = "custom_${UUID.randomUUID()}"
        val label = displayName(sourceName)
        val target = File(shaderDirectory, "$id.agsl")
        writeAtomically(target, source)

        val ids = customShaderIds().toMutableSet()
        ids += id
        preferences.edit()
            .putStringSet(CustomShaderIdsPreference, ids)
            .putString(CustomShaderNamePrefix + id, label)
            .apply()

        return ShaderDefinition(
            id = id,
            label = label,
            source = source,
            animated = containsUniform(source, "time"),
            usesTimeUniform = containsUniform(source, "time"),
            usesResolutionUniform = containsUniform(source, "resolution"),
            isBuiltIn = false
        )
    }

    fun delete(id: String) {
        if (!id.startsWith("custom_")) return

        File(shaderDirectory, "$id.agsl").delete()
        val ids = customShaderIds().toMutableSet()
        ids.remove(id)
        preferences.edit()
            .putStringSet(CustomShaderIdsPreference, ids)
            .remove(CustomShaderNamePrefix + id)
            .apply()
    }

    private fun loadCustomShader(id: String): ShaderDefinition? {
        val file = File(shaderDirectory, "$id.agsl")
        if (!file.isFile || file.length() > MaxShaderBytes) return null

        return try {
            val source = file.readText(StandardCharsets.UTF_8)
            ShaderDefinition(
                id = id,
                label = preferences.getString(CustomShaderNamePrefix + id, id) ?: id,
                source = source,
                animated = containsUniform(source, "time"),
                usesTimeUniform = containsUniform(source, "time"),
                usesResolutionUniform = containsUniform(source, "resolution"),
                isBuiltIn = false
            )
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun customShaderIds(): Set<String> =
        preferences.getStringSet(CustomShaderIdsPreference, emptySet()).orEmpty()

    private fun readSource(input: InputStream): String {
        val output = ByteArrayOutputStream(MaxShaderBytes)
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MaxShaderBytes) {
                "Shader source must be 256 KiB or smaller"
            }
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun validateSource(source: String) {
        require(UniformShaderPattern.containsMatchIn(source)) {
            "Shader must declare uniform shader content"
        }
        require(MainFunctionPattern.containsMatchIn(source)) {
            "Shader must define half4 main(vec2)"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(source)
        }
    }

    private fun writeAtomically(target: File, source: String) {
        shaderDirectory.mkdirs()
        val temporary = File(shaderDirectory, "${target.name}.tmp")
        try {
            temporary.writeText(source, StandardCharsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (error: IOException) {
            temporary.delete()
            throw IllegalArgumentException("Could not save shader", error)
        } catch (error: SecurityException) {
            temporary.delete()
            throw IllegalArgumentException("Could not save shader", error)
        }
    }

    private fun displayName(sourceName: String?): String {
        val name = sourceName
            ?.substringBeforeLast('.', sourceName)
            ?.trim()
            ?.take(64)
            .orEmpty()
        return name.ifBlank { "Imported shader" }
    }

    private fun containsUniform(source: String, name: String): Boolean =
        uniformPattern(name).containsMatchIn(stripComments(source))

    private fun stripComments(source: String): String =
        source.replace(BlockCommentPattern, "").replace(LineCommentPattern, "")

    private companion object {
        val UniformShaderPattern = Regex("uniform\\s+shader\\s+content\\s*;")
        val MainFunctionPattern = Regex("(?:half4|vec4)\\s+main\\s*\\(\\s*vec2\\b")
        val BlockCommentPattern = Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL))
        val LineCommentPattern = Regex("//[^\\n]*")

        fun uniformPattern(name: String): Regex =
            Regex("uniform\\s+(?:float|vec2)\\s+$name\\s*;")

        fun builtInDefinitions(): List<ShaderDefinition> =
            TerminalEffect.entries.map { it.toShaderDefinition() }
    }
}

internal fun loadSelectedShaderIds(preferences: android.content.SharedPreferences): List<String> {
    val saved = preferences.getString(SelectedShadersPreference, null)
        ?.split(ShaderIdSeparator)
        ?.filter(String::isNotBlank)
        .orEmpty()
    if (saved.isNotEmpty()) return saved

    return listOf("none")
}

internal fun saveSelectedShaderIds(
    preferences: android.content.SharedPreferences,
    shaderIds: List<String>
) {
    val selected = shaderIds.ifEmpty { listOf("none") }
    preferences.edit()
        .putString(SelectedShadersPreference, selected.joinToString(ShaderIdSeparator))
        .apply()
}
