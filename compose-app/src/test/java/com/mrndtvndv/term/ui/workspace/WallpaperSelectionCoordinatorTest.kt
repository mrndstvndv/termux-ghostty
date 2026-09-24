package com.mrndtvndv.term.ui.workspace

import com.mrndtvndv.term.testutil.MemorySharedPreferences
import com.mrndtvndv.term.ui.prefs.UserPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperSelectionCoordinatorTest {

    private class FakeWallpaperGrantStore : WallpaperGrantStore {
        val taken = mutableListOf<String>()
        val released = mutableListOf<String>()
        var failTake = false
        var failRelease = false

        override fun takeReadPermission(uri: String) {
            if (failTake) throw SecurityException("grant refused")
            taken += uri
        }

        override fun releaseReadPermission(uri: String) {
            if (failRelease) throw SecurityException("grant not held")
            released += uri
        }
    }

    private val grants = FakeWallpaperGrantStore()
    private val warnings = mutableListOf<String>()
    private val coordinator = WallpaperSelectionCoordinator<String>(grants) { message, _ ->
        warnings += message
    }

    @Test
    fun `successful selection commits the new wallpaper and releases the previous grant`() = runTest {
        var committed: String? = null

        val outcome = coordinator.select(
            previousUri = "content://old",
            newUri = "content://new",
            decode = { "pixels" },
            commit = { committed = it },
        )

        assertEquals(WallpaperSelectionOutcome.Committed, outcome)
        assertEquals("pixels", committed)
        assertEquals(listOf("content://new"), grants.taken)
        assertEquals(listOf("content://old"), grants.released)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `selecting the same uri keeps the existing grant`() = runTest {
        val outcome = coordinator.select(
            previousUri = "content://same",
            newUri = "content://same",
            decode = { "pixels" },
            commit = {},
        )

        assertEquals(WallpaperSelectionOutcome.Committed, outcome)
        assertTrue(grants.released.isEmpty())
    }

    @Test
    fun `selection without a previous wallpaper releases nothing`() = runTest {
        val outcome = coordinator.select(
            previousUri = null,
            newUri = "content://new",
            decode = { "pixels" },
            commit = {},
        )

        assertEquals(WallpaperSelectionOutcome.Committed, outcome)
        assertTrue(grants.released.isEmpty())
    }

    @Test
    fun `unreadable image does not commit and preserves the previous grant`() = runTest {
        var committed = false

        val outcome = coordinator.select(
            previousUri = "content://old",
            newUri = "content://broken",
            decode = { null },
            commit = { committed = true },
        )

        assertEquals(WallpaperSelectionOutcome.ImageUnreadable, outcome)
        assertFalse(committed)
        assertTrue(grants.released.none { it == "content://old" })
        assertEquals(listOf("content://broken"), grants.released)
    }

    @Test
    fun `a decode exception is treated as an unreadable image`() = runTest {
        val outcome = coordinator.select(
            previousUri = "content://old",
            newUri = "content://broken",
            decode = { throw IllegalStateException("corrupt") },
            commit = { error("commit must not run for a corrupt image") },
        )

        assertEquals(WallpaperSelectionOutcome.ImageUnreadable, outcome)
        assertTrue(grants.released.none { it == "content://old" })
        assertEquals(listOf("content://broken"), grants.released)
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a refused grant keeps the previous wallpaper and commits nothing`() = runTest {
        grants.failTake = true
        var committed = false

        val outcome = coordinator.select(
            previousUri = "content://old",
            newUri = "content://new",
            decode = { "pixels" },
            commit = { committed = true },
        )

        assertEquals(WallpaperSelectionOutcome.GrantDenied, outcome)
        assertFalse(committed)
        assertTrue(grants.taken.isEmpty())
        assertTrue(grants.released.isEmpty())
        assertEquals(1, warnings.size)
    }

    @Test
    fun `a stale decode cannot replace a newer selection`() = runTest {
        val firstDecode = CompletableDeferred<String?>()
        var committed: String? = null

        val first = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://first",
                decode = { firstDecode.await() },
                commit = { committed = "first" },
            )
        }
        advanceUntilIdle()

        val second = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://second",
                decode = { "second" },
                commit = { committed = "second" },
            )
        }
        advanceUntilIdle()

        firstDecode.complete("first")
        advanceUntilIdle()

        assertEquals(WallpaperSelectionOutcome.Superseded, first.await())
        assertEquals(WallpaperSelectionOutcome.Committed, second.await())
        assertEquals("second", committed)
        assertEquals(listOf("content://first", "content://second"), grants.taken)
        assertEquals(1, grants.released.count { it == "content://old" })
        assertTrue(grants.released.contains("content://first"))
    }

    @Test
    fun `a superseded request for the same document does not drop its grant`() = runTest {
        val firstDecode = CompletableDeferred<String?>()

        val first = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { firstDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        val second = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { "same" },
                commit = {},
            )
        }
        advanceUntilIdle()

        firstDecode.complete("same")
        advanceUntilIdle()

        assertEquals(WallpaperSelectionOutcome.Superseded, first.await())
        assertEquals(WallpaperSelectionOutcome.Committed, second.await())
        assertEquals(listOf("content://old"), grants.released)
    }

    @Test
    fun `a failing decode for a superseded request reports superseded and keeps the newer grant`() = runTest {
        val firstDecode = CompletableDeferred<String?>()
        val secondDecode = CompletableDeferred<String?>()

        val first = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { firstDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        val second = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { secondDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        firstDecode.complete(null)
        advanceUntilIdle()
        assertEquals(WallpaperSelectionOutcome.Superseded, first.await())
        assertTrue(grants.released.none { it == "content://same" })

        secondDecode.complete("same")
        advanceUntilIdle()
        assertEquals(WallpaperSelectionOutcome.Committed, second.await())
        assertEquals(listOf("content://old"), grants.released)
    }

    @Test
    fun `a failing decode for a superseded different document reports superseded`() = runTest {
        val firstDecode = CompletableDeferred<String?>()
        var firstCommit = false

        val first = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://first",
                decode = { firstDecode.await() },
                commit = { firstCommit = true },
            )
        }
        advanceUntilIdle()

        val second = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://second",
                decode = { "second" },
                commit = {},
            )
        }
        advanceUntilIdle()

        firstDecode.complete(null)
        advanceUntilIdle()

        assertEquals(WallpaperSelectionOutcome.Superseded, first.await())
        assertEquals(WallpaperSelectionOutcome.Committed, second.await())
        assertFalse(firstCommit)
        assertTrue(grants.released.contains("content://first"))
        assertEquals(1, grants.released.count { it == "content://old" })
    }

    @Test
    fun `clear prevents an in-flight decode from committing`() = runTest {
        val decode = CompletableDeferred<String?>()
        var committed = false

        val selection = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://pending",
                decode = { decode.await() },
                commit = { committed = true },
            )
        }
        advanceUntilIdle()

        coordinator.clear("content://old")
        decode.complete("pending")
        advanceUntilIdle()

        assertEquals(WallpaperSelectionOutcome.Superseded, selection.await())
        assertFalse(committed)
        assertTrue(grants.released.contains("content://old"))
        assertTrue(grants.released.contains("content://pending"))
    }

    @Test
    fun `stale cleanup after clear does not release a newer selection grant`() = runTest {
        val staleDecode = CompletableDeferred<String?>()

        val stale = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://stale",
                decode = { staleDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        coordinator.clear("content://old")

        val newer = async {
            coordinator.select(
                previousUri = null,
                newUri = "content://new",
                decode = { "new" },
                commit = {},
            )
        }
        advanceUntilIdle()
        assertEquals(WallpaperSelectionOutcome.Committed, newer.await())

        staleDecode.complete("stale")
        advanceUntilIdle()

        assertEquals(WallpaperSelectionOutcome.Superseded, stale.await())
        assertTrue(grants.released.none { it == "content://new" })
        assertEquals(listOf("content://old", "content://stale"), grants.released)
    }

    @Test
    fun `a cancelled decode releases its own unused grant without surfacing a failure`() = runTest {
        val decode = CompletableDeferred<String?>()

        val selection = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://cancelled",
                decode = { decode.await() },
                commit = { error("a cancelled request must not commit") },
            )
        }
        advanceUntilIdle()

        selection.cancel()
        advanceUntilIdle()

        assertTrue(selection.isCancelled)
        assertTrue(grants.released.contains("content://cancelled"))
        assertTrue(grants.released.none { it == "content://old" })
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a cancelled stale request does not drop a newer same-document grant`() = runTest {
        val firstDecode = CompletableDeferred<String?>()
        val secondDecode = CompletableDeferred<String?>()

        val first = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { firstDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        val second = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { secondDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        first.cancel()
        advanceUntilIdle()

        assertTrue(first.isCancelled)
        assertTrue(grants.released.none { it == "content://same" })

        secondDecode.complete("same")
        advanceUntilIdle()
        assertEquals(WallpaperSelectionOutcome.Committed, second.await())
        assertEquals(listOf("content://old"), grants.released)
    }

    @Test
    fun `a stale request cannot release the grant of the committed document`() = runTest {
        val firstDecode = CompletableDeferred<String?>()
        val thirdDecode = CompletableDeferred<String?>()

        val first = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { firstDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        // The same document is committed by a newer request while the first is still decoding.
        val second = async {
            coordinator.select(
                previousUri = "content://old",
                newUri = "content://same",
                decode = { "same" },
                commit = {},
            )
        }
        advanceUntilIdle()

        // A third selection moves the latest target away from the committed document.
        val third = async {
            coordinator.select(
                previousUri = "content://same",
                newUri = "content://third",
                decode = { thirdDecode.await() },
                commit = {},
            )
        }
        advanceUntilIdle()

        firstDecode.complete("same")
        advanceUntilIdle()

        assertEquals(WallpaperSelectionOutcome.Superseded, first.await())
        assertTrue(grants.released.none { it == "content://same" })

        thirdDecode.complete(null)
        advanceUntilIdle()
        assertEquals(WallpaperSelectionOutcome.ImageUnreadable, third.await())
        assertTrue(second.isCompleted)
    }

    @Test
    fun `clear releases the persisted grant and ignores empty values`() {
        coordinator.clear("content://wallpaper")
        coordinator.clear(null)
        coordinator.clear("")

        assertEquals(listOf("content://wallpaper"), grants.released)
    }

    @Test
    fun `a failed release is logged without throwing`() {
        grants.failRelease = true

        coordinator.clear("content://wallpaper")

        assertEquals(1, warnings.size)
    }

    @Test
    fun `unreadable image preserves persisted preferences and the previous grant`() = runTest {
        val prefs = MemorySharedPreferences()
        val userPrefs = UserPrefs().apply {
            init(prefs)
            setWallpaper("content://old", "old.png", id = 1L, prefs = prefs)
        }
        val selection = WallpaperSelectionCoordinator<String>(grants) { message, _ -> warnings += message }

        val outcome = selection.select(
            previousUri = userPrefs.wallpaperUri.value,
            newUri = "content://broken",
            decode = { null },
            commit = { userPrefs.setWallpaper("content://broken", "broken.png", id = 2L, prefs = prefs) },
        )

        assertEquals(WallpaperSelectionOutcome.ImageUnreadable, outcome)
        assertEquals("content://old", userPrefs.wallpaperUri.value)
        assertEquals("old.png", userPrefs.wallpaperName.value)
        assertEquals(1L, userPrefs.wallpaperId.value)
        assertEquals("content://old", prefs.getString("terminal_wallpaper_uri", null))
        assertEquals("old.png", prefs.getString("terminal_wallpaper_name", null))
        assertEquals(1L, prefs.getLong("terminal_wallpaper_id", 0L))
        assertTrue(grants.released.none { it == "content://old" })
    }

    @Test
    fun `successful selection persists the new preferences and releases the previous grant`() = runTest {
        val prefs = MemorySharedPreferences()
        val userPrefs = UserPrefs().apply {
            init(prefs)
            setWallpaper("content://old", "old.png", id = 1L, prefs = prefs)
        }
        val selection = WallpaperSelectionCoordinator<String>(grants) { message, _ -> warnings += message }

        val outcome = selection.select(
            previousUri = userPrefs.wallpaperUri.value,
            newUri = "content://new",
            decode = { "pixels" },
            commit = { userPrefs.setWallpaper("content://new", "new.png", id = 2L, prefs = prefs) },
        )

        assertEquals(WallpaperSelectionOutcome.Committed, outcome)
        assertEquals("content://new", userPrefs.wallpaperUri.value)
        assertEquals("new.png", userPrefs.wallpaperName.value)
        assertEquals(2L, userPrefs.wallpaperId.value)
        assertEquals("content://new", prefs.getString("terminal_wallpaper_uri", null))
        assertEquals(listOf("content://new"), grants.taken)
        assertEquals(listOf("content://old"), grants.released)
    }
}
