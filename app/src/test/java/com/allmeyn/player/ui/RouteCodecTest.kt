package com.allmeyn.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCodecTest {
    @Test
    fun everyRouteSurvivesARoundTrip() {
        val routes = listOf(
            Route.AlbumRoute("yes|big generator|1987"),
            Route.ArtistRoute("Jan Hammer"),
            Route.FolderRoute("content://tree/primary:Music/Yes: Live"), // colons inside the value
            Route.PlaylistRoute("pl-123"),
            Route.NowPlayingRoute, Route.QueueRoute, Route.SearchRoute, Route.DspRoute, Route.SettingsRoute,
        )
        routes.forEach { assertEquals(it, decodeRoute(it.encode())) }
    }

    @Test
    fun emptyValuesAndUnknownKinds() {
        assertEquals(Route.ArtistRoute(""), decodeRoute(Route.ArtistRoute("").encode()))
        assertNull(decodeRoute("lyrics:abc")) // e.g. saved by a newer version
        assertNull(decodeRoute(""))
    }
}
