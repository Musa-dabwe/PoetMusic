package com.musa.poetmusic.server

import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.data.Playlist
import com.musa.poetmusic.data.Track

/** Options bottom drawer, its sub-sheets and the pastel confirmation modals. */
object DrawerViews {

    // Drawer action icons (pastel line-art matching the Poet design).
    private val ICON_D_PLAY = """<svg width="12" height="14" viewBox="0 0 12 14"><polygon points="0,0 12,7 0,14" fill="currentColor"></polygon></svg>"""
    private val ICON_D_NEXT = """<svg width="18" height="14" viewBox="0 0 18 14"><rect x="0" y="1" width="10" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="6" width="10" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="11" width="7" height="2" rx="1" fill="currentColor"></rect><polygon points="13,4 18,7 13,10" fill="currentColor"></polygon></svg>"""
    internal val ICON_D_QUEUE = """<svg width="16" height="14" viewBox="0 0 16 14"><rect x="0" y="1" width="12" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="6" width="12" height="2" rx="1" fill="currentColor"></rect><rect x="0" y="11" width="8" height="2" rx="1" fill="currentColor"></rect></svg>"""
    private val ICON_D_PLAYLIST = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M2 4 H14 M2 8 H10 M2 12 H10 M13 9 V15 M10 12 H16" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round"></path></svg>"""
    private val ICON_D_DELETE = """<svg width="14" height="16" viewBox="0 0 14 16"><path d="M2 4 H12 M5 4 V2 H9 V4 M3 4 L3.7 14 H10.3 L11 4" stroke="#c25f6e" stroke-width="1.6" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_TAGS = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M11 2 L14 5 L5 14 L2 14 L2 11 Z" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_SETAS = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M8 1 L10 6 L15 6 L11 9 L12.5 14 L8 11 L3.5 14 L5 9 L1 6 L6 6 Z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_SHARE = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="12" cy="3" r="2" stroke="currentColor" stroke-width="1.5" fill="none"></circle><circle cx="4" cy="8" r="2" stroke="currentColor" stroke-width="1.5" fill="none"></circle><circle cx="12" cy="13" r="2" stroke="currentColor" stroke-width="1.5" fill="none"></circle><path d="M5.7 7 L10.3 4 M5.7 9 L10.3 12" stroke="currentColor" stroke-width="1.5"></path></svg>"""
    private val ICON_D_ALBUM = """<svg width="16" height="16" viewBox="0 0 16 16"><rect x="1.5" y="1.5" width="13" height="13" rx="3" stroke="currentColor" stroke-width="1.5" fill="none"></rect><circle cx="8" cy="8" r="2" stroke="currentColor" stroke-width="1.5" fill="none"></circle></svg>"""
    private val ICON_D_ARTIST = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="5" r="3" stroke="currentColor" stroke-width="1.5" fill="none"></circle><path d="M2.5 14 C2.5 10.5 5 9 8 9 C11 9 13.5 10.5 13.5 14" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round"></path></svg>"""
    private val ICON_D_FOLDER = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M1.5 4 L6 4 L7.5 6 L14.5 6 L14.5 13 L1.5 13 Z" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linejoin="round"></path></svg>"""
    private val ICON_D_INFO = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.5" fill="none"></circle><path d="M8 7 V11.5 M8 4.6 V5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"></path></svg>"""
    internal val ICON_D_CLOCK = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.5" stroke="currentColor" stroke-width="1.5" fill="none"></circle><path d="M8 4.5 V8 L10.5 9.5" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""
    internal val ICON_D_HOURGLASS = """<svg width="16" height="16" viewBox="0 0 16 16"><path d="M4 1.5 H12 M4 14.5 H12 M5 1.5 V4 C5 6 8 7 8 8 C8 9 5 10 5 12 V14.5 M11 1.5 V4 C11 6 8 7 8 8 C8 9 11 10 11 12 V14.5" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round" stroke-linejoin="round"></path></svg>"""
    internal val ICON_D_TIMER_OFF = """<svg width="16" height="16" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.5" stroke="#c25f6e" stroke-width="1.5" fill="none"></circle><path d="M3.5 3.5 L12.5 12.5" stroke="#c25f6e" stroke-width="1.5" stroke-linecap="round"></path></svg>"""

    /** One tappable row inside the options drawer. */
    internal fun drawerItem(
        icon: String, label: String, attrs: String, sub: String = "", chevron: Boolean = false, danger: Boolean = false
    ): String {
        val labelBlock =
            if (sub.isEmpty()) """<span class="dlabel">$label</span>"""
            else """<span style="flex:1;"><span class="dlabel" style="display:block;">$label</span><span class="dlabel-sub">$sub</span></span>"""
        val chev = if (chevron) """<span class="dchev">›</span>""" else ""
        return """<button class="ditem${if (danger) " ditem-danger" else ""}" $attrs>
          <span class="dicon">$icon</span>$labelBlock$chev
        </button>"""
    }

    /**
     * Full options bottom sheet. Renders the same four grouped sections for a
     * single track and for a batch selection; single-only items (edit tags,
     * navigation) are hidden in batch mode. `ids` is the comma-separated target
     * list carried into every follow-up action; `cq` re-supplies the queue
     * context for play/navigation.
     */
    fun optionsDrawer(db: MusicDatabase, tracks: List<Track>, ids: String, ctx: QueueCtx): String {
        val single = tracks.size == 1
        val first = tracks.first()
        val cq = ctx.query()
        val q = "ids=$ids&$cq"
        val headArt =
            if (single && first.hasArt) """<img src="/api/art/${first.id}?v=${first.lastModified}" alt="">"""
            else if (single) esc(initials(first.title)) else tracks.size.toString()
        val headBg = if (single) artColor(first.id) else "var(--accent-faint)"
        val title = if (single) esc(first.title) else "${tracks.size} tracks selected"
        val sub = if (single) esc(first.artist) else "Batch actions"

        // Playlist & file
        val removeFromPlaylist =
            if (single && ctx.ctx == "playlist" && ctx.pid != 0L)
                drawerItem(ICON_D_PLAYLIST, "Remove from this playlist",
                    """hx-post="/api/playlist/${ctx.pid}/remove/${first.id}" hx-swap="none" hx-on::after-request="poetFinish()"""")
            else ""

        // Single edits the whole track; a batch gets the reduced keep-unchanged
        // form, which only ever sets the fields the user actually fills in.
        val editTags = if (single)
            drawerItem(ICON_D_TAGS, "Edit tags",
                """hx-get="/api/library/edit-tags/${first.id}" hx-target="#modal-root" hx-swap="innerHTML" hx-on::after-request="poetFinish()"""")
        else
            drawerItem(ICON_D_TAGS, "Edit tags",
                """hx-get="/api/library/batch-tags?ids=$ids" hx-target="#modal-root" hx-swap="innerHTML" hx-on::after-request="poetFinish()"""",
                sub = "Set a field across all ${tracks.size}")

        val navSection = if (single) """
          <div class="dsec">Navigation &amp; info</div>
          ${drawerItem(ICON_D_ALBUM, "Go to album",
            """hx-get="/screens/album?album=${enc(first.album)}&artist=${enc(first.artist)}" hx-target="#main-container" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_ARTIST, "Go to artist",
            """hx-get="/screens/artist?name=${enc(first.artist)}" hx-target="#main-container" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_FOLDER, "Go to folder", """onclick="poetToast('Opening folder…'); poetFinish();"""")}
          ${drawerItem(ICON_D_INFO, "Song info / details",
            """hx-get="/api/library/sub?kind=info&$q" hx-target="#sheet-root" hx-swap="innerHTML"""")}
        """ else ""

        return """
        <div class="sheet-shield" onclick="poetDrawerClose()"></div>
        <div class="drawer">
          <div class="sheet-grab"></div>
          <div class="drawer-head">
            <div class="drawer-head-art" style="background:$headBg;">$headArt</div>
            <div style="flex:1; min-width:0;">
              <div style="font-size:15px; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">$title</div>
              <div style="font-size:12px; color:var(--muted); white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">$sub</div>
            </div>
          </div>

          <div class="dsec">Playback &amp; queue</div>
          ${drawerItem(ICON_D_PLAY, "Play now", """hx-post="/api/tracks/play-now?$q" hx-swap="none" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_NEXT, "Play next", """hx-post="/api/tracks/play-next?$q" hx-swap="none" hx-on::after-request="poetFinish()"""")}
          ${drawerItem(ICON_D_QUEUE, "Add to queue", """hx-post="/api/tracks/add-queue?$q" hx-swap="none" hx-on::after-request="poetFinish()"""")}

          <div class="dsec">Playlist &amp; file</div>
          ${drawerItem(ICON_D_PLAYLIST, "Add to playlist",
            """hx-get="/api/library/sub?kind=addplaylist&$q" hx-target="#sheet-root" hx-swap="innerHTML"""")}
          $removeFromPlaylist
          ${if (ctx.ctx == "playlist") "" else drawerItem(ICON_D_DELETE, "Delete from device",
            """hx-get="/api/library/sub?kind=delete&$q" hx-target="#sheet-root" hx-swap="innerHTML"""", danger = true)}

          <div class="dsec">Metadata &amp; utilities</div>
          $editTags
          ${drawerItem(ICON_D_SETAS, "Set as…",
            """hx-get="/api/library/sub?kind=setas&$q" hx-target="#sheet-root" hx-swap="innerHTML"""",
            sub = "Ringtone, notification, alarm", chevron = true)}
          ${drawerItem(ICON_D_SHARE, if (single) "Share" else "Share ${tracks.size} songs",
            """hx-post="/api/tracks/share?ids=$ids" hx-swap="none" hx-on::after-request="poetFinish()"""")}

          $navSection
        </div>"""
    }

    /** Dispatches a drawer sub-sheet by kind. */
    fun subSheet(kind: String, db: MusicDatabase, tracks: List<Track>, ids: String, ctx: QueueCtx, infoSizeBytes: Long = -1): String {
        val cq = ctx.query()
        val back = "poetSubBack('$ids','${esc(cq)}')"
        return when (kind) {
            "addplaylist" -> addPlaylistSheet(db, tracks, ids, back)
            "setas" -> setAsSheet(back)
            "info" -> songInfoSheet(tracks.first(), infoSizeBytes, back)
            "delete" -> deleteConfirmSheet(tracks, ids, back)
            else -> ""
        }
    }

    private fun targetLabel(tracks: List<Track>): String =
        if (tracks.size == 1) "“${esc(tracks.first().title)}”"
        else "${tracks.size} songs"

    private fun addPlaylistSheet(db: MusicDatabase, tracks: List<Track>, ids: String, back: String): String {
        val picks = db.playlists().joinToString("") { p ->
            """
            <button class="pl-pick" data-pid="${p.id}" onclick="poetPlToggle(this)">
              <span class="pl-check">${SharedViews.CHECK_SVG}</span>
              <span class="pl-art" style="background:${artColor(p.id)};">♪</span>
              <span style="flex:1;"><span style="font-size:14px; font-weight:600; display:block;">${esc(p.name)}</span><span style="font-size:12px; color:var(--muted);">${p.trackCount} songs</span></span>
            </button>"""
        }.ifBlank { """<div style="font-size:12px; color:var(--muted); padding:6px 2px;">No playlists yet — create one below.</div>""" }
        return """
        <div class="sheet-shield" onclick="$back"></div>
        <div class="sheet sheet-tall">
          <div class="sheet-grab"></div>
          <div class="sheet-title" style="margin-bottom:4px;">Add to playlist</div>
          <div style="font-size:12px; color:var(--muted); margin-bottom:14px;">${targetLabel(tracks)}</div>
          <form class="pl-create" hx-post="/api/playlist/create?ids=$ids" hx-swap="none"
                hx-on::after-request="poetFinish()">
            <input name="name" placeholder="Create new playlist…" required>
            <button type="submit">Create</button>
          </form>
          <div style="display:flex; flex-direction:column; gap:4px;">$picks</div>
          <button class="sheet-btn" onclick="poetAddPlaylists('$ids')">Done</button>
        </div>"""
    }

    private fun setAsSheet(back: String): String {
        val opts = listOf("Ringtone", "Notification sound", "Alarm").joinToString("") { x ->
            """<button class="setas-opt" onclick="poetToast('Set as ${x.lowercase()}'); poetFinish();">$x</button>"""
        }
        return """
        <div class="sheet-shield" onclick="$back"></div>
        <div class="sheet">
          <div class="sheet-grab"></div>
          <div class="sheet-title" style="margin-bottom:12px;">Set track as</div>
          <div style="display:flex; flex-direction:column; gap:8px;">$opts</div>
        </div>"""
    }

    /** Human-readable file size for the info sheet ("weight" of the track). */
    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun songInfoSheet(t: Track, sizeBytes: Long, back: String): String {
        val ext = t.displayName.substringAfterLast('.', "").uppercase().ifBlank { "—" }
        val added = if (t.dateAdded > 0)
            java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(t.dateAdded)) else "—"
        val rows = listOf(
            "Title" to t.title,
            "Artist" to t.artist.ifBlank { "—" },
            "File name" to t.displayName,
            "Format" to ext,
            "File size" to fmtSize(sizeBytes),
            "Duration" to fmtTime(t.durationMs),
            "Album" to t.album.ifBlank { "—" },
            "Track / disc" to "${if (t.trackNo > 0) t.trackNo else "—"} / ${if (t.discNo > 0) t.discNo else "—"}",
            "Year" to t.year.ifBlank { "—" },
            "Date added" to added
        ).joinToString("") { (k, v) ->
            """<div class="info-row"><span class="info-k">$k</span><span class="info-v">${esc(v)}</span></div>"""
        }
        return """
        <div class="center-shield" onclick="if(event.target===this) $back">
          <div class="info-card">
            <div style="font-size:16px; font-weight:700; margin-bottom:2px;">${esc(t.title)}</div>
            <div style="font-size:12px; color:var(--muted); margin-bottom:16px;">${esc(t.artist)}</div>
            <div style="display:flex; flex-direction:column; gap:10px;">$rows</div>
            <button class="btn-primary" style="width:100%; justify-content:center; margin-top:18px;" onclick="$back">Close</button>
          </div>
        </div>"""
    }

    private fun deleteConfirmSheet(tracks: List<Track>, ids: String, back: String): String {
        val msg =
            if (tracks.size == 1) "Delete “${esc(tracks.first().title)}”?"
            else "Delete ${tracks.size} songs?"
        return """
        <div class="center-shield" onclick="if(event.target===this) $back">
          <div class="confirm-card">
            <div class="confirm-title">Delete from device</div>
            <div class="confirm-msg">$msg This permanently deletes the file${if (tracks.size == 1) "" else "s"} from your device.</div>
            <div class="confirm-actions">
              <button class="btn-ghost" onclick="$back">CANCEL</button>
              <button class="btn-delete" hx-post="/api/tracks/delete?ids=$ids" hx-swap="none"
                      hx-on::after-request="poetFinish()">DELETE</button>
            </div>
          </div>
        </div>"""
    }

    // ---------------- native-fidelity confirmation modal ----------------

    /**
     * Pastel replacement for the WebView's "http://127.0.0.1 says" confirm()
     * dialog: rounded 18px card over a blurred dim, borderless CANCEL that
     * swaps an empty fragment into #modal-root, filled accent OK.
     */
    private fun confirmModal(title: String, messageHtml: String, okAttrs: String): String = """
        <div class="modal-shield" onclick="if(event.target===this) document.getElementById('modal-root').innerHTML=''">
          <div class="confirm-card">
            <div class="confirm-title">$title</div>
            <div class="confirm-msg">$messageHtml</div>
            <div class="confirm-actions">
              <button class="btn-ghost" hx-get="/partial/empty" hx-target="#modal-root" hx-swap="innerHTML">CANCEL</button>
              <button class="btn-primary confirm-ok" $okAttrs hx-swap="none"
                      hx-on::after-request="document.getElementById('modal-root').innerHTML=''">OK</button>
            </div>
          </div>
        </div>"""

    fun confirmRemoveFolder(id: Long, displayPath: String): String {
        val folderName = displayPath.trimEnd('/').substringAfterLast('/').ifBlank { displayPath }
        return confirmModal(
            "Remove folder",
            """Are you sure you want to remove <span class="confirm-strong">${esc(folderName)}</span> from the library?""",
            """hx-delete="/api/settings/folder?id=$id""""
        )
    }

    fun confirmRemoveTrack(t: Track): String = confirmModal(
        "Remove from library",
        """Remove <span class="confirm-strong">${esc(t.title)}</span> from the library? The file is kept on disk.""",
        """hx-post="/api/track/${t.id}/remove""""
    )

    fun confirmDeletePlaylist(pl: Playlist): String = confirmModal(
        "Delete playlist",
        """Delete the playlist <span class="confirm-strong">${esc(pl.name)}</span>? Its songs stay in the library.""",
        """hx-post="/api/playlist/${pl.id}/delete""""
    )
}
