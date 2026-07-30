package com.musa.poetmusic.server

import com.musa.poetmusic.data.LyricLine
import com.musa.poetmusic.data.MusicDatabase
import com.musa.poetmusic.playback.PlayerController

/** Now Playing screen, its transport buttons, lyrics deck and sleep drawer. */
object NowPlayingViews {

    private fun speedLabel(speed: Float): String =
        listOf(0.75f to "0.75", 1f to "1.0", 1.25f to "1.25", 1.5f to "1.5", 2f to "2.0")
            .firstOrNull { kotlin.math.abs(it.first - speed) < 0.01f }?.second
            ?: speed.toString()

    /** Uploaded play-in-order glyph (512-grid trace), recolored via currentColor. */
    private val ICON_PLAY_IN_ORDER = """<svg width="20" height="20" viewBox="0 0 512 512"><path d="M0 0 C141.24 0 282.48 0 428 0 C428 14.52 428 29.04 428 44 C286.76 44 145.52 44 0 44 C0 29.48 0 14.96 0 0 Z " fill="currentColor" transform="translate(42,390)"/><path d="M0 0 C3.8590715 0.96476788 6.07340874 2.10233844 9.41015625 4.17578125 C10.47250488 4.83263916 11.53485352 5.48949707 12.62939453 6.16625977 C13.77386615 6.88150819 14.91823219 7.59692557 16.0625 8.3125 C17.24569307 9.04717693 18.42912638 9.78146707 19.61279297 10.51538086 C27.83053807 15.61756787 36.01510561 20.7725153 44.18359375 25.953125 C49.67013324 29.42988014 55.17820548 32.87195309 60.6875 36.3125 C62.20174561 37.25814819 62.20174561 37.25814819 63.74658203 38.22290039 C65.80412772 39.5076015 67.86174532 40.79218745 69.91943359 42.07666016 C74.94746487 45.21589223 79.97365341 48.35807151 85 51.5 C86.99999085 52.75001464 88.99999086 54.00001462 91 55.25 C103 62.75 115 70.25 127 77.75 C128.48753784 78.6795752 128.48753784 78.6795752 130.00512695 79.62792969 C131.98836694 80.86763656 133.97128171 82.10786388 135.95385742 83.34863281 C141.20762148 86.63564309 146.46810942 89.91122582 151.73828125 93.171875 C153.38860352 94.19571289 153.38860352 94.19571289 155.07226562 95.24023438 C157.18739593 96.55215064 159.30449951 97.86089236 161.42382812 99.16601562 C162.37322266 99.75576172 163.32261719 100.34550781 164.30078125 100.953125 C165.13778564 101.47036133 165.97479004 101.98759766 166.8371582 102.52050781 C168.81113979 103.87081015 170.38442439 105.24611464 172 107 C164.72053162 112.24458839 157.22750617 117.02906084 149.5625 121.6875 C147.24236261 123.10493207 144.92383557 124.52498678 142.60546875 125.9453125 C142.03483704 126.29459305 141.46420532 126.6438736 140.87628174 127.0037384 C136.01596749 129.98190653 131.18796188 133.00856359 126.375 136.0625 C119.37021169 140.5051139 112.3480813 144.91878907 105.3125 149.3125 C104.30300293 149.94293213 103.29350586 150.57336426 102.25341797 151.22290039 C100.19587228 152.5076015 98.13825468 153.79218745 96.08056641 155.07666016 C91.05253513 158.21589223 86.02634659 161.35807151 81 164.5 C79.00000915 165.75001464 77.00000914 167.00001462 75 168.25 C74.01 168.86875 73.02 169.4875 72 170.125 C63 175.75 63 175.75 59.9987793 177.62573242 C58.00281172 178.87323247 56.00688061 180.12079088 54.01098633 181.3684082 C48.94959084 184.53214182 43.88752606 187.69479561 38.82421875 190.85546875 C37.80666504 191.490896 36.78911133 192.12632324 35.74072266 192.78100586 C33.72637207 194.03880721 31.71172983 195.29614162 29.69677734 196.55297852 C24.89703779 199.55012798 20.10798279 202.56216199 15.33984375 205.609375 C14.01690308 206.45302612 14.01690308 206.45302612 12.66723633 207.3137207 C11.01220973 208.37082067 9.35956899 209.43166839 7.7097168 210.49682617 C6.98308838 210.96064697 6.25645996 211.42446777 5.5078125 211.90234375 C4.87504395 212.30960693 4.24227539 212.71687012 3.59033203 213.13647461 C2 214 2 214 0 214 C0 143.38 0 72.76 0 0 Z " fill="currentColor" transform="translate(42,81)"/><path d="M0 0 C70.62 0 141.24 0 214 0 C214 14.52 214 29.04 214 44 C143.38 44 72.76 44 0 44 C0 29.48 0 14.96 0 0 Z " fill="currentColor" transform="translate(256,91)"/><path d="M0 0 C70.62 0 141.24 0 214 0 C214 14.19 214 28.38 214 43 C143.38 43 72.76 43 0 43 C0 28.81 0 14.62 0 0 Z " fill="currentColor" transform="translate(256,241)"/></svg>"""

    /** Uploaded play-single-and-stop glyph (512-grid trace), recolored via currentColor. */
    private val ICON_PLAY_SINGLE_STOP = """<svg width="20" height="20" viewBox="0 0 512 512"><path d="M0 0 C0.78100571 0.72451355 1.56201141 1.4490271 2.36668396 2.19549561 C3.2414035 3.00595337 4.11612305 3.81641113 5.01734924 4.65142822 C9.39795551 8.82383049 13.71296948 13.05623002 17.99339294 17.33123779 C19.3931411 18.72599335 19.3931411 18.72599335 20.82116699 20.14892578 C22.77494405 22.09678124 24.72671185 24.046624 26.67723083 25.9977417 C29.16909121 28.48970458 31.66781974 30.97465965 34.16826534 33.458004 C36.57280917 35.84874947 38.97014543 38.24667642 41.36839294 40.64373779 C42.26211105 41.53027039 43.15582916 42.41680298 44.07662964 43.3302002 C45.31921288 44.57739838 45.31921288 44.57739838 46.5868988 45.84979248 C47.3139653 46.57543396 48.0410318 47.30107544 48.79013062 48.04870605 C52.83748842 52.61563998 54.89266451 57.04032684 54.71604919 63.22186279 C53.02172268 69.10217245 50.55568725 71.79378584 45.71604919 75.22186279 C42.71592487 76.22190423 40.73703622 76.3490518 37.60316086 76.35329056 C36.53906895 76.3571317 35.47497705 76.36097285 34.37863994 76.36493039 C33.20541407 76.36406391 32.0321882 76.36319743 30.82341003 76.36230469 C28.95874836 76.36676137 28.95874836 76.36676137 27.05641675 76.37130809 C23.59921384 76.378497 20.14206062 76.38116808 16.68485379 76.38190556 C12.95876078 76.38375482 9.23267857 76.39130154 5.5065918 76.39804077 C-2.64426557 76.41163015 -10.79511803 76.41764981 -18.94598484 76.42205143 C-24.03449928 76.42481295 -29.12301181 76.42905015 -34.21152496 76.4335556 C-48.29729722 76.44574929 -62.38306795 76.45604826 -76.46884537 76.45943069 C-77.3706005 76.45965026 -78.27235564 76.45986983 -79.20143668 76.46009605 C-80.55732662 76.46042346 -80.55732662 76.46042346 -81.94060826 76.46075749 C-83.77234093 76.46120096 -85.6040736 76.46164753 -87.43580627 76.46209717 C-88.3444111 76.4623186 -89.25301593 76.46254003 -90.18915424 76.46276817 C-104.91177143 76.46671616 -119.63433913 76.48416978 -134.3569372 76.50745669 C-149.46872379 76.53116643 -164.58048553 76.54363238 -179.69229102 76.54479861 C-188.17848133 76.54571561 -196.66460716 76.55147411 -205.15077972 76.56961823 C-212.37713255 76.58501415 -219.60340097 76.59014271 -226.82976708 76.58188012 C-230.51670387 76.57797174 -234.20347067 76.57891225 -237.89038658 76.59293747 C-241.88817305 76.60800614 -245.885576 76.59981547 -249.88337708 76.58935547 C-251.04768006 76.59741187 -252.21198304 76.60546828 -253.41156793 76.61376882 C-259.4754651 76.57658009 -263.96858043 76.24319371 -269.28395081 73.22186279 C-273.46148936 68.82445379 -274.59921332 65.36137089 -274.49879456 59.28045654 C-274.02838933 54.77311924 -272.06349256 51.96142805 -268.84645081 48.90936279 C-265.13141433 46.46287536 -262.06023348 46.09778024 -257.66877365 46.08319759 C-256.74119303 46.07772315 -255.8136124 46.07224871 -254.85792327 46.06660837 C-253.84236428 46.06563026 -252.8268053 46.06465216 -251.7804718 46.06364441 C-250.69899628 46.05870643 -249.61752076 46.05376845 -248.50327325 46.04868084 C-244.86734182 46.0332819 -241.23142809 46.02493869 -237.59547424 46.01654053 C-234.9939305 46.00696389 -232.39238831 45.99695394 -229.79084778 45.98654175 C-224.18367499 45.9647859 -218.57649983 45.94663815 -212.96930695 45.93093681 C-204.86223516 45.90823007 -196.75518943 45.88008835 -188.64813974 45.85061754 C-175.49517941 45.8029515 -162.34220843 45.75968463 -149.18922424 45.71917725 C-136.41187206 45.6798173 -123.63452934 45.63838987 -110.85719299 45.59417725 C-109.67591909 45.59009084 -109.67591909 45.59009084 -108.47078106 45.58592188 C-104.52006867 45.57224232 -100.56935642 45.55852206 -96.61864424 45.54478157 C-63.84043262 45.43083231 -31.06220133 45.32401952 1.71604919 45.22186279 C1.04025818 44.56701904 0.36446716 43.91217529 -0.33180237 43.23748779 C-2.85979358 40.77810232 -5.37819101 38.30963235 -7.8908844 35.83465576 C-8.97546083 34.77006261 -10.06361695 33.70910258 -11.15553284 32.65203857 C-24.43303258 19.78756261 -24.43303258 19.78756261 -25.66676331 11.71795654 C-24.93011238 5.83591055 -22.96431242 1.96192602 -18.28395081 -1.77813721 C-11.90512416 -4.7870177 -5.59863032 -4.35873349 0 0 Z " fill="currentColor" transform="translate(340.28395080566406,194.77813720703125)"/><path d="M0 0 C2.99417353 2.55385389 5.3603812 5.2832624 7.125 8.8125 C7.22248974 10.49009403 7.25599492 12.17156469 7.25922108 13.85198593 C7.26367892 14.92515367 7.26813676 15.99832141 7.27272969 17.10400939 C7.27254533 18.29250306 7.27236097 19.48099674 7.27217102 20.70550537 C7.27584771 21.96071495 7.2795244 23.21592453 7.2833125 24.50917077 C7.29334855 28.00475471 7.29705187 31.50032148 7.29970181 34.9959178 C7.30360012 38.76154787 7.31317879 42.52716428 7.32191467 46.29278564 C7.34169263 55.39253816 7.35174704 64.49229181 7.36064246 73.59206045 C7.36502324 77.87493486 7.3703906 82.15780803 7.37563133 86.44068146 C7.39264505 100.67396157 7.40713983 114.90724091 7.41438007 129.14052963 C7.41629004 132.83554841 7.41821036 136.53056718 7.42016602 140.22558594 C7.42064976 141.14399042 7.4211335 142.06239491 7.4216319 143.00862983 C7.42993445 157.89043166 7.45527452 172.77215316 7.48774669 187.65391991 C7.52079729 202.9250014 7.53882703 218.19604205 7.54202431 233.46715951 C7.54419129 242.04445897 7.55296717 250.6216413 7.57848549 259.19890594 C7.60016522 266.50291425 7.60825641 273.80677699 7.59851871 281.11081383 C7.59396601 284.8381753 7.59591602 288.56523627 7.61528015 292.29255676 C7.63608701 296.33243724 7.62595234 300.37164782 7.61274719 304.41156006 C7.62371644 305.59113085 7.63468568 306.77070163 7.64598733 307.98601699 C7.59638142 314.25027207 7.33550894 318.04990331 3.125 322.8125 C2.609375 323.410625 2.09375 324.00875 1.5625 324.625 C-1.87976891 327.46861345 -6.1342979 327.14311314 -10.3671875 327.09375 C-14.08229058 326.67710293 -16.12972199 325.28994601 -18.875 322.8125 C-21.43096783 319.21290443 -22.25048524 316.76951385 -22.25531673 312.37263584 C-22.26375668 311.28652816 -22.27219662 310.20042049 -22.28089231 309.08140045 C-22.27488227 307.89386341 -22.26887222 306.70632636 -22.26268005 305.48280334 C-22.26806559 304.21686338 -22.27345113 302.95092342 -22.27899987 301.64662164 C-22.29346978 298.13494039 -22.28920503 294.62349353 -22.28183305 291.11179936 C-22.27710748 287.32371187 -22.2896058 283.53567553 -22.29985046 279.74760437 C-22.31705576 272.32735215 -22.31787491 264.90718455 -22.31263756 257.48691756 C-22.30859788 251.45637723 -22.31003701 245.42586193 -22.31538582 239.3953228 C-22.31613424 238.5373706 -22.31688266 237.6794184 -22.31765376 236.79546766 C-22.31918522 235.05259971 -22.32072357 233.30973176 -22.32226873 231.56686383 C-22.336 215.21875145 -22.33055166 198.8706869 -22.31908352 182.52257561 C-22.30915021 167.56335707 -22.32209238 152.60426221 -22.34601771 137.64506275 C-22.3703945 122.28799408 -22.3800201 106.93098303 -22.37336498 91.57389539 C-22.36986963 82.95082372 -22.37212563 74.32784718 -22.38961601 65.70479012 C-22.40432025 58.36424062 -22.4049968 51.02385325 -22.3875451 43.683307 C-22.379018 39.93770952 -22.37692689 36.19240186 -22.39219666 32.44681931 C-22.40854118 28.38737009 -22.39393055 24.32862351 -22.37620544 20.2691803 C-22.39061295 18.49122081 -22.39061295 18.49122081 -22.40531152 16.67734295 C-22.34890162 10.41969881 -22.1277795 6.54521577 -17.875 1.8125 C-12.94593274 -2.536677 -5.87012005 -2.4246148 0 0 Z " fill="currentColor" transform="translate(438.875,93.1875)"/></svg>"""

    /** Shuffle button icon for the two modes (see PlayerController shuffle codes):
     *  play-in-order glyph when off, crossed arrows for shuffle songs. */
    fun shuffleIcon(mode: Int): String = when (mode) {
        1 -> // shuffle songs
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 3 21 3 21 8"></polyline><line x1="4" y1="20" x2="21" y2="3"></line><polyline points="21 16 21 21 16 21"></polyline><line x1="15" y1="15" x2="21" y2="21"></line><line x1="4" y1="4" x2="9" y2="9"></line></svg>"""
        else -> // off: play in order
            ICON_PLAY_IN_ORDER
    }

    fun shuffleTitle(mode: Int): String = when (mode) {
        1 -> "Shuffle songs"
        else -> "Play in order"
    }

    fun repeatTitle(mode: Int): String = when (mode) {
        1 -> "Repeat one song"
        3 -> "Play single song and stop"
        else -> "Repeat playlist"
    }

    /** Musicolet-style cycling buttons: each tap POSTs and the server answers with
     *  the button already in its next state, swapped in place via outerHTML. */
    fun shuffleButton(mode: Int): String =
        """<button id="np-shuffle" class="np-dot${if (mode != 0) " on" else ""}" title="${shuffleTitle(mode)}" hx-post="/api/player/shuffle" hx-swap="outerHTML">${shuffleIcon(mode)}</button>"""

    /** Repeat playlist is the base state, so only the one-song modes highlight. */
    fun repeatButton(mode: Int): String =
        """<button id="np-repeat" class="np-dot${if (mode == 1 || mode == 3) " on" else ""}" title="${repeatTitle(mode)}" hx-post="/api/player/repeat" hx-swap="outerHTML">${repeatIcon(mode)}</button>"""

    /** Repeat button icon for the three playback modes (see PlayerController repeat codes). */
    fun repeatIcon(mode: Int): String = when (mode) {
        1 -> // repeat one song
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path><text x="12" y="15" font-size="9" stroke-width="1" fill="currentColor" text-anchor="middle">1</text></svg>"""
        3 -> // play single song and stop
            ICON_PLAY_SINGLE_STOP
        else -> // repeat playlist
            """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9"></polyline><path d="M3 11V9a4 4 0 0 1 4-4h14"></path><polyline points="7 23 3 19 7 15"></polyline><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>"""
    }

    fun nowPlayingScreen(db: MusicDatabase, lyricsOpen: Boolean): String {
        val s = PlayerController.snapshot
        if (s.trackId < 0) {
            return """
            <div class="screen" id="np-root" data-track-id="-1" style="text-align:center;">
              <button class="backlink" hx-get="/screens/library" hx-target="#main-container" style="float:left;">← Library</button>
              <div style="clear:both;"></div>
              <div class="empty">
                <div style="font-size:40px; margin-bottom:12px;">♪</div>
                Nothing playing yet.<br>Pick a song from your library.
              </div>
            </div>"""
        }
        val track = db.track(s.trackId)
        val hasLyrics = track?.lrcUri != null
        val durSec = (s.durationMs / 1000).coerceAtLeast(1)
        val posSec = s.positionMs / 1000
        val pct = if (s.durationMs > 0) s.positionMs * 100 / s.durationMs else 0
        val artBg = artColor(s.trackId)
        return """
        <div class="screen" id="np-root" data-track-id="${s.trackId}">
          <button class="backlink" hx-get="/screens/library" hx-target="#main-container">← Library</button>
          <div style="display:flex; flex-direction:column; align-items:center;">
            <div class="np-art" style="background:repeating-linear-gradient(45deg, $artBg, $artBg 14px, var(--card-bg) 14px, var(--card-bg) 28px);">
              <img src="/api/art/${s.trackId}?v=${track?.lastModified ?: 0}" alt="album art">
            </div>
            <div id="np-title" style="font-size:22px; font-weight:700; letter-spacing:-0.02em; text-align:center;">${esc(s.title)}</div>
            <div id="np-artist" style="font-size:14px; color:var(--muted); margin-top:4px; margin-bottom:20px;">${esc(s.artist)}</div>

            <div style="width:100%; max-width:340px;">
              <input id="np-slider" class="seek" type="range" min="0" max="$durSec" value="$posSec"
                     style="background:linear-gradient(to right, var(--accent) $pct%, var(--track-empty) $pct%);"
                     oninput="sliderInput(this)" onchange="sliderDone(this)">
              <div style="display:flex; justify-content:space-between; font-size:12px; color:var(--muted); font-variant-numeric:tabular-nums; margin-top:4px;">
                <span id="np-cur">${fmtTime(s.positionMs)}</span><span id="np-tot">${fmtTime(s.durationMs)}</span>
              </div>
            </div>

            <div style="display:flex; align-items:center; gap:18px; margin-top:18px;">
              ${shuffleButton(s.shuffleMode)}
              <button class="np-side" hx-post="/api/player/prev" hx-swap="none">
                <svg width="18" height="14" viewBox="0 0 18 14"><rect x="0" y="0" width="3" height="14" fill="#3b3651"></rect><polygon points="18,0 6,7 18,14" fill="#3b3651"></polygon></svg>
              </button>
              <button id="np-play" class="np-main" hx-post="/api/player/toggle" hx-swap="none"></button>
              <button class="np-side" hx-post="/api/player/next" hx-swap="none">
                <svg width="18" height="14" viewBox="0 0 18 14"><polygon points="0,0 12,7 0,14" fill="#3b3651"></polygon><rect x="15" y="0" width="3" height="14" fill="#3b3651"></rect></svg>
              </button>
              ${repeatButton(s.repeatMode)}
            </div>

            <div style="display:flex; align-items:center; gap:8px; margin-top:22px; flex-wrap:wrap; justify-content:center;">
              <button id="np-speed" class="chip" hx-post="/api/player/speed" hx-swap="none">${speedLabel(s.speed)}x</button>
              <button id="np-sleep" class="chip${if (s.sleepRemainingMs >= 0 || s.sleepSongsRemaining > 0) " on" else ""}" hx-get="/partial/sleep-menu" hx-target="#sheet-root" hx-swap="innerHTML">sleep</button>
              <button id="np-lyrics" class="chip${if (lyricsOpen) " on" else ""}" onclick="toggleLyrics(this)">lyrics</button>
              <button id="np-fav" class="chip${if (track?.favorite == true) " on" else ""}" hx-post="/api/player/favourite" hx-swap="none">favourite</button>
              <button class="chip" onclick="openQueue()">queue</button>
            </div>
            <div id="lyrics-deck-wrap" style="width:100%;">
              <div id="lyrics-deck"${if (lyricsOpen) """ hx-get="/api/player/lyrics" hx-trigger="load" hx-swap="innerHTML"""" else ""}></div>
            </div>
          </div>
        </div>
        <script>
          function toggleLyrics(btn) {
            var deck = document.getElementById('lyrics-deck');
            if (deck.innerHTML.trim() === '') {
              htmx.ajax('GET', '/api/player/lyrics', { target: deck, swap: 'innerHTML' });
              btn.classList.add('on');
              deck.parentElement.style.display = '';
            } else {
              deck.innerHTML = '';
              btn.classList.remove('on');
            }
          }
        </script>"""
    }

    fun lyricsDeckHtml(lines: List<LyricLine>): String {
        if (lines.isEmpty()) {
            return """<div class="lyrics-deck"><div class="lyric" style="text-align:center;">No lyrics found for this song.<br><span style="font-size:11px;">Drop a matching .lrc file next to the track and rescan.</span></div></div>"""
        }
        val rows = lines.joinToString("") { """<div class="lyric" data-at="${it.atMs}">${esc(it.text)}</div>""" }
        return """<div class="lyrics-deck">$rows</div>"""
    }

    /**
     * Sleep timer bottom drawer, styled like the library options drawer for
     * visual uniformity. "After (N) songs" and "Custom timer" expand inline
     * panels (stepper / minutes input) driven by the poetSleep* JS in Shell.
     */
    fun sleepDrawer(): String = """
        <div class="sheet-shield" onclick="closeSheet()"></div>
        <div class="drawer">
          <div class="sheet-grab"></div>
          <div class="drawer-head">
            <div class="drawer-head-art" style="background:var(--accent-faint); font-size:18px;">☾</div>
            <div style="flex:1; min-width:0;">
              <div style="font-size:15px; font-weight:700;">Sleep timer</div>
              <div style="font-size:12px; color:var(--muted);">Music pauses when the timer ends</div>
            </div>
          </div>
          ${DrawerViews.drawerItem(DrawerViews.ICON_D_CLOCK, "In 30 min",
            """hx-post="/api/player/sleep?min=30" hx-swap="none" hx-on::after-request="closeSheet()"""")}
          ${DrawerViews.drawerItem(DrawerViews.ICON_D_CLOCK, "In 1 hr",
            """hx-post="/api/player/sleep?min=60" hx-swap="none" hx-on::after-request="closeSheet()"""")}
          ${DrawerViews.drawerItem(DrawerViews.ICON_D_QUEUE, "After (N) songs", """onclick="poetSleepPanel('songs')"""",
            sub = "Stop once the songs finish", chevron = true)}
          <div id="sleep-songs-panel" class="sleep-panel" hidden>
            <button class="sleep-step" onclick="poetSleepAdj(-1)">−</button>
            <div class="sleep-count"><span id="sleep-songs-n">3</span> songs</div>
            <button class="sleep-step" onclick="poetSleepAdj(1)">+</button>
            <button class="sleep-set" onclick="poetSleepSetSongs()">Set</button>
          </div>
          ${DrawerViews.drawerItem(DrawerViews.ICON_D_HOURGLASS, "Custom timer", """onclick="poetSleepPanel('custom')"""",
            sub = "Pick your own minutes", chevron = true)}
          <div id="sleep-custom-panel" class="sleep-panel" hidden>
            <input id="sleep-custom-min" type="number" inputmode="numeric" min="1" max="600" value="45">
            <div class="sleep-count" style="flex:0 0 auto;">min</div>
            <button class="sleep-set" onclick="poetSleepSetCustom()">Set</button>
          </div>
          ${DrawerViews.drawerItem(DrawerViews.ICON_D_TIMER_OFF, "Turn off timer",
            """hx-post="/api/player/sleep?min=0" hx-swap="none" hx-on::after-request="closeSheet()"""", danger = true)}
        </div>"""
}
