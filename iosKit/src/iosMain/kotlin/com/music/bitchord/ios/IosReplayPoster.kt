package com.music.bitchord.ios

import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.graphics.ColorUtils
import com.music.bitchord.ui.graphics.paletteSwatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.Image as SkiaImage

/**
 * The Replay as one picture, drawn to an image you can send — the phone's
 * `renderReplayPoster`, band for band.
 *
 * Drawn rather than screenshotted for the phone's reasons: the thing people
 * want to send is the whole year, not the one card on screen, and a capture
 * would carry the status bar and this particular phone's letterboxing.
 *
 * ## Why Skia directly
 *
 * Compose here *is* Skia, so there is no hardware-bitmap caveat to avoid as
 * there is on Android — but composing offscreen still means a composition with
 * no window, and the layout below is fixed and needs no measurement pass. So it
 * draws straight onto a raster [Surface], off the main thread, the way the
 * phone draws onto a canvas. Text goes through Skia's paragraph layout rather
 * than single-font text lines, because that is the layer with font fallback:
 * a Japanese title or an emoji in a playlist-born song name is drawn in a face
 * that has the glyph instead of as a row of boxes.
 *
 * 1080 × 1920, the 9:16 the story cards are held to.
 */
internal suspend fun renderReplayPoster(
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
    /** Which story card to draw, or null for the whole Replay — see the phone's KDoc. */
    page: ReplayStoryPage? = null,
): SkiaImage = coroutineScope {
    val songs = summary.songRows(POSTER_ROWS)
    val artists = summary.artistRows(POSTER_ROWS)
    val albums = summary.albumRows(POSTER_ALBUMS)
    val lit = page?.let { summary.storyArtwork(it) } ?: songs.firstOrNull()?.artworkUrl

    // Everything any band could ask for, fetched together: these are a dozen
    // small reads that are almost always in Coil's disk cache already, and
    // started at once a cold cache costs one round trip rather than twelve.
    // A card's own rows are added too — the album card goes five deep where
    // the poster's album strip stops at three.
    val urls = buildList {
        addAll((songs + artists + albums).mapNotNull { it.artworkUrl })
        lit?.let(::add)
        when (page) {
            ReplayStoryPage.ALBUMS -> addAll(summary.albumRows(CARD_ROWS).mapNotNull { it.artworkUrl })
            ReplayStoryPage.INTRO, ReplayStoryPage.MINUTES -> addAll(collageCovers(summary).let { it.first + it.second })
            else -> Unit
        }
    }.distinct()
    val bitmaps = urls
        .map { url -> async(Dispatchers.Default) { url to loadCover(url) } }
        .awaitAll()
        .mapNotNull { (url, bitmap) -> bitmap?.let { url to it } }
        .toMap()

    withContext(Dispatchers.Default) {
        val covers = bitmaps.mapValues { SkiaImage.makeFromBitmap(it.value) }
        val surface = Surface.makeRasterN32Premul(POSTER_W, POSTER_H)
        val canvas = surface.canvas
        val type = Type()

        // The same cover and the same turn of the colour wheel the card on
        // screen was drawn with, so the picture is recognisably that card.
        drawBackdrop(canvas, lit?.let { bitmaps[it] }, page?.let(::storyHue) ?: 0f)
        var y = drawHeader(canvas, type, summary, holder, memberSince)
        if (page == null) {
            y = drawTotals(canvas, type, summary, y)
            y = drawColumns(canvas, type, songs, artists, covers, y)
            y = drawAlbums(canvas, type, albums, covers, y)
            drawGenres(canvas, type, summary, y)
        } else {
            y = drawRuns(canvas, type, summary.storyHeadline(page), MARGIN, y, CONTENT_W)
            drawCard(canvas, type, summary, page, covers, y + 56f)
        }
        drawFooter(canvas, type)
        surface.makeImageSnapshot()
    }
}

// ── One story card ──────────────────────────────────────────────────────────

/**
 * The body of [page], under its headline: the same content the card on screen
 * shows, in the same order, laid out at fixed positions instead of weighted
 * spacers.
 */
private fun drawCard(
    canvas: Canvas,
    type: Type,
    summary: ReplaySummary,
    page: ReplayStoryPage,
    covers: Map<String, SkiaImage>,
    top: Float,
) {
    when (page) {
        ReplayStoryPage.INTRO, ReplayStoryPage.MINUTES -> drawCollage(canvas, summary, covers, top)
        ReplayStoryPage.SONGS -> drawLeaderboard(canvas, type, summary.songRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.ARTISTS -> drawLeaderboard(canvas, type, summary.artistRows(CARD_ROWS), covers, top, true)
        ReplayStoryPage.ALBUMS -> drawLeaderboard(canvas, type, summary.albumRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.GENRES -> drawBigList(canvas, type, summary.genreRows(CARD_ROWS), top)
        ReplayStoryPage.HABITS -> drawHabits(canvas, type, summary, top)
        ReplayStoryPage.SUMMARY -> drawRecap(canvas, type, summary, top)
    }
}

/** The number one, large, with its runners-up under it. */
private fun drawLeaderboard(
    canvas: Canvas,
    type: Type,
    rows: List<ReplayRow>,
    covers: Map<String, SkiaImage>,
    top: Float,
    circular: Boolean,
) {
    val lead = rows.firstOrNull() ?: return
    val hero = 348f
    drawArtwork(canvas, lead.artworkUrl?.let { covers[it] }, lead.title, MARGIN, top, hero, circular)

    val textX = MARGIN + hero + 48f
    val textWidth = POSTER_W - MARGIN - textX
    type.heading(78f, WHITE).draw(canvas, lead.title, textX, top + 86f, textWidth)
    var y = top + 86f
    lead.subtitle?.let {
        y += 62f
        type.body(50f, 0xB3FFFFFF.toInt()).draw(canvas, it, textX, y, textWidth)
    }
    type.body(44f, 0x8CFFFFFF.toInt()).draw(
        canvas,
        "${formatListening(lead.ms)} · ${replayCount(lead.plays, "replay_play_count")}",
        textX,
        y + 62f,
        textWidth,
    )

    var rowY = top + hero + 90f
    rows.drop(1).forEach { row ->
        type.heading(44f, 0x73FFFFFF).draw(canvas, row.rank.toString(), MARGIN, rowY + 72f)
        val artX = MARGIN + 62f
        drawArtwork(canvas, row.artworkUrl?.let { covers[it] }, row.title, artX, rowY, 108f, circular)
        val nameX = artX + 108f + 28f
        val stat = type.body(38f, 0x80FFFFFF.toInt())
        val time = formatListening(row.ms)
        val statWidth = stat.width(time)
        type.body(48f, WHITE, bold = true)
            .draw(canvas, row.title, nameX, rowY + 72f, POSTER_W - MARGIN - nameX - statWidth - 32f)
        stat.draw(canvas, time, POSTER_W - MARGIN, rowY + 72f, rightAligned = true)
        rowY += 148f
    }
}

/** The genre card: one word, big, then the rest as a ranked list. */
private fun drawBigList(canvas: Canvas, type: Type, rows: List<ReplayRow>, top: Float) {
    val lead = rows.firstOrNull() ?: return
    type.heading(150f, WHITE).draw(canvas, lead.title, MARGIN, top + 120f, CONTENT_W)
    type.body(46f, 0x99FFFFFF.toInt()).draw(canvas, formatListening(lead.ms), MARGIN, top + 186f)

    var y = top + 300f
    rows.drop(1).forEach { row ->
        type.heading(48f, 0x73FFFFFF).draw(canvas, row.rank.toString(), MARGIN, y)
        val stat = type.body(38f, 0x80FFFFFF.toInt())
        val time = formatListening(row.ms)
        val nameX = MARGIN + 80f
        type.body(52f, WHITE, bold = true)
            .draw(canvas, row.title, nameX, y, POSTER_W - MARGIN - nameX - stat.width(time) - 32f)
        stat.draw(canvas, time, POSTER_W - MARGIN, y, rightAligned = true)
        y += 106f
    }
}

private fun drawHabits(canvas: Canvas, type: Type, summary: ReplaySummary, top: Float) {
    var y = top + 40f
    fun stat(value: String, label: String) {
        type.heading(84f, WHITE).draw(canvas, value, MARGIN, y, CONTENT_W)
        type.body(42f, 0x99FFFFFF.toInt()).draw(canvas, label, MARGIN, y + 56f, CONTENT_W)
        y += 176f
    }
    if (summary.distinctAlbums > 0) stat(grouped(summary.distinctAlbums.toLong()), "Different albums")
    summary.busiestDay?.let { stat(formatDay(it), "Your biggest day: ${formatListening(summary.busiestDayMs)}") }
    summary.peakHour?.let { stat(formatHour(it), "When you listen the most") }
}

private fun drawRecap(canvas: Canvas, type: Type, summary: ReplaySummary, top: Float) {
    var y = top + 40f
    fun line(label: String, value: String) {
        type.body(42f, 0x80FFFFFF.toInt()).draw(canvas, label, MARGIN, y, 300f)
        type.body(52f, WHITE, bold = true).draw(canvas, value, MARGIN + 320f, y, CONTENT_W - 320f)
        y += 96f
    }
    line("Minutes", formatMinutes(summary.totalMs))
    summary.songs.firstOrNull()?.let { line("Top song", it.song.title) }
    summary.artists.firstOrNull()?.let { line("Top artist", it.title) }
    summary.albums.firstOrNull()?.let { line("Top album", it.title) }
    summary.genres.firstOrNull()?.let { line("Top genre", it.title) }
}

/** The covers the opening cards scatter: three sleeves, then three faces not already among them. */
private fun collageCovers(summary: ReplaySummary): Pair<List<String>, List<String>> {
    val squares = summary.songs.mapNotNull { it.song.thumbnailUrl }.distinct().take(3)
    val faces = summary.artists.mapNotNull { it.artworkUrl }.distinct().filterNot { it in squares }.take(3)
    return squares to faces
}

/** The scatter of covers the opening cards are built around. */
private fun drawCollage(canvas: Canvas, summary: ReplaySummary, covers: Map<String, SkiaImage>, top: Float) {
    val (squares, faces) = collageCovers(summary)
    // Fractions of the content box, so the pile keeps its shape at any size.
    val squareAt = listOf(Triple(0.22f, 0.30f, 500f), Triple(0.02f, 0.06f, 260f), Triple(0.66f, 0.00f, 215f))
    val faceAt = listOf(Triple(0.00f, 0.62f, 185f), Triple(0.72f, 0.32f, 225f), Triple(0.46f, 0.76f, 200f))
    val height = 900f
    squares.forEachIndexed { index, url ->
        val (fx, fy, size) = squareAt[index]
        drawArtwork(canvas, covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, false)
    }
    faces.forEachIndexed { index, url ->
        val (fx, fy, size) = faceAt[index]
        drawArtwork(canvas, covers[url], "", MARGIN + CONTENT_W * fx, top + height * fy, size, true)
    }
}

// ── Background ──────────────────────────────────────────────────────────────

/**
 * The mesh, by hand: four wide radial gradients off the leading sleeve's own
 * colours over a dark base, flattened under a vertical scrim — the phone's
 * recipe, numbers and all.
 */
private fun drawBackdrop(canvas: Canvas, lead: Bitmap?, hue: Float) {
    val colors = paletteOf(lead).map { rotated(it, hue) }
    canvas.clear(dimmed(colors.first()))

    val anchors = listOf(0.20f to 0.16f, 0.84f to 0.22f, 0.76f to 0.66f, 0.18f to 0.78f)
    val paint = Paint().apply { isAntiAlias = true }
    colors.forEachIndexed { index, color ->
        val (fx, fy) = anchors[index]
        val cx = POSTER_W * fx
        val cy = POSTER_H * fy
        val radius = POSTER_W * 0.95f
        paint.shader = Shader.makeRadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(withAlpha(color, 210), withAlpha(color, 0)),
            floatArrayOf(0f, 1f),
            GradientStyle.DEFAULT,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }

    // Ink, weighted to the foot where the smaller type is.
    paint.shader = Shader.makeLinearGradient(
        0f,
        0f,
        0f,
        POSTER_H.toFloat(),
        intArrayOf(0x8C000000.toInt(), 0x59000000, 0xCC000000.toInt()),
        floatArrayOf(0f, 0.42f, 1f),
        GradientStyle.DEFAULT,
    )
    canvas.drawRect(Rect.makeWH(POSTER_W.toFloat(), POSTER_H.toFloat()), paint)
}

/**
 * Four colours off the sleeve, saturated and held to a mid lightness — the
 * phone's `paletteOf`, over the same Palette port the player's mesh reads.
 */
private fun paletteOf(bitmap: Bitmap?): List<Int> {
    val source = bitmap ?: return FALLBACK_PALETTE
    val swatches = runCatching {
        paletteSwatches(source.asComposeImageBitmap(), 24, clearFilters = false)
            .sortedByDescending { it.population }
            .map { it.rgb }
    }.getOrNull().orEmpty()
    if (swatches.isEmpty()) return FALLBACK_PALETTE
    return (swatches + FALLBACK_PALETTE).take(4).map(::tuned)
}

/** [color] turned [degrees] around the wheel, tone untouched — see [storyHue]. */
private fun rotated(color: Int, degrees: Float): Int {
    if (degrees == 0f) return color
    return withHsl(color) { it[0] = (it[0] + degrees) % 360f }
}

private fun tuned(color: Int): Int = withHsl(color) {
    it[1] = (it[1] * 1.35f).coerceAtMost(1f)
    it[2] = it[2].coerceIn(0.28f, 0.58f)
}

private fun dimmed(color: Int): Int = withHsl(color) { it[2] = 0.10f }

private inline fun withHsl(color: Int, change: (FloatArray) -> Unit): Int {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    change(hsl)
    return ColorUtils.HSLToColor(hsl)
}

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

// ── Bands ───────────────────────────────────────────────────────────────────

private fun drawHeader(canvas: Canvas, type: Type, summary: ReplaySummary, holder: String, memberSince: String?): Float {
    val label = summary.localizedLabel()
    val title = if (label.length == 4 && label.all { it.isDigit() }) "Replay ${label.takeLast(2)}" else "Replay · $label"
    val brand = type.heading(46f, 0xE6FFFFFF.toInt())
    val wordWidth = brand.width(BRAND)
    brand.draw(canvas, BRAND, POSTER_W - MARGIN, 132f, rightAligned = true)
    // The mark, to the left of the word, as the story header carries it.
    val markLeft = POSTER_W - MARGIN - wordWidth - LOGO_GAP - LOGO_W
    drawLogo(canvas, markLeft, 132f)
    type.heading(46f, WHITE).draw(canvas, title, MARGIN, 132f, markLeft - MARGIN - 24f)

    val credit = listOfNotNull(
        holder.ifBlank { "BitChord listener" },
        memberSince?.let { "Member since $it" },
    ).joinToString(" · ")
    type.label(24f, 0x8CFFFFFF.toInt()).draw(canvas, credit.uppercase(), MARGIN, 182f, CONTENT_W)
    return 300f
}

private fun drawTotals(canvas: Canvas, type: Type, summary: ReplaySummary, top: Float): Float {
    type.heading(148f, WHITE).draw(canvas, formatMinutes(summary.totalMs), MARGIN, top + 120f)
    type.label(30f, 0xB3FFFFFF.toInt(), tracking = 0.14f).draw(canvas, "MINUTES LISTENED", MARGIN, top + 176f)
    type.body(30f, 0x99FFFFFF.toInt()).draw(
        canvas,
        "${replayCount(summary.totalPlays, "replay_play_count")} · " +
            "${replayCount(summary.distinctSongs, "replay_song_count")} · " +
            replayCount(summary.distinctArtists, "replay_artist_count"),
        MARGIN,
        top + 232f,
        CONTENT_W,
    )
    return top + 320f
}

/** The two charts side by side: songs on the left, artists on the right as circles. */
private fun drawColumns(
    canvas: Canvas,
    type: Type,
    songs: List<ReplayRow>,
    artists: List<ReplayRow>,
    covers: Map<String, SkiaImage>,
    top: Float,
): Float {
    val columnWidth = (POSTER_W - MARGIN * 2 - COLUMN_GAP) / 2f
    val right = MARGIN + columnWidth + COLUMN_GAP
    val heading = type.label(28f, 0xB3FFFFFF.toInt(), tracking = 0.16f)
    heading.draw(canvas, "TOP SONGS", MARGIN, top)
    heading.draw(canvas, "TOP ARTISTS", right, top)

    var y = top + 54f
    repeat(maxOf(songs.size, artists.size)) { index ->
        songs.getOrNull(index)?.let { drawRow(canvas, type, it, covers, MARGIN, y, columnWidth, false) }
        artists.getOrNull(index)?.let { drawRow(canvas, type, it, covers, right, y, columnWidth, true) }
        y += ROW_HEIGHT
    }
    return y + 40f
}

private fun drawRow(
    canvas: Canvas,
    type: Type,
    row: ReplayRow,
    covers: Map<String, SkiaImage>,
    x: Float,
    y: Float,
    width: Float,
    circular: Boolean,
) {
    type.heading(34f, if (row.rank == 1) ACCENT else 0x73FFFFFF).draw(canvas, row.rank.toString(), x, y + ART * 0.68f)
    val artX = x + 44f
    drawArtwork(canvas, row.artworkUrl?.let { covers[it] }, row.title, artX, y, ART, circular)

    val textX = artX + ART + 20f
    val textWidth = width - (textX - x)
    type.body(30f, WHITE, bold = true).draw(canvas, row.title, textX, y + 36f, textWidth)
    val detail = listOfNotNull(row.subtitle, formatListening(row.ms)).joinToString(" · ")
    type.body(25f, 0x99FFFFFF.toInt()).draw(canvas, detail, textX, y + 72f, textWidth)
}

private fun drawAlbums(canvas: Canvas, type: Type, albums: List<ReplayRow>, covers: Map<String, SkiaImage>, top: Float): Float {
    if (albums.isEmpty()) return top
    type.label(28f, 0xB3FFFFFF.toInt(), tracking = 0.16f).draw(canvas, "TOP ALBUMS", MARGIN, top)
    val size = 200f
    val gap = (POSTER_W - MARGIN * 2 - size * POSTER_ALBUMS) / (POSTER_ALBUMS - 1)
    val y = top + 40f
    albums.take(POSTER_ALBUMS).forEachIndexed { index, album ->
        val x = MARGIN + index * (size + gap)
        drawArtwork(canvas, album.artworkUrl?.let { covers[it] }, album.title, x, y, size, false)
        type.body(25f, WHITE, bold = true).draw(canvas, album.title, x, y + size + 38f, size)
        type.body(22f, 0x8CFFFFFF.toInt()).draw(canvas, album.subtitle ?: formatListening(album.ms), x, y + size + 70f, size)
    }
    return y + size + 130f
}

private fun drawGenres(canvas: Canvas, type: Type, summary: ReplaySummary, top: Float) {
    val genres = summary.genreRows(POSTER_GENRES)
    if (genres.isEmpty()) return
    type.label(28f, 0xB3FFFFFF.toInt(), tracking = 0.16f).draw(canvas, "TOP GENRES", MARGIN, top)
    val name = type.body(32f, WHITE, bold = true)
    val chip = Paint().apply {
        isAntiAlias = true
        color = 0x26FFFFFF
    }
    var x = MARGIN
    val y = top + 44f
    genres.forEach { genre ->
        val width = name.width(genre.title) + 44f
        if (x + width > POSTER_W - MARGIN) return
        canvas.drawRRect(RRect.makeXYWH(x, y, width, 62f, 31f), chip)
        name.draw(canvas, genre.title, x + 22f, y + 42f)
        x += width + 14f
    }
}

private fun drawFooter(canvas: Canvas, type: Type) {
    type.label(24f, 0x73FFFFFF, tracking = 0.08f)
        .draw(canvas, "Counted on this device with BitChord", MARGIN, POSTER_H - 64f, CONTENT_W)
}

// ── Pieces ──────────────────────────────────────────────────────────────────

/**
 * A cover, cropped square and rounded — or, where one never loaded, the same
 * lettered-tile colour the lists use, so a missing sleeve is a deliberate-looking
 * tile rather than a hole.
 */
private fun drawArtwork(
    canvas: Canvas,
    image: SkiaImage?,
    fallback: String,
    x: Float,
    y: Float,
    size: Float,
    circular: Boolean,
) {
    val radius = if (circular) size / 2f else size * 0.10f
    val shape = RRect.makeXYWH(x, y, size, size, radius)
    if (image != null) {
        val side = minOf(image.width, image.height).toFloat()
        val source = Rect.makeXYWH((image.width - side) / 2f, (image.height - side) / 2f, side, side)
        canvas.save()
        canvas.clipRRect(shape, ClipMode.INTERSECT, true)
        // Mipmapped: a 480px sleeve drawn at 84 aliases badly under plain linear.
        canvas.drawImageRect(image, source, Rect.makeXYWH(x, y, size, size), COVER_SAMPLING, null, true)
        canvas.restore()
    } else {
        val hue = (fallback.hashCode().toFloat() % 360f + 360f) % 360f
        val paint = Paint().apply {
            isAntiAlias = true
            color = ColorUtils.HSLToColor(floatArrayOf(hue, 0.55f, 0.45f))
        }
        canvas.drawRRect(shape, paint)
    }
}

/**
 * A card's sentence, wrapped, with the emphasised runs in the heavier face.
 *
 * The phone breaks the runs into words and places them by hand because an
 * Android canvas has no paragraph of two typefaces. Skia's paragraph does, so
 * the runs go in as styled spans and it does the breaking — including in the
 * middle of the bold part, and without inventing a space where a run ends
 * mid-word ("4 songs" bold, then ", one was…").
 *
 * Returns the last line's baseline plus the phone's 24px, where the card body starts.
 */
private fun drawRuns(canvas: Canvas, type: Type, runs: List<HeadlineRun>, x: Float, top: Float, maxWidth: Float): Float {
    val bold = type.heading(HEADLINE_SIZE, WHITE).style.setHeight(HEADLINE_LEADING / HEADLINE_SIZE)
    val plain = type.body(HEADLINE_SIZE, 0x9EFFFFFF.toInt(), bold = true).style.setHeight(HEADLINE_LEADING / HEADLINE_SIZE)
    val builder = ParagraphBuilder(ParagraphStyle().apply { textStyle = plain }, type.fonts)
    runs.forEach { run ->
        builder.pushStyle(if (run.bold) bold else plain)
        builder.addText(run.text)
        builder.popStyle()
    }
    val paragraph = builder.build().layout(maxWidth)
    // The first baseline where the phone puts it: one type size below the top.
    val paintTop = top + HEADLINE_SIZE - paragraph.alphabeticBaseline
    paragraph.paint(canvas, x, paintTop)
    val lastBaseline = paragraph.lineMetrics.lastOrNull()?.baseline?.toFloat() ?: paragraph.alphabeticBaseline
    return paintTop + lastBaseline + 24f
}

/** The BitChord mark, baseline-aligned with the word beside it — `ic_logo`'s path, scaled. */
private fun drawLogo(canvas: Canvas, left: Float, baseline: Float) {
    val path = runCatching { Path.makeFromSVGString(LOGO_PATH) }.getOrNull() ?: return
    canvas.save()
    canvas.translate(left, baseline - LOGO_H)
    canvas.scale(LOGO_W / LOGO_VIEWPORT_W, LOGO_H / LOGO_VIEWPORT_H)
    canvas.drawPath(
        path,
        Paint().apply {
            isAntiAlias = true
            color = 0xE6FFFFFF.toInt()
        },
    )
    canvas.restore()
}

/**
 * The type the app is set in, on a canvas.
 *
 * The phone bundles SF Pro Display; on iOS it *is* the system face, asked for
 * by the name Compose itself resolves the default family to. The family list
 * carries Helvetica after it for the same reason Compose's does, and the
 * collection's default font manager is what supplies a face for any glyph
 * neither has.
 */
private class Type {
    val fonts: FontCollection = FontCollection().setDefaultFontManager(FontMgr.default)

    fun heading(size: Float, color: Int) = Pen(fonts, HEAVY, size, color, 0f)

    fun body(size: Float, color: Int, bold: Boolean = false) =
        Pen(fonts, if (bold) SEMIBOLD else REGULAR, size, color, 0f)

    fun label(size: Float, color: Int, tracking: Float = 0.10f) = Pen(fonts, SEMIBOLD, size, color, tracking)
}

/** One face at one size and colour; [tracking] is in ems, as Android's letterSpacing is. */
private class Pen(private val fonts: FontCollection, weight: Int, size: Float, color: Int, tracking: Float) {
    val style: TextStyle = TextStyle()
        .setFontFamilies(SYSTEM_FAMILIES)
        .setFontStyle(FontStyle(weight, FontWidth.NORMAL, FontSlant.UPRIGHT))
        .setFontSize(size)
        .setColor(color)
        .setLetterSpacing(tracking * size)

    /** One line, ellipsised at [width] the way a single-line row would be. */
    private fun layout(text: String, width: Float): Paragraph {
        val paragraph = ParagraphStyle().apply {
            textStyle = style
            ellipsis = "…"
            maxLinesCount = 1
        }
        return ParagraphBuilder(paragraph, fonts).pushStyle(style).addText(text).popStyle().build().layout(width)
    }

    fun width(text: String): Float = layout(text, UNBOUNDED).maxIntrinsicWidth

    /** Draws [text] with its baseline at [baseline]; [x] is its right edge when [rightAligned]. */
    fun draw(canvas: Canvas, text: String, x: Float, baseline: Float, maxWidth: Float = UNBOUNDED, rightAligned: Boolean = false) {
        if (maxWidth <= 0f) return
        val paragraph = layout(text, maxWidth)
        val left = if (rightAligned) x - minOf(paragraph.maxIntrinsicWidth, maxWidth) else x
        paragraph.paint(canvas, left, baseline - paragraph.alphabeticBaseline)
    }
}

private suspend fun loadCover(url: String): Bitmap? = withTimeoutOrNull(COVER_TIMEOUT_MILLIS) {
    runCatching {
        val context = PlatformContext.INSTANCE
        val request = ImageRequest.Builder(context).data(url.artworkAt(CARD_ART_PX)).build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }.getOrNull()
}

/** 9:16, the shape the story cards are held to. */
internal const val POSTER_W = 1080
internal const val POSTER_H = 1920

private const val MARGIN = 72f

/** The mark's drawn size; 730×484 in the vector, so this keeps its proportions. */
private const val LOGO_W = 66f
private const val LOGO_H = 44f
private const val LOGO_GAP = 20f
private const val LOGO_VIEWPORT_W = 730f
private const val LOGO_VIEWPORT_H = 484f

/** `ic_logo.xml`'s pathData; Android path data is SVG's path syntax. */
private const val LOGO_PATH =
    "M6.73729,316.937C20.201,294.159 49.6986,286.649 72.4033,300.174H72.4277C237.603,398.282 510.121,407.302 667.823,359.439" +
        "C693.147,351.759 719.927,366.055 727.602,391.378C735.276,416.715 720.992,443.477 695.649,451.176" +
        "C514.618,506.132 213.674,495.514 23.5058,382.622C0.678772,369.103 -6.78757,339.685 6.73729,316.937Z" +
        "M77.8135,154.865C89.38,136.046 113.982,130.11 132.77,141.676C270.528,226.369 480.519,250.891 643.46,201.43" +
        "C664.592,195.047 686.911,206.957 693.324,228.052C699.689,249.184 687.774,271.46 666.679,277.886" +
        "C480.555,334.36 249.169,307.004 90.9707,209.79C72.1827,198.224 66.247,173.628 77.8135,154.865Z" +
        "M140.542,15.3012C149.722,0.246392 169.428,-4.52761 184.482,4.71335C304.811,78.2128 456.29,94.8593 634.684,54.101" +
        "C651.874,50.1843 669.01,60.9551 672.927,78.1518C676.862,95.3486 666.134,112.484 648.9,116.401" +
        "C453.677,161.021 286.219,141.799 151.129,59.2417C136.074,50.0006 131.301,30.3561 140.542,15.3012Z"

private const val BRAND = "BitChord"

/** The width type and artwork are laid out in. */
private const val CONTENT_W = POSTER_W - MARGIN * 2

/** Matches the 30sp the card on screen sets its sentence at, at 3x. */
private const val HEADLINE_SIZE = 88f
private const val HEADLINE_LEADING = 108f

/** How far down a chart a shared card goes — the same five the card shows. */
private const val CARD_ROWS = 5
private const val COLUMN_GAP = 36f
private const val ROW_HEIGHT = 116f
private const val ART = 84f
private const val ACCENT = 0xFFFA2D48.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

private const val POSTER_ROWS = 5
private const val POSTER_ALBUMS = 3
private const val POSTER_GENRES = 4

/** SF Pro Display's Heavy, Semibold and Regular, as weights of the system face. */
private const val HEAVY = 800
private const val SEMIBOLD = 600
private const val REGULAR = 400

/** What Compose resolves FontFamily.Default to on iOS. */
private val SYSTEM_FAMILIES = arrayOf(".AppleSystemUIFont", "Helvetica")

/** Wide enough that nothing on a 1080px poster ellipsises against it. */
private const val UNBOUNDED = 100_000f

/** A cover that hasn't arrived by then is drawn as its tile: the sheet is waiting on it. */
private const val COVER_TIMEOUT_MILLIS = 8_000L

private val COVER_SAMPLING = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

private val FALLBACK_PALETTE = listOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFF2B5876.toInt(), 0xFFFFAF7B.toInt())
