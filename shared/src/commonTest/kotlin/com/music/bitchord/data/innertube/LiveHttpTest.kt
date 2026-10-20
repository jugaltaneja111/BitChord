package com.music.bitchord.data.innertube

import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.testEnv
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plain HTTPS through the platform's client, printed in full when it fails: the first thing to
 * rule out when the live stream test fails. Talks to the network, so only with BITCHORD_LIVE_YOUTUBE=1.
 */
class LiveHttpTest {

    private fun describe(error: Throwable): String = generateSequence(error) { it.cause }
        .joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }

    private suspend fun fetch(label: String, client: HttpClient, url: String): Boolean = try {
        val response = client.get(url)
        println("LiveHttpTest $label: ${response.status} ${response.bodyAsText().length} chars")
        true
    } catch (e: Exception) {
        println("LiveHttpTest $label failed: ${describe(e)}")
        false
    }

    @Test
    fun `plain https requests succeed`() = runBlocking(Dispatchers.Default) {
        if (testEnv("BITCHORD_LIVE_YOUTUBE") != "1") return@runBlocking
        val results = listOf(
            appHttpClient().use { fetch("appHttpClient music.youtube.com", it, "https://music.youtube.com/") },
            appHttpClient().use { fetch("appHttpClient example.com", it, "https://example.com/") },
            HttpClient().use { fetch("default engine music.youtube.com", it, "https://music.youtube.com/") },
        )
        // Reported, not asserted: InnerTubeX resolves without a visitor id, and YouTube does not
        // always hand one out.
        try {
            println("LiveHttpTest visitorData present: ${Innertube.ensureVisitorData() != null}")
        } catch (e: Exception) {
            println("LiveHttpTest visitorData failed: ${describe(e)}")
        }
        assertTrue(results.all { it }, "see the LiveHttpTest lines above")
    }
}
