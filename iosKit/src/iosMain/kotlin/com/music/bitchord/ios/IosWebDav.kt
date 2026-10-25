package com.music.bitchord.ios

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.appHttpClient
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.webdav.WebDavAuth
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A track on the WebDAV server, as the index keeps it between launches. */
@Serializable
private data class WebDavFile(val url: String, val name: String, val cover: String? = null)

@Serializable
private data class WebDavIndex(val root: String = "", val files: List<WebDavFile> = emptyList())

/**
 * The phone's WebDAV library: a folder on the listener's server (Nextcloud's Music folder, or
 * anything speaking WebDAV) walked with PROPFIND, one level at a time as there, since many
 * servers refuse `Depth: infinity`. A track is named by its file, "Artist - Title", its album by
 * the folder it is in, and its cover by a picture filed beside it; it streams over HTTPS with the
 * account's Basic login, which [WebDavAuth] adds to every request for the server's host, the
 * covers' included.
 *
 * The last listing is kept in Application Support so the page is up at once, and the server is
 * walked again at launch and on Refresh. The password is in the Keychain.
 */
@OptIn(ExperimentalEncodingApi::class, kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal object IosWebDav {
    private const val TAG = "IosWebDav"
    const val LINK = "local:webdav"
    private const val ID_PREFIX = "webdav:"
    private const val MAX_FILES = 10_000
    private const val SOURCE = "WebDAV"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy { appHttpClient { install(HttpTimeout) } }
    private val indexPath: String by lazy { appDataPath("webdav.json") }

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Why the last walk failed, or null: a server that answers with an error is not an empty one. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _configured = MutableStateFlow(isConfigured(IosSettings.webdavUrl.flow.value))
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    private var job: Job? = null

    fun isWebDav(videoId: String?) = videoId?.startsWith(ID_PREFIX) == true

    fun isWebDav(song: Song) = isWebDav(song.videoId)

    /** The file to stream for [videoId] and the login to stream it with. */
    fun stream(videoId: String): Pair<String, Map<String, String>>? {
        val url = videoId.removePrefix(ID_PREFIX).takeIf { isWebDav(videoId) && it.startsWith("http") } ?: return null
        val auth = authHeader(IosSettings.webdavUsername.flow.value, IosSettings.webdavPassword.flow.value)
        return url to (auth?.let { mapOf("Authorization" to it) } ?: emptyMap())
    }

    /** Reads the last listing and walks the server again. Called once, at launch. */
    fun restore() {
        publishAuth()
        val root = normalize(IosSettings.webdavUrl.flow.value)
        NSString.stringWithContentsOfFile(indexPath, NSUTF8StringEncoding, null)?.let { text ->
            runCatching { json.decodeFromString<WebDavIndex>(text) }.getOrNull()
                ?.takeIf { it.root == root }
                ?.let { _songs.value = it.files.map(::song) }
        }
        if (_configured.value) refresh()
    }

    fun refresh() {
        val root = normalize(IosSettings.webdavUrl.flow.value)
        if (!isConfigured(root)) return
        job?.cancel()
        job = scope.launch {
            _loading.value = true
            try {
                val files = walk(root, IosSettings.webdavUsername.flow.value, IosSettings.webdavPassword.flow.value)
                _songs.value = files.map(::song)
                _error.value = if (files.isEmpty()) tr("No audio files found on the WebDAV server. Check the URL in Sources.") else null
                val text = json.encodeToString(WebDavIndex.serializer(), WebDavIndex(root, files))
                NSString.create(string = text).writeToFile(indexPath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
                TrackLog.i(TAG, "${files.size} files listed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "listing failed: ${e.message}")
                _error.value = e.message ?: tr("Failed")
            } finally {
                _loading.value = false
            }
        }
    }

    /** Asks the server about the folder itself, without walking it. */
    suspend fun test(url: String, username: String, password: String): Result<Unit> = runCatching {
        val root = normalize(url)
        require(isConfigured(root)) { tr("Enter a http(s) server URL") }
        val code = propfind(root, "0", username, password).first
        if (code != 207 && code !in 200..299) error(tr("Server answered {0}", code))
    }

    /** Keeps the server and walks it. */
    fun save(url: String, username: String, password: String) {
        IosSettings.webdavUrl.set(normalize(url))
        IosSettings.webdavUsername.set(username.trim())
        IosSettings.webdavPassword.set(password)
        publishAuth()
        _configured.value = true
        _songs.value = emptyList()
        _error.value = null
        refresh()
    }

    /** Forgets the server and its listing. */
    fun disconnect() {
        job?.cancel()
        IosSettings.webdavUrl.set("")
        IosSettings.webdavUsername.set("")
        IosSettings.webdavPassword.set("")
        publishAuth()
        _configured.value = false
        _songs.value = emptyList()
        _error.value = null
        _loading.value = false
        platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(indexPath, null)
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun isConfigured(raw: String): Boolean {
        val url = normalize(raw)
        return (url.startsWith("http://", true) || url.startsWith("https://", true)) && hostOf(url) != null
    }

    private fun publishAuth() {
        val url = IosSettings.webdavUrl.flow.value
        WebDavAuth.update(
            hostOf(url).takeIf { isConfigured(url) },
            authHeader(IosSettings.webdavUsername.flow.value, IosSettings.webdavPassword.flow.value),
        )
    }

    private fun hostOf(url: String): String? =
        runCatching { Url(normalize(url)).host.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() && ' ' !in it }

    private fun authHeader(username: String, password: String): String? {
        if (username.isBlank() && password.isBlank()) return null
        return "Basic " + Base64.encode("$username:$password".encodeToByteArray())
    }

    /** Every audio file under [root], folder by folder, with the cover filed beside each. */
    private suspend fun walk(root: String, username: String, password: String): List<WebDavFile> {
        val audio = LinkedHashMap<String, Entry>()
        val imagesByDir = HashMap<String, MutableList<String>>()
        val dirs = ArrayDeque(listOf(root))
        val visited = HashSet<String>()
        while (dirs.isNotEmpty() && audio.size < MAX_FILES) {
            coroutineContext.ensureActive()
            val dir = dirs.removeFirst()
            if (!visited.add(dir.trimEnd('/').lowercase())) continue
            val (code, body) = propfind(dir, "1", username, password)
            if (code != 207 && code !in 200..299) error(tr("Listing failed with {0}", code))
            for (entry in parse(body, dir)) {
                if (entry.isCollection) {
                    if (entry.url.trimEnd('/').lowercase() !in visited) dirs.addLast(entry.url)
                    continue
                }
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                when (extension) {
                    in AUDIO -> audio.getOrPut(entry.url) { entry }
                    in IMAGES -> imagesByDir.getOrPut(dirOf(entry.url)) { mutableListOf() } += entry.url
                }
            }
        }
        return audio.values.map { entry ->
            WebDavFile(entry.url, entry.name, imagesByDir[dirOf(entry.url)]?.let(::pickCover))
        }
    }

    private suspend fun propfind(url: String, depth: String, username: String, password: String): Pair<Int, String> {
        val response = http.request(url) {
            method = HttpMethod("PROPFIND")
            header("Depth", depth)
            authHeader(username, password)?.let { header("Authorization", it) }
            setBody(TextContent(PROPFIND_BODY, ContentType.Application.Xml))
            timeout {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
        return response.status.value to response.bodyAsText()
    }

    private class Entry(val url: String, val name: String, val isCollection: Boolean)

    /**
     * A PROPFIND multistatus, read for the three things a library needs. Matched by local name,
     * whatever prefix the server gives the DAV namespace; the folder's own entry is dropped.
     */
    private fun parse(xml: String, dirUrl: String): List<Entry> =
        RESPONSE.findAll(xml).mapNotNull { match ->
            val block = match.groupValues[1]
            val href = element("href", block)?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val url = resolve(dirUrl, unescape(href))
            if (url.trimEnd('/').equals(dirUrl.trimEnd('/'), ignoreCase = true)) return@mapNotNull null
            val type = element("resourcetype", block).orEmpty()
            val isCollection = Regex("<(?:[\\w.-]+:)?collection\\b").containsMatchIn(type)
            val name = element("displayname", block)?.let(::unescape)?.trim()?.takeIf(String::isNotEmpty)
                ?: decoded(url.trimEnd('/').substringAfterLast('/'))
            Entry(url, name, isCollection)
        }.toList()

    private fun element(name: String, block: String): String? =
        Regex("<(?:[\\w.-]+:)?$name\\b[^>]*?(?:/>|>([\\s\\S]*?)</(?:[\\w.-]+:)?$name>)").find(block)?.groupValues?.get(1)

    private fun unescape(text: String): String =
        text.replace(Regex("&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos);")) { m ->
            when (val ref = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> (if (ref.startsWith("#x")) ref.drop(2).toIntOrNull(16) else ref.drop(1).toIntOrNull())
                    ?.let { code -> runCatching { Char(code).toString() }.getOrNull() } ?: m.value
            }
        }

    /** An href as a full URL: servers answer with the path alone, from the host's root. */
    private fun resolve(base: String, href: String): String {
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) return href
        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return href
        val pathStart = base.indexOf('/', schemeEnd + 3)
        val origin = if (pathStart < 0) base else base.substring(0, pathStart)
        return if (href.startsWith("/")) "$origin$href" else "${base.trimEnd('/')}/$href"
    }

    private fun decoded(segment: String): String = runCatching { segment.decodeURLPart() }.getOrDefault(segment)

    private fun dirOf(url: String): String = url.trimEnd('/').substringBeforeLast('/').lowercase()

    /** `cover.*` and its like over a stray photo; with several, the first by name. */
    private fun pickCover(urls: List<String>): String? = urls.sortedWith(
        compareBy<String>({ url -> COVER_STEMS.indexOfFirst { stem(url).startsWith(it) }.takeIf { it >= 0 } ?: Int.MAX_VALUE }, ::stem),
    ).firstOrNull()

    private fun stem(url: String) = decoded(url.trimEnd('/').substringAfterLast('/')).substringBeforeLast('.').lowercase()

    /** The row for a file: "Artist - Title" split as the phone splits it, the folder as its album. */
    private fun song(file: WebDavFile): Song {
        val base = file.name.substringBeforeLast('.').ifBlank { file.name }
        val parts = base.split(" - ", limit = 2)
        val (artist, title) = if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to base.trim()
        }
        val album = decoded(file.url.trimEnd('/').substringBeforeLast('/').substringAfterLast('/')).trim()
            .takeIf { it.isNotBlank() && !file.url.trimEnd('/').substringBeforeLast('/').equals(normalize(IosSettings.webdavUrl.flow.value), true) }
        return Song(
            videoId = ID_PREFIX + file.url,
            title = title,
            artist = artist ?: "Unknown Artist",
            thumbnailUrl = file.cover,
            durationText = null,
            albumName = album,
            localUri = file.url,
            playbackSource = SOURCE,
            playbackSourceType = PlaybackSourceType.BROWSE,
            playbackSourceId = LINK,
        )
    }

    private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontenttype/>
  </d:prop>
</d:propfind>"""

    private val RESPONSE = Regex("<(?:[\\w.-]+:)?response\\b[^>]*>([\\s\\S]*?)</(?:[\\w.-]+:)?response>")

    /** What AVPlayer or the FFmpeg player opens, as for files on the phone. */
    private val AUDIO = setOf("mp3", "m4a", "mp4", "aac", "flac", "wav", "aif", "aiff", "alac", "caf", "ogg", "oga", "opus", "webm")
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp")
    private val COVER_STEMS = listOf("cover", "folder", "front", "albumart", "album", "artwork")
}

/** A file of the listener's own, on this iPhone or on their server: no YouTube id behind it. */
internal fun isOwnFile(song: Song) = IosLocalMusic.isLocal(song) || IosWebDav.isWebDav(song)
