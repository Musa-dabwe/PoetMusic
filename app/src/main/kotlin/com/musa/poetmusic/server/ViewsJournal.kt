package com.musa.poetmusic.server

import com.musa.poetmusic.data.JournalStats
import java.util.Locale

/**
 * Listening Journal: a stats dashboard over the local library and the plays
 * log, reached by tapping the Poet mark in the header. Everything on the
 * screen is derived from the database — the library totals and tag health from
 * the tracks table, the heavy-rotation leaders from the plays PlayerController
 * writes once a song has actually been listened to.
 */
object JournalViews {

    fun journalScreen(stats: JournalStats, hasPortrait: Boolean, portraitStamp: String): String = """
        <div class="screen" data-screen="journal">
          <div class="journal-wrap">
            <div class="journal-card">
              <div class="journal-title">Listening Journal</div>
              <div class="journal-kicker">Local archive · all time</div>
              ${summary(stats)}
              <div class="journal-rule"></div>
              ${sectionHead("Heavy Rotation")}
              ${heavyRotation(stats)}
              <div class="journal-rule"></div>
              ${sectionHead("Library Health &amp; Metadata")}
              ${libraryHealth(stats)}
              <div style="display:flex; justify-content:center; margin-top:26px;">
                <button class="btn-primary" hx-get="/screens/library" hx-target="#main-container">Back to library</button>
              </div>
            </div>
            ${portrait(hasPortrait, portraitStamp)}
          </div>
        </div>"""

    /** The opening sentence: how much music the archive actually holds. */
    private fun summary(s: JournalStats): String {
        if (s.trackCount == 0) {
            return """
            <div class="journal-lede">Nothing has been scanned yet. Map a music folder in Settings and
              your archive will start keeping a record here.</div>"""
        }
        return """
        <div class="journal-lede">Your local archive holds ${strong("${fmtCount(s.trackCount)} ${plural(s.trackCount, "track")}")}
          across ${strong("${fmtCount(s.albumCount)} ${plural(s.albumCount, "album")}")}, totaling
          ${strong("${fmtHours(s.totalDurationMs)} hours")} of continuous playback time.</div>"""
    }

    private fun heavyRotation(s: JournalStats): String {
        val top = s.topTrack
        val artist = s.topArtist
        val peak = s.peakHour
        if (top == null || artist == null || peak == null) {
            return """
            <div class="journal-list">
              ${bullet("Nothing in rotation yet — play a song for ${PLAY_THRESHOLD_LABEL} and it starts counting towards your journal.")}
            </div>"""
        }
        val share = percent(peak.plays, s.totalPlays)
        return """
        <div class="journal-list">
          ${bullet(
            """${strong("Top Track")} — “${esc(top.title)}” by ${esc(top.artist)}
               (Played ${strong("${fmtCount(top.plays)} ${plural(top.plays, "time")}")})."""
        )}
          ${bullet(
            """${strong("Most Active Artist")} — ${strong(esc(artist.artist))} with
               ${strong("${fmtCount(artist.plays)} total ${plural(artist.plays, "play")}")} across local directories."""
        )}
          ${bullet(
            """${strong("Peak Listening Hour")} — ${strong("${clockHour(peak.hour)} (${hourLabel(peak.hour)})")},
               accounting for ${strong("$share%")} of your total library activity."""
        )}
        </div>"""
    }

    private fun libraryHealth(s: JournalStats): String {
        // With nothing scanned there is no health to report: "0% organized"
        // would read as a problem with the library rather than an empty one.
        if (s.trackCount == 0) {
            return """
            <div class="journal-metrics">
              ${metric("Tag Integrity Index", NO_TRACKS)}
              ${metric("Cover Art Coverage", NO_TRACKS)}
              ${metric("Synced Lyrics (.lrc)", NO_TRACKS)}
            </div>"""
        }
        val tagIndex = percent(s.filledTagFields, s.totalTagFields)
        val artValue =
            if (s.missingArt == 0) "every track covered"
            else "${strong("${fmtCount(s.missingArt)} ${plural(s.missingArt, "track")}")} missing art"
        return """
        <div class="journal-metrics">
          ${metric("Tag Integrity Index", "${strong("$tagIndex%")} organized ID3v2")}
          ${metric("Cover Art Coverage", artValue)}
          ${metric("Synced Lyrics (.lrc)", "${strong("${fmtCount(s.syncedLyrics)} ${plural(s.syncedLyrics, "track")}")} paired")}
        </div>"""
    }

    /**
     * The circular portrait badge that overhangs the card. Tapping it (or the
     * pencil) opens the gallery picker; the pick is stored in app-private
     * storage and served back from /api/journal/portrait.
     */
    private fun portrait(hasPortrait: Boolean, stamp: String): String {
        val inner =
            if (hasPortrait) """<img id="journal-portrait-img" src="/api/journal/portrait?v=${esc(stamp)}" alt="Journal portrait">"""
            else """<div class="journal-portrait-empty"><div style="font-size:20px;">♪</div><div>Add portrait</div></div>"""
        return """
        <div class="journal-portrait" onclick="poetPickPortrait()" role="button" tabindex="0" aria-label="Change journal portrait">
          <div class="journal-portrait-ring">$inner</div>
          <div class="journal-portrait-edit" aria-hidden="true">
            <svg width="12" height="12" viewBox="0 0 14 14"><path d="M9.6 1.4 L12.6 4.4 L4.6 12.4 L1.2 12.8 L1.6 9.4 Z" fill="none" stroke="#3b3651" stroke-width="1.6" stroke-linejoin="round"></path></svg>
          </div>
        </div>"""
    }

    private fun sectionHead(label: String): String = """
        <div class="journal-section">
          <div class="journal-section-bar"></div>
          <div class="journal-section-name">$label</div>
        </div>"""

    private fun bullet(text: String): String = """
        <div class="journal-bullet"><div class="journal-dot"></div><div>$text</div></div>"""

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

    /** [part] of [total] as a rounded percentage; 0 when there is nothing to divide. */
    fun percent(part: Int, total: Int): Int =
        if (total <= 0) 0 else Math.round(part * 100.0 / total).toInt().coerceIn(0, 100)

    /** Naive English plural, enough for the nouns this screen counts. */
    fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"
}
