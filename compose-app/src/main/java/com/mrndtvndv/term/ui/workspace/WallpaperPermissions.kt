package com.mrndtvndv.term.ui.workspace

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

private const val WallpaperReadPermissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION

/** Persists and releases read grants for wallpaper documents identified by URI string. */
internal interface WallpaperGrantStore {
    /** Takes a persistable read grant for [uri]; throws when the provider rejects it. */
    fun takeReadPermission(uri: String)

    /** Releases the persisted read grant for [uri]; throws when none is held. */
    fun releaseReadPermission(uri: String)
}

internal class ContentResolverWallpaperGrantStore(
    private val contentResolver: ContentResolver,
) : WallpaperGrantStore {
    override fun takeReadPermission(uri: String) {
        contentResolver.takePersistableUriPermission(Uri.parse(uri), WallpaperReadPermissionFlags)
    }

    override fun releaseReadPermission(uri: String) {
        contentResolver.releasePersistableUriPermission(Uri.parse(uri), WallpaperReadPermissionFlags)
    }
}
