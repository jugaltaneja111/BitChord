package com.music.bitchord.data.innertube

import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.testEnv
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Resolves a real YouTube stream and fetches its first bytes: the go/no-go for playback on a
 * platform. Talks to YouTube, so it only runs with BITCHORD_LIVE_YOUTUBE=1.
 */
class LiveStreamResolveTest {

    private class MemoryStore : InnerTubeXResolver.ConfigStore {
        private val values = mutableMapOf<String, Any>()
        override fun getString(key: String) = values[key] as? String
        override fun getLong(key: String) = values[key] as? Long
        override fun putString(key: String, value: String) { values[key] = value }
        override fun putLong(key: String, value: Long) { values[key] = value }
    }

    private class MemoryPlayers : InnerTubeXResolver.PlayerCache {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test
    fun `a public track resolves to an AAC stream googlevideo serves`() = runBlocking {
        if (testEnv("BITCHORD_LIVE_YOUTUBE") != "1") {
            println("LiveStreamResolveTest skipped: set BITCHORD_LIVE_YOUTUBE=1 to run it")
            return@runBlocking
        }
        // No PoToken minter, as on a platform without one: only the clients that need none are tried.
        InnerTubeXResolver.init(MemoryPlayers(), MemoryStore(), poTokenProvider = null)

        // AAC in MP4, the format AVFoundation plays natively.
        val stream = InnerTubeXResolver.extract(VIDEO_ID, maxKbps = 320, requireM4a = true)
        assertNotNull(stream, "no client returned a stream for $VIDEO_ID")
        println("LiveStreamResolveTest: ${stream.clientName}/${stream.profileId} ${stream.mimeType} ${stream.kbps} kbps")
        assertTrue(stream.mimeType.startsWith("audio/mp4"), "expected AAC in MP4, got ${stream.mimeType}")

        val bytes = appHttpClient().use { client ->
            client.get(stream.url) {
                stream.headers.forEach { (name, value) -> header(name, value) }
                header("Range", "bytes=0-1023")
            }.bodyAsBytes()
        }
        assertTrue(bytes.size >= 8, "googlevideo returned ${bytes.size} bytes")
        // Every MP4 opens with an `ftyp` box: a 4-byte size, then the type.
        assertEquals("ftyp", bytes.copyOfRange(4, 8).decodeToString())
    }

    private companion object {
        /** A public, long-lived music video that is not age-restricted. */
        const val VIDEO_ID = "dQw4w9WgXcQ"
    }
}
