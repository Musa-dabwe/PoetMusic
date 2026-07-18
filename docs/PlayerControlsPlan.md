# Poet Music — Musicolet-Style Player Controls & Lyrics Scroll Fix

Plan derived from the "Implementing Musicolet Player Controls" design
conversation. Two work items:

1. **UI improvements** — turn the Now Playing shuffle/repeat buttons into
   Musicolet-style sequential state machines with *instant* visual feedback,
   including Musicolet's group (album) shuffle.
2. **Scrolling bug** — the lyrics auto-scroll drags the whole page along with
   it; scope the scroll to the lyrics deck only (the recommended solution).

---

## Part 1 — UI improvements: sequential state-machine controls

### Where the app stands today

| Control | Behaviour today | Gap vs. the design |
|---|---|---|
| Repeat (`#np-repeat`) | Already a 4-state cycle (off → repeat playlist → repeat one → play single & stop) handled by `PlayerController.cycleRepeat()` | Feedback is delayed: the button posts with `hx-swap="none"` and its icon/highlight only changes when the 1 s state poller next runs |
| Shuffle (`#np-shuffle`) | Binary toggle (`toggleShuffle()`): off ↔ shuffle songs | No group/album shuffle state; same delayed-feedback problem |

Both buttons fire `hx-post` with `hx-swap="none"` and rely on
`applyState()` (the 1 s poller in `Shell.kt`) to repaint them. A tap can
therefore take up to a full second to show any change — the opposite of the
micro-feedback the design calls for.

### Target design (from the conversation)

**Shuffle — three states, cycled per tap:**

| Mode | Name | Visuals | Behaviour |
|---|---|---|---|
| 0 | Off | dim icon (ordered-list glyph) | queue plays in natural order |
| 1 | Shuffle songs | accent highlight, crossed arrows | current song + static Fisher-Yates sequence of the rest; master order preserved so turning shuffle off restores it |
| 2 | Shuffle albums | accent highlight, crossed arrows + disc badge | Musicolet's group shuffle: finish the current song's album in order, then jump to a random album and play *it* in order, and so on |

**Repeat — keep the existing 4-state cycle** (it already exceeds the
3-state design in the conversation), but adopt the same instant-feedback
mechanism and per-state `title` labels.

**Interaction pattern (the HTMX + Ktor state machine from the
conversation):** every tap POSTs to the server; the server advances the
state *deterministically* (reads the current mode, computes the next one,
applies it) and responds with the button's next-state HTML fragment, which
HTMX swaps in place via `hx-swap="outerHTML"`. The 1 s poller keeps
repainting the same buttons from the live snapshot, so the two mechanisms
can never disagree for more than one poll.

### Changes by file

**`playback/PlayerController.kt`**
- Replace `shuffleActive: Boolean` with `shuffleMode: Int` and constants
  `SHUFFLE_OFF = 0`, `SHUFFLE_SONGS = 1`, `SHUFFLE_ALBUMS = 2`.
- `Snapshot.shuffle: Boolean` → `Snapshot.shuffleMode: Int`.
- Replace `toggleShuffle()` with `applyShuffleMode(mode)` which rewrites
  the static queue without interrupting the current song:
  - **OFF** — restore master order around the current song (existing logic).
  - **SONGS** — current song + shuffled rest (existing logic).
  - **ALBUMS** — current song + the rest of its album in master order, then
    the remaining albums in random order, each album's tracks in master
    order. Album identity is title + album artist (so two albums sharing a
    title stay separate), and an untagged track is its own group rather
    than part of one giant "" pseudo-album.
- Replace `cycleRepeat()` with `advanceRepeatMode()` /
  `advanceShuffleMode()`: one serialized main-thread read → compute next →
  apply → return the committed mode (same `CountDownLatch` pattern as
  `queueItems()`), so the server can render exactly the state that was
  committed.
- Persistence: `pb_shuffle` stores the mode int (legacy `"1"` restores as
  Shuffle songs unchanged).

**`server/PoetServer.kt`**
- `POST /api/player/shuffle` and `POST /api/player/repeat` become state
  machines: call the controller's atomic advance op and respond with the
  updated button HTML for the committed mode (instead of 204 No Content).
- `/api/player/state` JSON: `"shuffle"` field carries the mode int (0/1/2).

**`server/Views.kt`**
- `shuffleButton(mode)` / `repeatButton(mode)` helpers render the full
  `<button>` with `hx-swap="outerHTML"`, state-dependent `on` class, icon
  and `title`; used by both `nowPlayingScreen` and the two endpoints.
- `shuffleIcon(mode)` gains the album-shuffle glyph (crossed arrows + disc
  badge); icons stay `stroke="currentColor"` so the accent/dim colors keep
  coming from `.np-dot` / `.np-dot.on`.

**`server/Shell.kt` (poller JS)**
- `ICON_SHUFFLE[0..2]` + `SHUFFLE_TITLES` arrays mirror the server icons;
  `applyState()` indexes them with `s.shuffle` and highlights when ≠ 0.

### Race note

The advance ops are serialized on the main-thread handler: each one reads
the live mode, computes the next, applies it and returns what it committed,
so rapid taps queue up as distinct transitions instead of collapsing into
one. The poller still reconciles the button with the snapshot every second
as a belt-and-braces backstop.

---

## Part 2 — Scrolling bug: lyrics auto-scroll drags the whole page

### Symptom

With the lyrics deck open on Now Playing, the entire screen jerks/scrolls
by itself once per second while a track plays; manually scrolling the page
(or the deck) gets fought and undone by the next tick.

### Root cause

`applyState()` centres the active lyric with

```js
active.scrollIntoView({ block: 'center', behavior: 'smooth' });
```

`scrollIntoView` scrolls **every scrollable ancestor** — not just the
`.lyrics-deck` box (`max-height:38vh; overflow-y:auto`) but also the
document itself. Each 1 s poll re-issued it even when the active line had
not changed, so the page was continuously yanked toward the deck.

### Recommended solution (adopted)

Scroll *only the deck*, and only when the active line actually changes:

```js
var box = deck.querySelector('.lyrics-deck');
if (box && active && !poetSeeking && box._active !== active) {
  box._active = active;
  box.scrollTo({ top: active.offsetTop - (box.clientHeight - active.offsetHeight) / 2,
                 behavior: 'smooth' });
}
```

plus `position:relative` on `.lyrics-deck` so each `.lyric`'s `offsetTop`
is measured against the deck. `Element.scrollTo` moves that one scroll
container and nothing else, and the change guard stops the once-per-second
re-scroll, so the user's page position is never touched.

Rejected alternatives:
- `scrollIntoView({ block: 'nearest' })` — still scrolls ancestors when the
  line is outside the page viewport.
- `overflow-anchor` / `scroll-margin` tweaks — don't stop ancestor
  scrolling at all.

### Do not reintroduce by

Calling `scrollIntoView` (any options) on elements inside an
`overflow-y:auto` box that lives in a scrollable page — always scroll the
owning container via `scrollTo`/`scrollTop`.

### How to verify

1. Play a track with synced lyrics, open the lyrics deck, then scroll the
   page so the deck is half visible: the page must stay put; only the deck
   glides as lines advance.
2. Hand-scroll inside the deck between two line changes: the deck must not
   snap back until the *next* line becomes active.
3. Lyrics closed: no scrolling activity anywhere.

---

## Out of scope

- ExoPlayer's native `shuffleModeEnabled` stays off — the queue remains a
  static, literal sequence (Musicolet queue model, see `PlayerController`).
- The library "Shuffle all" button keeps its existing behaviour (it builds
  a Shuffle-songs queue via `setQueue(shuffled = true)`).
- No changes to the home-screen widget or media notification.
