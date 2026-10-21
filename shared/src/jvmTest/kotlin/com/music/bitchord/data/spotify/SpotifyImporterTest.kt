package com.music.bitchord.data.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyImporterTest {
    private val id = "37i9dQZF1DXcBWIGoYBM5M"

    @Test
    fun linksAndUrisGiveThePlaylistId() {
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("  https://open.spotify.com/playlist/$id?si=abc123 "))
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/intl-de/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("http://spotify.com/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("spotify:playlist:$id"))
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(SpotifyImporter.extractPlaylistId(""))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/album/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://evilspotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("ftp://open.spotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/short"))
        assertNull(SpotifyImporter.extractPlaylistId("open.spotify.com/playlist/$id"))
    }
}
