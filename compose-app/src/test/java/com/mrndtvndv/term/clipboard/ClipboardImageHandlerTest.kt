package com.mrndtvndv.term.clipboard

import android.content.SharedPreferences
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.ServerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipboardImageHandlerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun getExtensionForMimeType_returnsExpectedExtensions() {
        assertEquals("png", ClipboardImageHandler.getExtensionForMimeType("image/png"))
        assertEquals("jpg", ClipboardImageHandler.getExtensionForMimeType("image/jpeg"))
        assertEquals("jpg", ClipboardImageHandler.getExtensionForMimeType("image/jpg"))
        assertEquals("webp", ClipboardImageHandler.getExtensionForMimeType("image/webp"))
        assertEquals("gif", ClipboardImageHandler.getExtensionForMimeType("image/gif"))
        assertEquals("svg", ClipboardImageHandler.getExtensionForMimeType("image/svg+xml"))
    }

    @Test
    fun pruneOldImages_removesExpiredFiles() {
        val dir = tempFolder.newFolder("clipboard_test")
        val now = 100_000_000L

        val expiredFile = File(dir, "clipboard_old.png").apply {
            writeText("old")
            setLastModified(now - ClipboardImageHandler.MAX_IMAGE_RETENTION_AGE_MS - 1000L)
        }

        val freshFile = File(dir, "clipboard_new.png").apply {
            writeText("new")
            setLastModified(now - 1000L)
        }

        val nonClipboardFile = File(dir, "user_document.txt").apply {
            writeText("keep me")
            setLastModified(now - ClipboardImageHandler.MAX_IMAGE_RETENTION_AGE_MS - 5000L)
        }

        ClipboardImageHandler.pruneOldImages(dir, nowMs = now)

        assertFalse(expiredFile.exists())
        assertTrue(freshFile.exists())
        assertTrue(nonClipboardFile.exists())
    }

    @Test
    fun pruneOldImages_enforcesMaximumFileCount() {
        val dir = tempFolder.newFolder("clipboard_count_test")
        val now = 100_000_000L

        // Create 25 files
        val files = (1..25).map { index ->
            File(dir, "clipboard_$index.png").apply {
                writeText("img $index")
                setLastModified(now - (25 - index) * 1000L)
            }
        }

        ClipboardImageHandler.pruneOldImages(dir, nowMs = now)

        val remaining = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(ClipboardImageHandler.MAX_IMAGE_RETENTION_COUNT, remaining.size)

        // Oldest 5 files should have been deleted (clipboard_1 to clipboard_5)
        for (i in 1..5) {
            assertFalse(files[i - 1].exists())
        }
        // Newest 20 files should still exist (clipboard_6 to clipboard_25)
        for (i in 6..25) {
            assertTrue(files[i - 1].exists())
        }
    }

    @Test
    fun pruneOldImages_respectsCustomMaxRetentionCount() {
        val dir = tempFolder.newFolder("clipboard_custom_count_test")
        val now = 100_000_000L

        // Create 10 files
        val files = (1..10).map { index ->
            File(dir, "clipboard_$index.png").apply {
                writeText("img $index")
                setLastModified(now - (10 - index) * 1000L)
            }
        }

        ClipboardImageHandler.pruneOldImages(dir, maxRetentionCount = 4, nowMs = now)

        val remaining = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(4, remaining.size)

        // Oldest 6 files should have been deleted
        for (i in 1..6) {
            assertFalse(files[i - 1].exists())
        }
        // Newest 4 files should still exist
        for (i in 7..10) {
            assertTrue(files[i - 1].exists())
        }
    }

    @Test
    fun pruneOldImages_keepsAtLeastOneFileForInvalidLimit() {
        val dir = tempFolder.newFolder("clipboard_invalid_count_test")
        val file = File(dir, "clipboard_1.png").apply {
            writeText("img")
            setLastModified(100_000_000L)
        }

        ClipboardImageHandler.pruneOldImages(dir, maxRetentionCount = 0, nowMs = 100_000_000L)

        assertTrue(file.exists())
    }

    @Test
    fun selectRemoteImageFilesForDeletion_appliesAgeAndCountPolicies() {
        val now = 100_000_000L
        val expired = SftpFile(
            "clipboard_expired.png",
            "/tmp/clipboard_expired.png",
            false,
            1,
            0,
            now - ClipboardImageHandler.MAX_IMAGE_RETENTION_AGE_MS - 1,
        )
        val newest = SftpFile(
            "clipboard_newest.png",
            "/tmp/clipboard_newest.png",
            false,
            1,
            0,
            now - 1,
        )
        val older = SftpFile(
            "clipboard_older.png",
            "/tmp/clipboard_older.png",
            false,
            1,
            0,
            now - 2,
        )
        val directory = SftpFile("clipboard_dir", "/tmp/clipboard_dir", true, 0, 0, now)

        val deleted = ClipboardImageHandler.selectRemoteImageFilesForDeletion(
            files = listOf(expired, newest, older, directory),
            maxRetentionCount = 1,
            nowMs = now,
        )

        assertEquals(listOf(expired, older), deleted)
    }

    @Test
    fun serverConfig_defaultsAndCustomImagePasteSettings() {
        val defaultConfig = ServerConfig(label = "Default Server")
        assertFalse(defaultConfig.imagePasteEnabled)
        assertFalse(defaultConfig.isImagePasteActive)
        assertNull(defaultConfig.imagePasteDirectory)
        assertTrue(defaultConfig.imagePasteAutoCleanup)
        assertEquals(20, defaultConfig.imagePasteMaxFiles)

        val noDirConfig = ServerConfig(label = "Enabled Without Dir", imagePasteEnabled = true)
        assertFalse(noDirConfig.isImagePasteActive)

        val customConfig = ServerConfig(
            label = "Custom Server",
            imagePasteEnabled = true,
            imagePasteDirectory = "/custom/path",
            imagePasteAutoCleanup = false,
            imagePasteMaxFiles = 50,
        )
        assertTrue(customConfig.imagePasteEnabled)
        assertTrue(customConfig.isImagePasteActive)
        assertEquals("/custom/path", customConfig.imagePasteDirectory)
        assertFalse(customConfig.imagePasteAutoCleanup)
        assertEquals(50, customConfig.imagePasteMaxFiles)

        val invalidConfig = ServerConfig(label = "Invalid", imagePasteMaxFiles = 0)
        assertEquals(1, invalidConfig.safeImagePasteMaxFiles)
    }

    @Test
    fun serverRepository_persistsImagePasteSettings() {
        val prefs = FakeSharedPreferences()
        val repo = ServerRepository(prefs)

        val server = ServerConfig(
            id = "server-1",
            label = "My VPS",
            host = "vps.example.com",
            imagePasteEnabled = true,
            imagePasteDirectory = "/home/user/cache",
            imagePasteAutoCleanup = false,
            imagePasteMaxFiles = 10,
        )
        repo.add(server)

        val loaded = repo.get("server-1")
        assertEquals(true, loaded?.imagePasteEnabled)
        assertEquals("/home/user/cache", loaded?.imagePasteDirectory)
        assertEquals(false, loaded?.imagePasteAutoCleanup)
        assertEquals(10, loaded?.imagePasteMaxFiles)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            // No-op for test fake
        }
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            // No-op for test fake
        }

        private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private val toRemove = mutableSetOf<String>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) toRemove.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                toRemove.forEach { data.remove(it) }
                data.putAll(temp)
            }
        }
    }
}
