package com.musa.poetmusic.server

import com.musa.poetmusic.data.FormatShare
import com.musa.poetmusic.data.JournalStats
import com.musa.poetmusic.data.TopAlbum
import com.musa.poetmusic.data.TopArtist
import com.musa.poetmusic.data.TopGenre
import com.musa.poetmusic.data.TopTrack
import java.util.Locale

/**
 * Listening Journal: a full-screen "wrapped" report over the local library and
 * the plays log, reached by tapping the Poet mark in the header. Everything on
 * the screen is derived from the database — the library totals, tag health and
 * format mix from the tracks table, the leaderboards and habits from the plays
 * PlayerController writes once a song has actually been listened to.
 *
 * The screen has two depths: collapsed shows the first [SHORT_TOP] of each
 * leaderboard, "Show Wrapped" reveals everything the database read returned.
 */
object JournalViews {

    /** Leaderboard entries shown before "Show Wrapped" is turned on. */
    private const val SHORT_TOP = 5

    /** Genre chips shown collapsed / expanded. */
    private const val SHORT_GENRES = 6

    fun journalScreen(
        stats: JournalStats,
        hasPortrait: Boolean,
        portraitStamp: String,
        detail: Boolean = false
    ): String = """
        <div class="screen journal-screen" data-screen="journal">
          ${topBar(detail)}
          <div class="journal-body">
            ${hero(hasPortrait, portraitStamp)}
            ${totals(stats)}
            ${personality(stats)}
            ${rotation(stats, detail)}
            ${habits(stats)}
            ${health(stats)}
            <button class="journal-return" hx-get="/screens/library" hx-target="#main-container">Return to library</button>
          </div>
        </div>"""

    /**
     * Sticky bar: the way back to the library, and the depth toggle. Both are
     * plain htmx gets so the server stays the only place the depth is decided.
     */
    private fun topBar(detail: Boolean): String = """
        <div class="journal-bar">
          <div class="journal-bar-inner">
            <button class="journal-back" hx-get="/screens/library" hx-target="#main-container" aria-label="Back to library">
              <svg width="16" height="12" viewBox="0 0 16 12"><path d="M6 1 L1 6 L6 11 M1 6 L15 6" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>
            </button>
            <div class="journal-bar-title">Listening Journal</div>
            <button class="journal-toggle" hx-get="/screens/journal${if (detail) "" else "?detail=1"}" hx-target="#main-container">
              ${if (detail) "Show less" else "Show Wrapped"}
            </button>
          </div>
        </div>"""

    /**
     * The circular portrait badge over the title. Tapping it (or the pencil)
     * opens the gallery picker; the pick is stored in app-private storage and
     * served back from /api/journal/portrait.
     */
    private fun hero(hasPortrait: Boolean, stamp: String): String {
        val inner =
            if (hasPortrait) """<img id="journal-portrait-img" src="/api/journal/portrait?v=${esc(stamp)}" alt="Journal portrait">"""
            else """<div class="journal-portrait-empty"><div style="font-size:20px;">♪</div><div>Add portrait</div></div>"""
        return """
        <div class="journal-hero">
          <div class="journal-portrait" onclick="poetPickPortrait()" role="button" tabindex="0" aria-label="Change journal portrait">
            <div class="journal-portrait-ring">$inner</div>
            <div class="journal-portrait-edit" aria-hidden="true">
              <svg width="12" height="12" viewBox="0 0 14 14"><path d="M9.6 1.4 L12.6 4.4 L4.6 12.4 L1.2 12.8 L1.6 9.4 Z" fill="none" stroke="#3b3651" stroke-width="1.6" stroke-linejoin="round"></path></svg>
            </div>
          </div>
          <div class="journal-title">Everything you&#39;ve played</div>
          <div class="journal-kicker">Local archive · all time</div>
        </div>"""
    }

    /** The three headline numbers, or the pointer at the folder picker. */
    private fun totals(s: JournalStats): String {
        if (s.trackCount == 0) {
            return """
            <div class="journal-lede">Nothing has been scanned yet. Map a music folder in Settings and
              your archive will start keeping a record here.</div>"""
        }
        return """
        <div class="journal-totals">
          ${tile(fmtCount(s.trackCount), plural(s.trackCount, "track"))}
          ${tile(fmtCount(s.albumCount), plural(s.albumCount, "album"))}
          ${tile(fmtHours(s.totalDurationMs), "hours")}
        </div>"""
    }

    private fun tile(value: String, label: String): String = """
        <div class="journal-tile">
          <div class="journal-tile-value">$value</div>
          <div class="journal-tile-label">$label</div>
        </div>"""

    /** The listener-personality card, read off the shape of the plays log. */
    private fun personality(s: JournalStats): String {
        val (name, desc) = personalityFor(s)
        return """
        <div class="journal-personality">
          <div class="journal-personality-kicker">Your listener personality</div>
          <div class="journal-personality-name">${esc(name)}</div>
          <div class="journal-personality-desc">$desc</div>
        </div>"""
    }

    /**
     * Which listener the plays log describes, and the sentence backing it up.
     * Repeat share comes first because it is the strongest signal — someone who
     * replays the same records reads as devoted whatever hour they do it at.
     */
    fun personalityFor(s: JournalStats): Pair<String, String> {
        if (s.totalPlays == 0) {
            return "The Newcomer" to
                "Nothing is in rotation yet. Play a song for $PLAY_THRESHOLD_LABEL and this page starts keeping score."
        }
        val repeatPct = percent(s.totalPlays - s.exploredTracks, s.totalPlays)
        val hour = s.peakHour?.hour
        val nightOwl = hour != null && (hour >= 21 || hour <= 4)
        return when {
            repeatPct >= 60 -> "The Devoted Fan" to
                """You return to the same records again and again — ${strong("$repeatPct%")} of your plays
                   are songs you had already heard. Comfort over novelty, and you know exactly what you like."""
            repeatPct >= 35 -> "The Deep Diver" to
                """You sit with a record before moving on: ${strong("$repeatPct%")} of your plays are repeats,
                   spread across ${strong("${fmtCount(s.exploredTracks)} ${plural(s.exploredTracks, "track")}")} you have opened up."""
            nightOwl -> "The Night Owl" to
                """Your listening peaks at ${strong(clockHour(hour!!))} — squarely ${strong(hourLabel(hour).lowercase(Locale.US))} —
                   and you keep reaching for something you have not heard yet."""
            else -> "The Explorer" to
                """You keep moving: ${strong("${fmtCount(s.exploredTracks)} ${plural(s.exploredTracks, "track")}")}
                   of your archive have been opened, and only ${strong("$repeatPct%")} of your plays are repeats."""
        }
    }

    /** Everything the plays log ranks: artists, songs, albums and genres. */
    private fun rotation(s: JournalStats, detail: Boolean): String {
        if (s.totalPlays == 0) {
            return """
            ${sectionHead("Heavy Rotation")}
            <div class="journal-empty">Nothing in rotation yet — play a song for $PLAY_THRESHOLD_LABEL
              and it starts counting towards your journal.</div>"""
        }
        val take = if (detail) Int.MAX_VALUE else SHORT_TOP
        return """
        ${sectionHead("Top Artists")}
        ${artistGrid(s.topArtists.take(take))}
        ${sectionHead("Top Songs")}
        ${songList(s.topSongs.take(take))}
        ${albumSection(s.topAlbums.take(take))}
        ${genreSection(s.topGenres.take(if (detail) Int.MAX_VALUE else SHORT_GENRES))}"""
    }

    private fun artistGrid(artists: List<TopArtist>): String = """
        <div class="journal-artists">
          ${artists.mapIndexed { i, a -> artistCell(i, a) }.joinToString("")}
        </div>"""

    private fun artistCell(index: Int, a: TopArtist): String = """
        <div class="journal-artist">
          <div class="journal-artist-face" style="background:${artColor(index.toLong())};">${esc(initials(a.artist))}</div>
          <div style="min-width:0;">
            <div class="journal-artist-name">${esc(a.artist)}</div>
            <div class="journal-artist-plays">${fmtCount(a.plays)} ${plural(a.plays, "play")}</div>
          </div>
        </div>"""

    private fun songList(songs: List<TopTrack>): String = """
        <div class="journal-songs">
          ${songs.mapIndexed { i, t -> songRow(i + 1, t) }.joinToString("")}
        </div>"""

    private fun songRow(rank: Int, t: TopTrack): String = """
        <div class="journal-song">
          <div class="journal-song-rank">$rank</div>
          <div style="flex:1; min-width:0;">
            <div class="journal-song-title">${esc(t.title)}</div>
            <div class="journal-song-artist">${esc(t.artist)}</div>
          </div>
          <div class="journal-song-plays">${fmtCount(t.plays)}×</div>
        </div>"""

    /**
     * Albums scroll sideways as covers. An album with no embedded art anywhere
     * falls back to a tinted tile carrying its initials, the same placeholder
     * the library rows use.
     */
    private fun albumSection(albums: List<TopAlbum>): String {
        if (albums.isEmpty()) return ""
        val cards = albums.mapIndexed { i, al ->
            val cover =
                if (al.artTrackId > 0) """<img loading="lazy" src="/api/art/${al.artTrackId}" alt="">"""
                else """<span class="journal-album-initials">${esc(initials(al.album))}</span>"""
            """
            <div class="journal-album">
              <div class="journal-album-cover" style="background:${artColor(i.toLong())};">
                $cover
                <div class="journal-album-rank">#${i + 1}</div>
              </div>
              <div class="journal-album-name">${esc(al.album)}</div>
              <div class="journal-album-artist">${esc(al.artist)}</div>
            </div>"""
        }.joinToString("")
        return """
        ${sectionHead("Top Albums")}
        <div class="journal-albums">$cards</div>"""
    }

    /**
     * Genre chips, tinted by share. The accent is a CSS variable, so the tint
     * is an accent-filled layer behind the label rather than an alpha suffix on
     * a hex the server does not have.
     */
    private fun genreSection(genres: List<TopGenre>): String {
        if (genres.isEmpty()) return ""
        val total = genres.sumOf { it.plays }
        val chips = genres.mapIndexed { i, g ->
            val weight = String.format(Locale.US, "%.2f", (0.42 - i * 0.045).coerceAtLeast(0.10))
            """
            <div class="journal-genre" style="--tint:$weight;">
              <span class="journal-genre-name">${esc(g.genre)}</span>
              <span class="journal-genre-pct">${percent(g.plays, total)}%</span>
            </div>"""
        }.joinToString("")
        return """
        ${sectionHead("Top Genres")}
        <div class="journal-genres">$chips</div>"""
    }

    /** Six habit tiles; each one is dropped when its source data is missing. */
    private fun habits(s: JournalStats): String {
        if (s.totalPlays == 0) return ""
        val tiles = mutableListOf<String>()
        s.peakDay?.let {
            tiles += habit("Most active day", dayName(it.day), "${percent(it.plays, s.totalPlays)}% of your plays landed here.")
        }
        if (s.longestStreak > 0) {
            tiles += habit(
                "Listening streak", "${s.longestStreak} ${plural(s.longestStreak, "day")}",
                "Your longest run of days in a row."
            )
        }
        s.peakHour?.let {
            tiles += habit(
                "Peak hour", clockHour(it.hour),
                "${percent(it.plays, s.totalPlays)}% of activity is ${hourLabel(it.hour).lowercase(Locale.US)}."
            )
        }
        val firstListens = percent(s.exploredTracks, s.totalPlays)
        tiles += habit("Discovery mix", "$firstListens% new", "${100 - firstListens}% were songs you had already played.")
        if (s.trackCount > 0) {
            tiles += habit(
                "Average length", fmtTime(s.totalDurationMs / s.trackCount),
                "Across every track in the archive."
            )
        }
        if (s.topDecade.isNotEmpty()) {
            tiles += habit("Decade focus", s.topDecade, "Based on the year tags you have.")
        }
        return """
        ${sectionHead("Listening Habits")}
        <div class="journal-habits">${tiles.joinToString("")}</div>"""
    }

    private fun habit(label: String, value: String, note: String): String = """
        <div class="journal-habit">
          <div class="journal-habit-label">$label</div>
          <div class="journal-habit-value">${esc(value)}</div>
          <div class="journal-habit-note">${esc(note)}</div>
        </div>"""

    /** Library health: how much has been heard, then the metadata rows. */
    private fun health(s: JournalStats): String = """
        ${sectionHead("Library Health &amp; Metadata")}
        ${explored(s)}
        <div class="journal-metrics">${healthRows(s)}</div>"""

    private fun explored(s: JournalStats): String {
        if (s.trackCount == 0) return ""
        val pct = percent(s.exploredTracks, s.trackCount)
        return """
        <div class="journal-explored">
          <div class="journal-explored-head">
            <div>Library explored</div>
            <div>$pct%</div>
          </div>
          <div class="journal-explored-track"><div class="journal-explored-fill" style="width:$pct%;"></div></div>
          <div class="journal-explored-note">You have listened to ${fmtCount(s.exploredTracks)} of your
            ${fmtCount(s.trackCount)} local ${plural(s.trackCount, "track")} at least once.</div>
        </div>"""
    }

    private fun healthRows(s: JournalStats): String {
        // With nothing scanned there is no health to report: "0% organized"
        // would read as a problem with the library rather than an empty one.
        if (s.trackCount == 0) {
            return metric("Tag Integrity Index", NO_TRACKS) +
                metric("Cover Art Coverage", NO_TRACKS) +
                metric("Synced Lyrics (.lrc)", NO_TRACKS)
        }
        val tagIndex = percent(s.filledTagFields, s.totalTagFields)
        val artValue =
            if (s.missingArt == 0) "every track covered"
            else "${strong("${fmtCount(s.missingArt)} ${plural(s.missingArt, "track")}")} missing art"
        return metric("Tag Integrity Index", "${strong("$tagIndex%")} organized ID3v2") +
            metric("Cover Art Coverage", artValue) +
            metric("Synced Lyrics (.lrc)", "${strong("${fmtCount(s.syncedLyrics)} ${plural(s.syncedLyrics, "track")}")} paired") +
            metric("File Formats", formatSummary(s.formats, s.trackCount)) +
            metric(
                "Backup Status",
                if (s.folderCount == 0) "no folder mapped"
                else "${strong("${fmtCount(s.folderCount)} ${plural(s.folderCount, "folder")}")} mapped"
            )
    }

    /** "MP3 85% · FLAC 10% · M4A 5%", trimmed to the three commonest kinds. */
    fun formatSummary(formats: List<FormatShare>, trackCount: Int): String {
        if (formats.isEmpty() || trackCount == 0) return "unknown"
        return formats.take(3).joinToString(" · ") {
            "${esc(it.ext.uppercase(Locale.US))} ${percent(it.count, trackCount)}%"
        }
    }

    private fun sectionHead(label: String): String = """
        <div class="journal-section">
          <div class="journal-section-bar"></div>
          <div class="journal-section-name">$label</div>
        </div>"""

    private fun metric(name: String, value: String): String = """
        <div class="journal-metric">
          <div class="journal-metric-name">$name</div>
          <div class="journal-metric-value">$value</div>
        </div>"""

    private fun strong(text: String): String = """<span class="journal-strong">$text</span>"""

    // ---------- formatting ----------

    /** Human wording for PlayerController's play threshold, kept in one place. */
    private const val PLAY_THRESHOLD_LABEL = "twenty seconds"

    /** Metric value shown for every health tile while the library is empty. */
    private const val NO_TRACKS = "no tracks yet"

    /** Total playback time in hours, one decimal ("84.5"). */
    fun fmtHours(ms: Long): String = String.format(Locale.US, "%.1f", ms / 3_600_000.0)

    /** Zero-padded clock hour for the peak listening slot ("23:00"). */
    fun clockHour(hour: Int): String = String.format(Locale.US, "%02d:00", hour.coerceIn(0, 23))

    /** Part of the day an hour falls in, as shown next to the peak hour. */
    fun hourLabel(hour: Int): String = when (hour.coerceIn(0, 23)) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        in 21..23 -> "Late Night"
        else -> "Small Hours"
    }

    /** Weekday name for SQLite's %w, where 0 is Sunday. */
    fun dayName(day: Int): String = when (day.coerceIn(0, 6)) {
        0 -> "Sunday"
        1 -> "Monday"
        2 -> "Tuesday"
        3 -> "Wednesday"
        4 -> "Thursday"
        5 -> "Friday"
        else -> "Saturday"
    }

    /** [part] of [total] as a rounded percentage; 0 when there is nothing to divide. */
    fun percent(part: Int, total: Int): Int =
        if (total <= 0) 0 else Math.round(part * 100.0 / total).toInt().coerceIn(0, 100)

    /** Naive English plural, enough for the nouns this screen counts. */
    fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"
}
