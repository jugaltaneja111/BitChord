package com.music.bitchord.ios.ffmpeg

import platform.Foundation.NSURL
import kotlin.math.ceil

/** One request of a DASH representation: a URL and, for SegmentList's mediaRange, its bytes. */
internal data class DashSegment(val url: String, val rangeStart: Long = -1, val rangeEnd: Long = -1)

/** What [Dash.plan] made of a manifest. */
internal sealed interface DashPlan {
    val durationMs: Long
    val bandwidth: Long

    /** SegmentBase, or a bare BaseURL: the representation is one file. */
    data class Single(val url: String, override val durationMs: Long, override val bandwidth: Long) : DashPlan

    /** SegmentTemplate or SegmentList: the initialization segment (when there is one) and then every media segment, in order. */
    data class Segments(val segments: List<DashSegment>, override val durationMs: Long, override val bandwidth: Long) : DashPlan

    fun describe(): String = when (this) {
        is Single -> "single file, $bandwidth bps, ${durationMs}ms"
        is Segments -> "${segments.size} segments, $bandwidth bps, ${durationMs}ms"
    }
}

/**
 * The bits of MPEG-DASH an on-demand audio stream uses: the first Period's highest-bandwidth
 * audio Representation, addressed by SegmentTemplate ($Number$/$Time$/$RepresentationID$/
 * $Bandwidth$, with a SegmentTimeline or a fixed duration), SegmentList, or SegmentBase/BaseURL.
 * BaseURLs nest, each resolved against the one above and the first against the manifest's URL.
 * Live (dynamic) manifests aren't played.
 */
internal object Dash {
    /** Codecs FFmpegBuild doesn't decode, passed over when anything else is offered. */
    private val UNPLAYABLE = listOf("ec-3", "ac-3", "ac-4", "mhm1", "mha1", "dtsc", "dtse", "dtsx")

    fun plan(text: String, manifestUrl: String): DashPlan {
        val mpd = Xml.parse(text).takeIf { it.name == "MPD" } ?: error("not a DASH manifest")
        if (mpd.attrs["type"] == "dynamic") error("live DASH isn't supported")
        val mpdBase = resolve(manifestUrl, mpd.child("BaseURL")?.text)
        val period = mpd.children("Period").firstOrNull() ?: error("DASH manifest has no Period")
        val durationSec = isoDuration(period.attrs["duration"]) ?: isoDuration(mpd.attrs["mediaPresentationDuration"])
        val periodBase = resolve(mpdBase, period.child("BaseURL")?.text)

        class Choice(val set: XmlNode, val rep: XmlNode, val bandwidth: Long, val playable: Boolean)
        val choices = period.children("AdaptationSet").flatMap { set ->
            set.children("Representation").mapNotNull { rep ->
                val mime = rep.attrs["mimeType"] ?: set.attrs["mimeType"] ?: ""
                val contentType = set.attrs["contentType"] ?: rep.attrs["contentType"] ?: ""
                val codecs = (rep.attrs["codecs"] ?: set.attrs["codecs"] ?: "").lowercase()
                val audio = mime.startsWith("audio") || contentType == "audio" ||
                    (mime.isEmpty() && contentType.isEmpty() && AUDIO_CODECS.any { codecs.startsWith(it) })
                if (!audio) return@mapNotNull null
                Choice(
                    set = set,
                    rep = rep,
                    bandwidth = rep.attrs["bandwidth"]?.toLongOrNull() ?: 0L,
                    playable = UNPLAYABLE.none { codecs.startsWith(it) },
                )
            }
        }
        val choice = (choices.filter { it.playable }.ifEmpty { choices }).maxByOrNull { it.bandwidth }
            ?: error("DASH manifest has no audio")
        val set = choice.set
        val rep = choice.rep
        val repBase = resolve(resolve(periodBase, set.child("BaseURL")?.text), rep.child("BaseURL")?.text)
        val repId = rep.attrs["id"] ?: ""
        val durationMs = durationSec?.let { (it * 1000).toLong() } ?: 0L

        val templates = listOfNotNull(period.child("SegmentTemplate"), set.child("SegmentTemplate"), rep.child("SegmentTemplate"))
        if (templates.isNotEmpty()) {
            val segments = templateSegments(templates, repBase, repId, choice.bandwidth, durationSec)
            return DashPlan.Segments(segments, durationMs, choice.bandwidth)
        }
        val list = rep.child("SegmentList") ?: set.child("SegmentList") ?: period.child("SegmentList")
        if (list != null) {
            val segments = ArrayList<DashSegment>()
            list.child("Initialization")?.let { init ->
                segments += segment(resolve(repBase, init.attrs["sourceURL"]), init.attrs["range"])
            }
            list.children("SegmentURL").forEach { url ->
                segments += segment(resolve(repBase, url.attrs["media"]), url.attrs["mediaRange"])
            }
            if (segments.isEmpty()) error("empty DASH SegmentList")
            return DashPlan.Segments(segments, durationMs, choice.bandwidth)
        }
        return DashPlan.Single(repBase, durationMs, choice.bandwidth)
    }

    private fun templateSegments(
        levels: List<XmlNode>,
        base: String,
        repId: String,
        bandwidth: Long,
        durationSec: Double?,
    ): List<DashSegment> {
        // The innermost level's attributes win; a timeline comes from the innermost that has one.
        fun attr(name: String): String? = levels.asReversed().firstNotNullOfOrNull { it.attrs[name] }
        val media = attr("media") ?: error("DASH SegmentTemplate has no media")
        val timescale = attr("timescale")?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
        val startNumber = attr("startNumber")?.toLongOrNull() ?: 1L
        val offset = attr("presentationTimeOffset")?.toLongOrNull() ?: 0L
        val timeline = levels.asReversed().firstNotNullOfOrNull { it.child("SegmentTimeline") }

        val segments = ArrayList<DashSegment>()
        val initialization = attr("initialization")
            ?: levels.asReversed().firstNotNullOfOrNull { it.child("Initialization")?.attrs?.get("sourceURL") }
        if (initialization != null) {
            segments += DashSegment(resolve(base, expand(initialization, repId, bandwidth, 0, 0)))
        }
        if (timeline != null) {
            val end = durationSec?.let { (it * timescale).toLong() + offset }
            var time = 0L
            var number = startNumber
            val entries = timeline.children("S")
            entries.forEachIndexed { index, s ->
                s.attrs["t"]?.toLongOrNull()?.let { time = it }
                val d = s.attrs["d"]?.toLongOrNull()?.takeIf { it > 0 } ?: error("DASH timeline entry without d")
                var repeat = s.attrs["r"]?.toLongOrNull() ?: 0L
                if (repeat < 0) {
                    // Repeats until the next entry's start, or the period's end.
                    val until = entries.getOrNull(index + 1)?.attrs?.get("t")?.toLongOrNull() ?: end
                        ?: error("DASH timeline repeats with no end")
                    repeat = ceil((until - time).toDouble() / d).toLong() - 1
                }
                for (i in 0..repeat) {
                    segments += DashSegment(resolve(base, expand(media, repId, bandwidth, number, time)))
                    time += d
                    number++
                }
            }
        } else {
            val duration = attr("duration")?.toLongOrNull()?.takeIf { it > 0 }
                ?: error("DASH SegmentTemplate has neither a timeline nor a duration")
            val seconds = durationSec ?: error("DASH manifest has no duration to count segments by")
            val count = ceil(seconds * timescale / duration).toLong().coerceAtLeast(1)
            for (i in 0 until count) {
                val number = startNumber + i
                segments += DashSegment(resolve(base, expand(media, repId, bandwidth, number, offset + i * duration)))
            }
        }
        return segments
    }

    private val IDENTIFIER = Regex("""\$(RepresentationID|Number|Time|Bandwidth)(%0(\d+)[dioxX])?\$|\$\$""")

    /** Fills a template's $…$ identifiers, with their `%0Nd` widths. */
    internal fun expand(template: String, repId: String, bandwidth: Long, number: Long, time: Long): String =
        IDENTIFIER.replace(template) { match ->
            if (match.value == "$$") return@replace "$"
            val value = when (match.groupValues[1]) {
                "RepresentationID" -> return@replace repId
                "Number" -> number
                "Time" -> time
                else -> bandwidth
            }
            val width = match.groupValues[3].toIntOrNull() ?: 0
            value.toString().padStart(width, '0')
        }

    private fun segment(url: String, range: String?): DashSegment {
        val bounds = range?.split('-')?.mapNotNull { it.trim().toLongOrNull() }
        return if (bounds != null && bounds.size == 2) DashSegment(url, bounds[0], bounds[1]) else DashSegment(url)
    }

    /** [relative] against [base], as a browser would; [base] itself when there's nothing to add. */
    internal fun resolve(base: String, relative: String?): String {
        val rel = relative?.trim().orEmpty()
        if (rel.isEmpty()) return base
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        val baseUrl = NSURL.URLWithString(base) ?: return rel
        return NSURL.URLWithString(rel, relativeToURL = baseUrl)?.absoluteString ?: rel
    }

    private val DURATION = Regex("""^P(?:(\d+(?:\.\d+)?)Y)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)W)?(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")

    /** An ISO 8601 duration (`PT3M25.5S`) in seconds. */
    internal fun isoDuration(text: String?): Double? {
        val match = DURATION.matchEntire(text?.trim() ?: return null) ?: return null
        val unit = doubleArrayOf(365.0 * 86400, 30.0 * 86400, 7.0 * 86400, 86400.0, 3600.0, 60.0, 1.0)
        var seconds = 0.0
        for (i in 0 until 7) seconds += (match.groupValues[i + 1].toDoubleOrNull() ?: 0.0) * unit[i]
        return seconds.takeIf { it > 0 }
    }

    private val AUDIO_CODECS = listOf("mp4a", "opus", "vorbis", "flac", "fLaC", "ec-3", "ac-3", "alac", "mp3")
}

/** An element of a parsed XML document: names without their namespace prefix. */
internal class XmlNode(val name: String, val attrs: Map<String, String>) {
    val childNodes = ArrayList<XmlNode>()
    private val textBuilder = StringBuilder()
    val text: String get() = textBuilder.toString().trim()

    fun child(name: String): XmlNode? = childNodes.firstOrNull { it.name == name }
    fun children(name: String): List<XmlNode> = childNodes.filter { it.name == name }

    internal fun appendText(text: String) {
        textBuilder.append(text)
    }
}

/** Just enough XML for a manifest: elements, attributes, text, entities; comments, PIs, DOCTYPE and CDATA. */
internal object Xml {
    fun parse(text: String): XmlNode {
        val root = XmlNode("#document", emptyMap())
        val stack = ArrayList<XmlNode>().apply { add(root) }
        var i = 0
        val n = text.length
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) {
                stack.last().appendText(decode(text.substring(i)))
                break
            }
            if (lt > i) stack.last().appendText(decode(text.substring(i, lt)))
            when {
                text.startsWith("<!--", lt) -> i = text.indexOf("-->", lt).let { if (it < 0) n else it + 3 }
                text.startsWith("<![CDATA[", lt) -> {
                    val end = text.indexOf("]]>", lt).let { if (it < 0) n else it }
                    stack.last().appendText(text.substring(lt + 9, end))
                    i = minOf(n, end + 3)
                }
                text.startsWith("<?", lt) -> i = text.indexOf("?>", lt).let { if (it < 0) n else it + 2 }
                text.startsWith("<!", lt) -> i = text.indexOf('>', lt).let { if (it < 0) n else it + 1 }
                text.startsWith("</", lt) -> {
                    val end = text.indexOf('>', lt).let { if (it < 0) n else it }
                    val name = localName(text.substring(lt + 2, end).trim())
                    // Pops to the matching element, forgiving a stray close.
                    val at = stack.indexOfLast { it.name == name }
                    if (at > 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                    i = end + 1
                }
                else -> {
                    val end = tagEnd(text, lt)
                    var body = text.substring(lt + 1, end)
                    val selfClosing = body.endsWith("/")
                    if (selfClosing) body = body.dropLast(1)
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    val node = XmlNode(localName(body.substring(0, nameEnd)), attributes(body.substring(nameEnd)))
                    stack.last().childNodes += node
                    if (!selfClosing) stack += node
                    i = end + 1
                }
            }
        }
        return root.childNodes.firstOrNull() ?: error("empty XML")
    }

    /** The `>` closing the tag at [start], skipping any inside quoted attribute values. */
    private fun tagEnd(text: String, start: Int): Int {
        var quote: Char? = null
        for (j in start + 1 until text.length) {
            val c = text[j]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
        }
        return text.length - 1
    }

    private val ATTRIBUTE = Regex("""([^\s=]+)\s*=\s*("([^"]*)"|'([^']*)')""")

    private fun attributes(text: String): Map<String, String> =
        ATTRIBUTE.findAll(text).associate { match ->
            val value = match.groups[3]?.value ?: match.groups[4]?.value ?: ""
            localName(match.groupValues[1]) to decode(value)
        }

    private fun localName(name: String): String = name.substringAfter(':')

    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""")

    private fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            when (val e = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    if (code <= 0xFFFF) code.toChar().toString() else {
                        val v = code - 0x10000
                        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
                    }
                }
            }
        }
    }
}
