package com.allmeyn.player.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Screens pushed on top of the tabs. */
internal sealed interface Route {
    data class AlbumRoute(val key: String) : Route
    data class ArtistRoute(val name: String) : Route
    data class FolderRoute(val path: String) : Route
    data class PlaylistRoute(val id: String) : Route
    data object NowPlayingRoute : Route
    data object QueueRoute : Route
    data object SearchRoute : Route
    data object DspRoute : Route
    data object SettingsRoute : Route
}

/** One line per route, so the screen stack survives folding, rotating and process death. */
internal fun Route.encode(): String = when (this) {
    is Route.AlbumRoute -> "album:$key"
    is Route.ArtistRoute -> "artist:$name"
    is Route.FolderRoute -> "folder:$path"
    is Route.PlaylistRoute -> "playlist:$id"
    Route.NowPlayingRoute -> "nowplaying:"
    Route.QueueRoute -> "queue:"
    Route.SearchRoute -> "search:"
    Route.DspRoute -> "dsp:"
    Route.SettingsRoute -> "settings:"
}

/** The inverse of [encode]; null for anything unknown (e.g. saved by a newer version). */
internal fun decodeRoute(s: String): Route? {
    val kind = s.substringBefore(':')
    val value = s.substringAfter(':', "")
    return when (kind) {
        "album" -> Route.AlbumRoute(value)
        "artist" -> Route.ArtistRoute(value)
        "folder" -> Route.FolderRoute(value)
        "playlist" -> Route.PlaylistRoute(value)
        "nowplaying" -> Route.NowPlayingRoute
        "queue" -> Route.QueueRoute
        "search" -> Route.SearchRoute
        "dsp" -> Route.DspRoute
        "settings" -> Route.SettingsRoute
        else -> null
    }
}

internal val RouteStackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> mutableStateListOf<Route>().apply { addAll(saved.mapNotNull(::decodeRoute)) } },
)
