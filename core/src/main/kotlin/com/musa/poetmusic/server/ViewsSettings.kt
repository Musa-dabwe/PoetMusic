package com.musa.poetmusic.server

import com.musa.poetmusic.data.LibraryStore
import com.musa.poetmusic.data.ScanPort
import com.musa.poetmusic.playback.EqPort
import com.musa.poetmusic.playback.EqPresets

/** Settings screen, scan card and the About screen. */
object SettingsViews {

    fun settingsScreen(db: LibraryStore, eq: EqPort, scanner: ScanPort, prevRestartMs: Long, showTip: Boolean): String {
        val folders = db.folders()
        val folderRows = folders.joinToString("") { f ->
            """
            <div class="folder-row">
              <span style="font-size:14px;">▣</span>
              <span class="folder-path">${esc(f.displayPath)}</span>
              <button hx-get="/partial/confirm-folder/${f.id}" hx-target="#modal-root" hx-swap="innerHTML"
                      style="border:none; background:transparent; cursor:pointer; color:var(--muted); font-size:15px; padding:2px 6px; border-radius:6px;">✕</button>
            </div>"""
        }
        val tip = if (showTip) """
            <div class="tip-banner" id="tip-banner">
              <div class="tip-arrow"></div>
              <div class="tip-body">
                <div style="font-size:14px; font-weight:700; margin-bottom:4px;">Start here</div>
                <div style="font-size:13px; line-height:1.45; color:rgba(245,243,250,0.85);">Add a folder so Poet can find your music. You can add more anytime.</div>
                <button onclick="dismissTip()" style="border:none; cursor:pointer; font-family:inherit; margin-top:12px; padding:8px 14px; border-radius:99px; background:var(--accent); color:#3b3651; font-size:13px; font-weight:700;">Got it, don't show again</button>
              </div>
            </div>""" else ""
        val shield = if (showTip) """<div id="tip-shield" onclick="dismissTip()"></div>""" else ""

        val accent = db.getSetting("accent", "#b9a5ec")
        val theme = db.getSetting("theme", "Lavender")
        val dark = db.getSetting("dark", "0") == "1"
        val swatches = Shell.ACCENTS.joinToString("") { c ->
            """<button class="swatch${if (c == accent) " on" else ""}" data-c="$c" style="background:$c;" onclick="setAccent('$c')"></button>"""
        }
        val tints = Shell.CANVAS_TINTS.entries.joinToString("") { (name, bg) ->
            """<button class="tint-pill${if (name == theme) " on" else ""}" data-n="$name" style="background:$bg;" onclick="setTheme('$name','$bg')">$name</button>"""
        }

        return """
        <div class="screen" data-screen="settings" style="display:flex; flex-direction:column; gap:16px;">
          <div style="font-size:22px; font-weight:700; letter-spacing:-0.02em;">Settings</div>

          <div class="card" id="folder-card" style="position:relative; z-index:${if (showTip) 50 else 1};">
            <div class="card-title">Music folders</div>
            <div class="card-sub">Local directories scanned for audio files</div>
            <div style="display:flex; flex-direction:column; gap:8px; margin-bottom:14px;">
              ${folderRows.ifBlank { """<div style="font-size:12px; color:var(--muted); padding:4px 2px;">No folders mapped yet.</div>""" }}
            </div>
            <button class="btn-primary" style="width:100%; justify-content:center;" hx-post="/api/settings/add-folder" hx-swap="none" onclick="localStorage.setItem('poet-tip-dismissed','1')">+ Add folder directory</button>
            $tip
          </div>
          $shield

          <div class="card">
            <div class="card-title">Library scan</div>
            <div id="scan-card">${scanCard(db, scanner)}</div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:4px;">Equalizer</div>
            <div class="card-sub">Shape the sound of every track Poet plays</div>
            <div id="eq-card">${equalizerCard(eq)}</div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:4px;">Playback</div>
            <div class="card-sub">Restart the current song when Previous is pressed after…</div>
            <div style="display:flex; gap:8px; flex-wrap:wrap;">${prevOptions(prevRestartMs)}</div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:4px;">Appearance</div>
            <div class="card-sub">Switch between the light and dark colour set</div>
            <div style="display:flex; gap:8px;">
              <button class="theme-opt${if (!dark) " on" else ""}" data-dark="0" onclick="setDark(false)">
                <span style="font-size:15px;">☀</span> Light
              </button>
              <button class="theme-opt${if (dark) " on" else ""}" data-dark="1" onclick="setDark(true)">
                <span style="font-size:15px;">☾</span> Dark
              </button>
            </div>
          </div>

          <div class="card">
            <div class="card-title" style="margin-bottom:12px;">Accent color</div>
            <div style="display:flex; gap:12px; margin-bottom:18px;">$swatches</div>
            <div class="card-title" style="margin-bottom:12px;">Canvas tint</div>
            <div class="card-sub">Used in light mode</div>
            <div style="display:flex; gap:8px; flex-wrap:wrap;">$tints</div>
          </div>

          <!-- Portrait only: from the landscape breakpoint upwards About is a
               nav-rail destination (Shell.kt), so this card hides itself rather
               than offering the same screen twice. -->
          <div class="card settings-about" style="cursor:pointer;" hx-get="/screens/about" hx-target="#main-container">
            <div style="display:flex; align-items:center; justify-content:space-between; gap:12px;">
              <div style="min-width:0;">
                <div class="card-title" style="margin-bottom:2px;">About Poet Music</div>
                <div class="card-sub" style="margin-bottom:0;">Version, tech stack, license &amp; developer</div>
              </div>
              <span style="color:var(--faint); font-size:20px; flex-shrink:0;">›</span>
            </div>
          </div>
        </div>"""
    }

    // ---------------- equalizer ----------------

    /**
     * The audio effects card: master switch, preset selector, one vertical
     * slider per device band, and the bass boost / virtualizer strengths.
     *
     * The whole card re-renders on every change (hx-target="#eq-card") because
     * a preset moves every band at once, and the sliders have to follow. The
     * band count and centre frequencies come from the device, not from us.
     */
    fun equalizerCard(eq: EqPort): String {
        val s = eq.state()
        if (!s.available && !s.bassAvailable && !s.virtualizerAvailable) {
            return """<div style="font-size:12px; color:var(--muted); line-height:1.5;">
              This device doesn't offer the system audio effects Poet drives, so the
              equalizer is unavailable. Playback is unaffected.
            </div>"""
        }
        val toggle = """
        <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:16px;">
          <div style="min-width:0;">
            <div style="font-size:13px; font-weight:700;">Enable equalizer</div>
            <div style="font-size:11px; color:var(--muted);">${if (s.enabled) "Effects are being applied" else "Audio is passed through untouched"}</div>
          </div>
          <button type="button" class="ed-switch${if (s.enabled) " on" else ""}"
                  hx-post="/api/eq/enabled?on=${if (s.enabled) 0 else 1}" hx-target="#eq-card" hx-swap="innerHTML"
                  aria-label="Toggle equalizer"><span class="ed-knob"></span></button>
        </div>"""

        val presets = EqPresets.NAMES.joinToString("") { name ->
            """<button type="button" class="chip${if (name == s.preset) " on" else ""}"
                    hx-post="/api/eq/preset?name=${enc(name)}" hx-target="#eq-card" hx-swap="innerHTML">$name</button>"""
        }

        val bands = if (!s.available) "" else {
            val sliders = s.bands.joinToString("") { b ->
                val dB = b.levelMillibels / 100.0
                """
                <div class="eq-band">
                  <div class="eq-band-val" id="eq-val-${b.index}">${fmtGain(dB)}</div>
                  <input class="eq-slider" type="range" orient="vertical"
                         min="${s.minLevel}" max="${s.maxLevel}" step="100" value="${b.levelMillibels}"
                         oninput="poetEqLabel(${b.index}, this.value)"
                         hx-post="/api/eq/band?i=${b.index}" name="level"
                         hx-trigger="change" hx-target="#eq-card" hx-swap="innerHTML"
                         aria-label="${EqPresets.label(b.centerHz)} Hz">
                  <div class="eq-band-hz">${EqPresets.label(b.centerHz)}</div>
                </div>"""
            }
            """<div class="eq-bands${if (s.enabled) "" else " eq-off"}">$sliders</div>"""
        }

        val bass = if (!s.bassAvailable) "" else
            strengthRow("Bass boost", "bass", s.bassStrength, s.enabled)
        val virt = if (!s.virtualizerAvailable) "" else
            strengthRow("Virtualizer", "virtualizer", s.virtualizerStrength, s.enabled)

        return """
        $toggle
        <div style="font-size:12px; font-weight:700; color:var(--muted); margin-bottom:8px;">Preset</div>
        <div style="display:flex; gap:8px; flex-wrap:wrap; margin-bottom:16px;">$presets</div>
        $bands
        $bass
        $virt"""
    }

    /**
     * A 0-1000 strength slider for one of the non-EQ effects. The filled part
     * of the track is an inline gradient, the same trick the Now Playing seek
     * bar uses, so the control reads as filled without any client-side paint.
     */
    private fun strengthRow(label: String, kind: String, value: Int, enabled: Boolean): String {
        val pct = value * 100 / EqPort.STRENGTH_MAX
        return """
        <div class="eq-strength${if (enabled) "" else " eq-off"}">
          <div style="display:flex; justify-content:space-between; font-size:12px; font-weight:700; margin-bottom:6px;">
            <span>$label</span><span style="color:var(--muted);" id="eq-$kind-val">$pct%</span>
          </div>
          <input class="seek" type="range" min="0" max="${EqPort.STRENGTH_MAX}" step="50" value="$value" name="v"
                 style="background:linear-gradient(to right, var(--accent) $pct%, var(--track-empty) $pct%);"
                 oninput="poetEqStrength('$kind', this)"
                 hx-post="/api/eq/$kind" hx-trigger="change" hx-target="#eq-card" hx-swap="innerHTML">
        </div>"""
    }

    /** Band gain shown above its slider ("+3 dB", "0 dB"). */
    private fun fmtGain(dB: Double): String {
        val rounded = Math.round(dB).toInt()
        return when {
            rounded > 0 -> "+$rounded"
            else -> rounded.toString()
        }
    }

    // ---------------- playback ----------------

    /** The Previous-button restart thresholds, in seconds (0 = always previous). */
    val PREV_THRESHOLDS = listOf(0 to "Always previous", 3 to "3 seconds", 5 to "5 seconds", 10 to "10 seconds")

    private fun prevOptions(current: Long): String {
        return PREV_THRESHOLDS.joinToString("") { (sec, label) ->
            val on = sec * 1000L == current
            """<button type="button" class="chip${if (on) " on" else ""}"
                    hx-post="/api/settings/prev-restart?sec=$sec"
                    hx-target="#main-container" hx-swap="innerHTML">$label</button>"""
        }
    }

    // ---------------- about ----------------

    /**
     * The About screen shown from Settings, built from the project's README,
     * license, security policy, tech stack and developer info and rendered
     * through the in-app [Markdown] renderer so it follows the active theme.
     */
    fun aboutScreen(db: LibraryStore, about: AboutSpec): String {
        val md = AboutDoc.markdown(about, db.trackCount(), db.folders().size)

        return """
        <div class="screen" data-screen="about">
          <button class="backlink" hx-get="/screens/settings" hx-target="#main-container">← Settings</button>
          <!-- the rail is the way back once it is showing; see poet.css -->
          <div class="card">${Markdown.render(md)}</div>
        </div>"""
    }

    /**
     * Scan card body. Only polls /partial/scan while a scan is actually
     * running: the polling wrapper is part of the swapped content, so the
     * every-2s DOM churn stops as soon as the scan finishes instead of
     * tearing the card down forever while the user reads Settings.
     */
    fun scanCard(db: LibraryStore, scanner: ScanPort): String {
        val scanning = scanner.isScanning
        val status = if (scanning) esc(scanner.progressText)
        else esc(db.getSetting("last_scan", "Not scanned yet"))
        val auto = scanner.autoScanEnabled()
        val intervals = ScanPort.INTERVAL_CHOICES.joinToString("") { h ->
            val on = h == scanner.intervalHours()
            """<button type="button" class="chip${if (on) " on" else ""}"
                    hx-post="/api/library/scan-interval?h=$h" hx-target="#scan-card" hx-swap="innerHTML">${h}h</button>"""
        }
        val autoBlock = """
        <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:16px;">
          <div style="min-width:0;">
            <div style="font-size:13px; font-weight:700;">Keep library in sync</div>
            <div style="font-size:11px; color:var(--muted); line-height:1.45;">Rescans on start when stale, and watches mapped folders while Poet is open</div>
          </div>
          <button type="button" class="ed-switch${if (auto) " on" else ""}"
                  hx-post="/api/library/auto-scan?on=${if (auto) 0 else 1}" hx-target="#scan-card" hx-swap="innerHTML"
                  aria-label="Toggle automatic rescan"><span class="ed-knob"></span></button>
        </div>
        ${if (!auto) "" else """
        <div style="margin-top:12px;">
          <div style="font-size:12px; font-weight:700; color:var(--muted); margin-bottom:8px;">Rescan when older than</div>
          <div style="display:flex; gap:8px; flex-wrap:wrap;">$intervals</div>
        </div>"""}"""
        val body = """
        <div class="card-sub">$status</div>
        <button class="btn-outline" hx-post="/api/library/scan" hx-target="#scan-card" hx-swap="innerHTML" ${if (scanning) "disabled" else ""}>
          ${if (scanning) """<span class="spinner"></span> Scanning library…""" else """<span style="font-size:15px;">⟳</span> Trigger library scan"""}
        </button>
        $autoBlock"""
        return if (scanning)
            """<div hx-get="/partial/scan" hx-trigger="every 2s" hx-target="#scan-card" hx-swap="innerHTML">$body</div>"""
        else body
    }
}
