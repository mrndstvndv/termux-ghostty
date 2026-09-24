package com.mrndtvndv.term.ui.workspace

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

private const val WallpaperSelectionLogTag = "TerminalWallpaper"

/** Result of a single wallpaper selection attempt. */
internal enum class WallpaperSelectionOutcome {
    /** The decoded image was committed and the previous grant released. */
    Committed,

    /** A newer selection started first, so this result was discarded. */
    Superseded,

    /** The provider refused a persistable read grant for the picked document. */
    GrantDenied,

    /** The picked document could not be decoded into a usable image. */
    ImageUnreadable,
}

/**
 * Sequences wallpaper selection so a broken image can never replace a working one.
 *
 * A selection takes the read grant, decodes the document through the injected
 * [select] `decode` seam (off the main thread at the call site), and only then
 * commits the new wallpaper and releases the previous grant. When the decode
 * fails, the previous wallpaper and its grant are left untouched and the unused
 * grant taken for the rejected document is released.
 *
 * [select] is generic over the decoded artifact and free of Android bitmap APIs,
 * so the ordering guarantees are unit-testable.
 *
 * Concurrency: [select] and [clear] are expected to be called from the main
 * thread. A single in-flight selection is assumed at commit time; any request
 * that finishes after a newer request started yields [WallpaperSelectionOutcome.Superseded].
 */
@Suppress("TooGenericExceptionCaught")
internal class WallpaperSelectionCoordinator<Decoded>(
    private val grants: WallpaperGrantStore,
    private val logWarning: (String, Throwable) -> Unit = { message, error ->
        Log.w(WallpaperSelectionLogTag, message, error)
    },
) {
    private val requestCounter = AtomicLong(0)
    private var latestUri: String? = null
    private var committedUri: String? = null

    /**
     * Takes the read grant for [newUri], decodes it through [decode], and commits
     * the decoded image through [commit] only when it is valid and still the
     * newest selection.
     */
    suspend fun select(
        previousUri: String?,
        newUri: String,
        decode: suspend () -> Decoded?,
        commit: (Decoded) -> Unit,
    ): WallpaperSelectionOutcome {
        val requestId = requestCounter.incrementAndGet()
        latestUri = newUri

        if (!takeGrant(newUri)) return WallpaperSelectionOutcome.GrantDenied

        val decoded = try {
            decode()
        } catch (cancellation: CancellationException) {
            releaseUnusedGrant(newUri, previousUri, requestId)
            throw cancellation
        } catch (error: Exception) {
            logWarning("Failed to decode wallpaper: $newUri", error)
            null
        }
        return when {
            requestId != requestCounter.get() -> {
                releaseUnusedGrant(newUri, previousUri, requestId)
                WallpaperSelectionOutcome.Superseded
            }
            decoded == null -> {
                releaseUnusedGrant(newUri, previousUri, requestId)
                WallpaperSelectionOutcome.ImageUnreadable
            }
            else -> {
                commit(decoded)
                committedUri = newUri
                if (previousUri != null && previousUri != newUri) {
                    releaseQuietly(previousUri)
                }
                WallpaperSelectionOutcome.Committed
            }
        }
    }

    /**
     * Releases the grant taken by a request that will not commit, applying the
     * same-document, latest, and committed protections so a grant still in use
     * by another request or the active wallpaper is never dropped.
     */
    private fun releaseUnusedGrant(newUri: String, previousUri: String?, requestId: Long) {
        if (requestId == requestCounter.get()) {
            releaseTakenGrant(newUri, previousUri)
        } else {
            releaseSupersededGrant(newUri, previousUri)
        }
    }

    /**
     * Invalidates any in-flight selection so it cannot commit a wallpaper after
     * the user cleared it, then releases the committed grant.
     */
    fun clear(uri: String?) {
        requestCounter.incrementAndGet()
        latestUri = null
        committedUri = null
        if (uri.isNullOrEmpty()) return
        releaseQuietly(uri)
    }

    private fun takeGrant(uri: String): Boolean = try {
        grants.takeReadPermission(uri)
        true
    } catch (error: Exception) {
        logWarning("Failed to persist wallpaper read permission: $uri", error)
        false
    }

    /** Releases the grant taken for a document that turned out to be unusable. */
    private fun releaseTakenGrant(newUri: String, previousUri: String?) {
        if (newUri == previousUri || newUri == committedUri) return
        releaseQuietly(newUri)
    }

    /**
     * Releases the grant of a superseded request, unless a newer request targets
     * the same document or that document is the wallpaper that is still committed.
     */
    private fun releaseSupersededGrant(newUri: String, previousUri: String?) {
        if (newUri == previousUri || newUri == latestUri || newUri == committedUri) return
        releaseQuietly(newUri)
    }

    private fun releaseQuietly(uri: String) {
        try {
            grants.releaseReadPermission(uri)
        } catch (error: Exception) {
            logWarning("Failed to release wallpaper read permission: $uri", error)
        }
    }
}
