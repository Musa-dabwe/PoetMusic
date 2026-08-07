package com.musa.poetmusic.server

import com.musa.poetmusic.data.FileExtras
import com.musa.poetmusic.data.Track

/** Full-height three-tab tag editor sheet (Details / Artwork / Lyrics). */
object TagEditorViews {

    private fun editField(
        label: String, name: String, value: String, numeric: Boolean = false, required: Boolean = false
    ): String = """
        <label class="ed-field">
          <span>$label</span>
          <input type="text" name="$name" value="${esc(value)}"${if (numeric) """ inputmode="numeric"""" else ""}${if (required) " required" else ""}>
        </label>"""

    /** A batch field: blank submits as "leave unchanged" (see BatchTagForm). */
    private fun batchField(label: String, name: String, placeholder: String, numeric: Boolean = false): String = """
        <label class="ed-field">
          <span>$label</span>
          <input type="text" name="$name" value="" placeholder="${esc(placeholder)}"${if (numeric) """ inputmode="numeric"""" else ""}>
        </label>"""

    /**
     * The multi-select tag editor (§4). Deliberately a reduced form: the fields
     * that make sense to set identically across many files, and nothing that
     * only makes sense per track (artwork, lyrics, comment, disc number, the
     * rename pattern). Every field starts blank, and blank means "keep what
     * each track already has" — so the sheet can only ever add information.
     */
    fun batchTagEditorSheet(tracks: List<Track>, ids: String, writableCount: Int): String {
        val others = tracks.size - writableCount
        val note = when {
            others == 0 -> "Written into all ${tracks.size} files"
            writableCount == 0 -> "None of these formats can be written here — tags are saved to the Poet library only"
            else -> "$writableCount written to file · $others saved to the library only"
        }
        val preview = tracks.take(3).joinToString(", ") { esc(it.title) } +
            if (tracks.size > 3) " and ${tracks.size - 3} more" else ""

        return """
        <div class="sheet-shield" onclick="poetCloseEditor()"></div>
        <form id="ed-form" class="editor-sheet" hx-put="/api/library/batch-tags?ids=$ids" hx-swap="none">
          <div class="sheet-grab" style="margin:12px auto 12px auto;"></div>

          <div class="ed-head">
            <div class="ed-head-art" style="background:var(--accent-faint);">${tracks.size}</div>
            <div style="flex:1; min-width:0;">
              <div class="ed-head-title">Edit tags for ${tracks.size} songs</div>
              <div class="ed-head-file">$preview</div>
            </div>
            <button type="button" class="ed-close" onclick="poetCloseEditor()" aria-label="Close">
              <svg width="13" height="13" viewBox="0 0 12 12"><path d="M1 1 L11 11 M11 1 L1 11" stroke="currentColor" stroke-width="2" stroke-linecap="round"></path></svg>
            </button>
          </div>

          <div class="ed-body">
            <div class="ed-batch-note">
              Fields you leave empty stay as they are on each song. Anything you type
              is written to <strong>all ${tracks.size}</strong> selected songs.
            </div>
            <div style="display:flex; flex-direction:column; gap:14px;">
              ${batchField("Title", "title", "Leave empty to keep each title")}
              ${batchField("Artist", "artist", "Leave empty to keep")}
              ${batchField("Album", "album", "Leave empty to keep")}
              ${batchField("Album artist", "albumArtist", "Leave empty to keep")}
              <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                ${batchField("Genre", "genre", "Keep", numeric = false)}
                ${batchField("Year", "year", "Keep", numeric = true)}
              </div>
              ${batchField("Track no.", "trackNo", "Leave empty to keep each number", numeric = true)}
            </div>
          </div>

          <div class="ed-savebar">
            <button type="submit" class="btn-primary" style="width:100%; justify-content:center; padding:15px; font-size:15px;">Apply to ${tracks.size} songs</button>
            <div style="font-size:11px; color:var(--muted); text-align:center; margin-top:8px;">$note</div>
          </div>
        </form>"""
    }

    /**
     * Full-height tag editor sheet (Details / Artwork / Lyrics) matching the
     * Poet design. The header "Save Metadata Changes" bar fires an hx-put form
     * bundle at /api/library/edit-tags/{id}; artwork picking, the rename
     * toggle, the lyrics-mode switch and the synced-LRC maker are driven by the
     * scoped script at the bottom. Formats this platform cannot write still
     * save to the library.
     */
    fun tagEditorSheet(t: Track, extras: FileExtras, isCurrent: Boolean, writesToFile: Boolean): String {
        val ext = t.displayName.substringAfterLast('.', "")
        val artInitials = esc(initials(t.title))
        val comment = extras.comment ?: t.comment
        val embeddedLyrics = extras.lyrics ?: ""
        val artThumb =
            if (t.hasArt) """<img src="/api/art/${t.id}" alt="">"""
            else artInitials
        val saveNote =
            if (writesToFile) "Written to the file &amp; re-scanned into the library"
            else "This format can't be written here — tags are saved to the Poet library only"

        return """
        <div class="sheet-shield" onclick="poetCloseEditor()"></div>
        <form id="ed-form" class="editor-sheet" hx-put="/api/library/edit-tags/${t.id}" hx-swap="none"
              data-track-id="${t.id}" data-ext="${esc(ext)}" data-has-art="${if (t.hasArt) 1 else 0}"
              data-is-current="${if (isCurrent) 1 else 0}">
          <div class="sheet-grab" style="margin:12px auto 12px auto;"></div>

          <div class="ed-head">
            <div class="ed-head-art" id="ed-head-art">$artThumb</div>
            <div style="flex:1; min-width:0;">
              <div class="ed-head-title">Edit tags</div>
              <div class="ed-head-file">${esc(t.displayName)}</div>
            </div>
            <button type="button" class="ed-close" onclick="poetCloseEditor()" aria-label="Close">
              <svg width="13" height="13" viewBox="0 0 12 12"><path d="M1 1 L11 11 M11 1 L1 11" stroke="currentColor" stroke-width="2" stroke-linecap="round"></path></svg>
            </button>
          </div>

          <div class="ed-tabs">
            <button type="button" class="ed-tab active" data-tab="details" onclick="poetEdTab('details')">Details</button>
            <button type="button" class="ed-tab" data-tab="artwork" onclick="poetEdTab('artwork')">Artwork</button>
            <button type="button" class="ed-tab" data-tab="lyrics" onclick="poetEdTab('lyrics')">Lyrics</button>
          </div>

          <div class="ed-body">
            <!-- hidden state carried into the submit -->
            <input type="hidden" name="artAction" id="ed-art-action" value="keep">
            <input type="hidden" name="rename" id="ed-rename-flag" value="0">

            <!-- DETAILS -->
            <div class="ed-pane" data-pane="details">
              <div style="display:flex; flex-direction:column; gap:14px;">
                ${editField("Title", "title", t.title, required = true)}
                ${editField("Artist", "artist", t.artist)}
                ${editField("Album", "album", t.album)}
                ${editField("Album artist", "albumArtist", t.albumArtist)}
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                  ${editField("Genre", "genre", t.genre)}
                  ${editField("Year", "year", t.year, numeric = true)}
                </div>
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:14px;">
                  ${editField("Track no.", "trackNo", if (t.trackNo > 0) t.trackNo.toString() else "", numeric = true)}
                  ${editField("Disc no.", "discNo", if (t.discNo > 0) t.discNo.toString() else "", numeric = true)}
                </div>
                ${editField("Composer", "composer", t.composer)}
                <label class="ed-field">
                  <span>Comment</span>
                  <textarea name="comment" rows="2">${esc(comment)}</textarea>
                </label>

                <div class="ed-rename-card">
                  <div style="display:flex; align-items:center; justify-content:space-between; gap:12px;">
                    <div style="min-width:0;">
                      <div style="font-size:13px; font-weight:700;">Rename file from tags</div>
                      <div style="font-size:11px; color:var(--muted);">Rewrite the physical filename on save</div>
                    </div>
                    <button type="button" class="ed-switch" id="ed-rename-switch" onclick="poetEdToggleRename()" aria-label="Toggle rename">
                      <span class="ed-knob"></span>
                    </button>
                  </div>
                  <div id="ed-rename-detail" hidden style="margin-top:12px;">
                    <input type="text" name="renamePattern" id="ed-rename-pattern" value="%track% - %title%.${esc(ext)}"
                           class="ed-mono-input" oninput="poetEdRenamePreview()">
                    <div style="font-size:11px; color:var(--muted); margin-top:8px;">Preview</div>
                    <div id="ed-rename-preview" class="ed-rename-preview"></div>
                  </div>
                </div>
              </div>
            </div>

            <!-- ARTWORK -->
            <div class="ed-pane" data-pane="artwork" hidden>
              <div style="display:flex; flex-direction:column; align-items:center; gap:18px;">
                <div class="ed-art-tile" id="ed-art-tile"></div>
                <div style="display:flex; flex-direction:column; gap:8px; width:100%; max-width:300px;">
                  <button type="button" class="btn-primary" style="width:100%; justify-content:center;" onclick="poetEdPickArt()">Choose from gallery</button>
                  <div style="display:flex; gap:8px;">
                    <button type="button" class="ed-art-btn" id="ed-art-remove" onclick="poetEdArt('remove')">Remove artwork</button>
                    <button type="button" class="ed-art-btn" id="ed-art-restore" hidden onclick="poetEdArt('keep')">Restore embedded</button>
                  </div>
                </div>
                <div style="font-size:12px; color:var(--muted); text-align:center; max-width:280px; line-height:1.5;">
                  ${if (writesToFile) "Select a local PNG or JPEG. Removing artwork trims the embedded image to save file size."
                    else "Artwork is saved inside Poet\u2019s library. Other apps won\u2019t see the change."}
                </div>
              </div>
            </div>

            <!-- LYRICS -->
            <div class="ed-pane" data-pane="lyrics" hidden>
              <div style="display:flex; flex-direction:column; gap:14px;">
                <div class="ed-seg">
                  <button type="button" class="ed-seg-btn active" data-mode="unsynced" onclick="poetEdLyricMode('unsynced')">Unsynced</button>
                  <button type="button" class="ed-seg-btn" data-mode="synced" onclick="poetEdLyricMode('synced')">Synced (LRC)</button>
                </div>

                <div id="ed-lyric-unsynced">
                  <label class="ed-field">
                    <span>Unsynced lyrics (USLT)</span>
                    <textarea name="lyrics" id="ed-lyrics" rows="12" placeholder="Paste raw lyrics here…"
                              style="line-height:1.6;">${esc(embeddedLyrics)}</textarea>
                  </label>
                </div>

                <div id="ed-lyric-synced" hidden>
                  <div class="ed-lrc-transport">
                    <button type="button" class="ed-lrc-play" id="ed-lrc-play" onclick="poetLrcPlay()" aria-label="Play or pause"></button>
                    <div style="flex:1;">
                      <div class="ed-lrc-clock" id="ed-lrc-clock">00:00.00</div>
                      <div class="ed-lrc-count" id="ed-lrc-count">0 of 0 lines stamped</div>
                    </div>
                    <button type="button" class="ed-lrc-reset" onclick="poetLrcReset()">Reset</button>
                  </div>
                  <button type="button" class="ed-lrc-stamp" onclick="poetLrcStamp()">⌖ Stamp current line</button>
                  <div class="ed-lrc-rows" id="ed-lrc-rows"></div>
                  <button type="button" class="ed-lrc-export" id="ed-lrc-export" onclick="poetLrcExport()">Export .lrc file</button>
                </div>
              </div>
            </div>
          </div>

          <div class="ed-savebar">
            <button type="submit" class="btn-primary" style="width:100%; justify-content:center; padding:15px; font-size:15px;">Save Metadata Changes</button>
            <div style="font-size:11px; color:var(--muted); text-align:center; margin-top:8px;">$saveNote</div>
          </div>
        </form>
        <script>poetInitEditor();</script>"""
    }
}
