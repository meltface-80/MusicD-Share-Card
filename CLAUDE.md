# Working on this repository

## The rule

**Do not ship a change you have not tested. If you cannot test it, say so in the
same breath as you hand it over.**

This is inherited from
[MusicD Remote Lite](https://github.com/meltface-80/Android-Random-Remote),
where three consecutive releases shipped bugs that one run would have caught.
The pattern each time was the same: **the code compiled, so it was shipped.**
Compiling is not evidence.

It has already paid for itself here. The DIDL parser read `dc:creator` and
`upnp:artist` into one field, so whichever element Spotify happened to write
first won — and the card was headed "by Frank Ocean, John Mayer" instead of
"by Frank Ocean". The code compiled. The comment above it described the correct
behaviour. Only the test found it.

## What "tested" means here, concretely

```bash
./gradlew :core:test          # see the workaround below
node --check app/src/main/assets/web/app.js
npx eslint -c tools/eslint.config.mjs app/src/main/assets/web/*.js
node tools/check-css.js
node tools/check-sharecard.js
python3 tools/check-icons.py
```

**A new test must fail before the fix and pass after it.** Prove it: break the
fix, run the test, show it failing, restore. A test that passes both ways is
decoration.

### The local Gradle workaround

The proxy blocks the Android Gradle Plugin, so `:app` cannot be built here — only
in CI. To run `:core:test` locally, temporarily strip the Android plugins:

```bash
# save build.gradle.kts and settings.gradle.kts first
sed -i '/id("com.android/d;/id("org.jetbrains.kotlin.android")/d' build.gradle.kts
sed -i 's/include(":app")//' settings.gradle.kts
./gradlew :core:test
# then RESTORE BOTH FILES — never commit the stripped versions
```

## The honesty rule about Android code

CI compiles `:app` and runs `:app:testDebugUnitTest`. Everything in
`app/src/main/java/` has nothing but the compiler behind it: there are no
instrumentation tests and no device here.

So for anything in that directory, state plainly what was verified and what was
not. "Compiles in CI and the core tests pass" is an honest claim. "Fixed" is not,
unless somebody has run it on a device.

**Push logic down into `:core` wherever it can go** — that is the only place with
tests. `SeedHosts` lives there rather than in the Android module for exactly this
reason: the parsing is the part that can be wrong, and a bad address fails later
as "no Sonos players found", which is indistinguishable from a network problem.

## The app is not a Sonos app

`Source` is the interface and Sonos is one implementation of it. That shape was
arrived at the hard way: Sonos was wired in first and its assumptions ended up
pressed into every layer, until Roon proved the cost by handing a speaker its
own session id where the title should be. Read through the speaker the record
was simply not there, however carefully the DIDL was parsed.

- **Ask whoever actually knows.** `RoonSource` asks Roon, which has the album,
  the artist and a real cover because it is the thing playing them.
  `SonosSource` is right for what the speakers stream themselves — Spotify
  Connect, Apple Music via the Sonos app, radio. `UpnpSource` is the same
  conversation with any DLNA renderer.
- **The BEST answer wins, not the first — see `Sources.quality`.** Source order
  alone was never enough: when Roon plays to a Sonos speaker both sources see
  that room and both say "playing", and the chosen zone was asked before any
  ordering applied. With the Sonos zone picked, that shipped a card headed with
  a session id while Roon sat there knowing the album. Answers are ranked
  album+artist > artist > album > title > nothing, playing beats paused, and the
  source order is only the tie-break. Ranking the ANSWER also means this does
  not depend on `looksLikeStreamId` recognising every shape of rubbish.
- **An answer describing nothing is never drawn.** A blank card looks like the
  app working; "nothing is playing" is the truth.
- **`buildActions` must not clear `hintEl` unconditionally.** It did, which wiped
  a source's notice a moment after it was set — so on Android a Roon Core
  waiting to be approved said nothing at all, and Roon looked simply ignored.
- **`Source.notice()` covers EVERY stage that is not paired**, not just
  AWAITING_APPROVAL. A Core that was never found or that refused registration
  used to say nothing, which is the same dead end as before.
- **Zone ids are prefixed with their source** (`roon:…`, `sonos:…`) so two
  sources cannot collide on one room, and the picker says which is which.
- **Roon's first run needs a human, and NOTHING may put a deadline on that
  wait.** `register` went through `MooSocket.call`, which gives up after ninety
  seconds. Roon does not answer `register` at all until somebody presses Enable
  in Settings → Extensions, so the first pair did not sometimes fail — it always
  failed, with "Roon did not answer com.roonlabs.registry:1/register in time" and
  a closed socket. The only way out of that is Refresh, and Refresh introduced
  the extension again: six identically named copies in Roon's list, one per
  press, and no way to tell which to enable. Registration is now SENT, not
  awaited, and `rediscover()` refuses to tear down a socket that is already
  open. `Source.notice()` is what stops the wait itself looking like a broken
  app.
- **Three things write to disk, and nothing on the network reaches any of
  them**: `TokenStore` (Roon's pairing token), `WebhookStore` (the Discord
  URLs), and `CacheStore` (what the metadata lookups found). No route touches
  any of them; the cache is written by the lookup path on its own thread, after
  the request that triggered it has been answered. The rule that every route is
  a read stands unchanged, and a NEW route that writes still goes behind
  `Access.mayConfigure` in the same change.
- **A cache may never break a lookup.** A full disk, a file written by a version
  that shaped it differently, a permission that changed underneath — every one
  of those has to end as "we did not remember that one", never as a failed card.
  Every disk step in `TtlCache` goes through `quietly {}` and catches Throwable,
  for the same reason the request path does.
- **Persistence is OPT-IN and the default path is untouched.** A `TtlCache` with
  no `Persist` behaves exactly as it did before any of this existed, and
  `TtlCacheTest` asserts that first. The art bytes and Pitchfork's hourly index
  deliberately pass none: one is large and cheap to refetch, the other is a
  recent-reviews window that must stay fresh.
- **The shelf is read LAZILY, never in a constructor.** These caches are built
  while `startForeground()`'s five seconds are running — see `CardService` —
  and a file read in there is exactly what killed the app before.
- **The Roon client is a port of MusicD Remote Lite's**, trimmed to the shortest
  path to `now_playing`. That app is a remote — it browses, queues, seeks and
  sets volume; this one makes a picture, so the browse tree, queue, transport
  verbs and settings panel are all left out, and `required_services` asks for
  TRANSPORT only.
- **`now_playing.three_line` is line1=track, line2=artist, line3=ALBUM.** Read in
  the wrong order it makes a card headed with a track name, which looks almost
  right.
- **A UPnP renderer's control URL is not at a fixed path.** Sonos publishes its
  at constants; everyone else names theirs in a device description whose own
  address comes from the SSDP `LOCATION` header. Guessing ports instead of
  keeping LOCATION was the first version of `UpnpSource` and it would have found
  almost nothing.
- **`/api/health` must not touch the network.** It reported the zone count once,
  which meant a liveness check ran a multicast sweep and a description fetch per
  renderer.

## Things about this codebase that are easy to get wrong

- **The art proxy allows two things and nothing else: a KNOWN player, or a
  confidently public https host.** Not "anything that looks private" — that
  refused the absolute CDN link Spotify Connect reports (a blank sleeve on a
  card the Sonos app rendered fine) while permitting `127.0.0.1` and
  `169.254.169.254`. And "public" must be proved, not assumed from "not
  recognised as private": `0177.0.0.1` is octal for loopback, and refusing to
  classify it made it pass. Every numeric host must be a clean unambiguous
  public quad; only a name gets the benefit of the doubt.
- **A source can report an opaque id where a title should be.** Roon streaming
  to Sonos sends "Roon" + 32 hex characters as `dc:title`. A card headed with a
  hash looks like the app working, which is worse than one that admits it knows
  nothing — see `Didl.looksLikeStreamId`, kept deliberately narrow because the
  cost of a false positive is discarding a real album.
- **The cover MUST be proxied.** A canvas that has drawn a cross-origin image
  cannot be read back: `toBlob` throws and there is no card. Sonos sends no CORS
  header, so pointing the page at a player directly can never work, however much
  simpler it looks. See `ArtProxy`.
- **Reads are open; the few writes are gated, and the gate is in [Access].**
  This server answered the whole LAN without a password because it held no
  secrets. A Discord webhook URL is a credential — whoever has it can post to
  that channel from anywhere, forever — so the gate this rule always demanded
  arrived with it. Loopback is trusted without a PIN (standing at the device
  beats any PIN typed across the house); anything else needs the PIN, which is
  served ONLY to loopback. Adding or removing a webhook is gated; POSTING a card
  to an existing one is not, because that is the everyday action and the worst
  it offers a stranger on your wifi is a picture of your own album in your own
  channel. Any NEW route that writes goes behind `Access.mayConfigure` in the
  same change.
- **A WebView opens no file picker without a `WebChromeClient`.** `<input
  type="file">` is silently inert without one — no picker, no error, no log
  line — which is what the avatar photo button did. `onShowFileChooser` must
  launch `params.createIntent()` and `onActivityResult` must hand the result
  back with `parseResult`. A callback that is never answered leaves that input
  dead for the life of the page, so cancelling, failing and being destroyed all
  have to answer it with null.
  `FilePickerContractTest` scans for this, because it cannot be reproduced on a
  JVM and the failure mode is silence.
- **The file input is hidden by clip/opacity rather than `display: none`, but
  that was NOT the bug.** It was shipped as a second suspected cause and the
  field disproved it: the same page, with the same `display: none`, worked
  perfectly in Safari on iOS and did nothing only in the Android WebView. The
  missing `WebChromeClient` was the whole of it. The clip/opacity pattern stays
  because it also keeps the control keyboard-reachable, but do not go looking
  for a browser this fixed — there wasn't one.
- **An avatar is UPLOADED to Discord, not linked.** `avatar_url` is fetched by
  Discord's own servers, so a picture this app serves from a home network is
  invisible to it and falls back silently to the default — and a photo on a
  phone has no URL at all. `DiscordPoster.setAvatar` PATCHes the webhook with a
  base64 data URI instead and Discord keeps it on its CDN. The page scales the
  photo to 128px on a canvas first, because :core has no image decoder and a
  camera photo is thousands of pixels wide.
- **The webhook panel must fit a phone screen without scrolling.** The first
  version put Save below the fold behind three paragraphs of prose, and a
  settings screen you have to scroll to finish is one people abandon half done.
  Short fields pair across the width; every explanation is one line.
- **THE ICON IS `tools/icon/source.svg`, AND NOTHING IS DRAWN IN CODE ANY
  MORE.** Every icon in the repo — five Android densities, two layers each, four
  web PNGs and two for the project page — comes from that one file. It was drawn
  once: three grey dashes (unreadable at launcher size), then a single quaver
  whose flag is a hairline curl and thinned to nothing, reported as "a funny
  looking music note", then a beamed pair with tilted heads held in step by hand
  between a Python script and an Android vector. A supplied 3D render replaced
  all of that, and the SVG replaced the render. Change the icon by replacing the
  SVG; never by editing an output, because the next run silently puts it back.
- **THE ICON PIPELINE IS TWO STAGES AND BOTH MUST BE RUN.**
  `make-icons.py --render` needs a browser and rewrites the two committed
  masters (`tools/icon/artwork.png`, `tools/icon/backdrop.png`); plain
  `make-icons.py` turns those into the sixteen icons with nothing but Pillow.
  The split exists so CI can check the second half exactly: a different Chromium
  antialiases differently, so a check that re-rendered the SVG would fail for a
  reason that has nothing to do with the icon. Stopping after `--render` leaves
  sixteen icons drawn from the old picture, and nothing in this repo reads them
  — only a launcher and a Home Screen do, and neither is here.
- **THE ANDROID ICON IS TWO BITMAPS, AND IT CANNOT GO BACK TO A VECTOR.** The
  SVG has gradients, a drop shadow and a glow, and an Android vector holds none
  of those. The FOREGROUND is the artwork with real transparency around it — the
  thing the SVG buys over a flat render, because there is no rectangle to hide
  and so no seam to hide it with — and the BACKGROUND is the SVG's own backdrop,
  squared off and carried to every edge.
- **THE ANDROID FOREGROUND IS SIZED BY A RADIUS, NOT BY A BOUNDING BOX.** A
  launcher mask is as often a circle as a square, and a box around arcs in one
  corner and a sleeve in the other is far larger than the artwork really is —
  sizing to it shrinks the icon to fit corners that are empty. `SAFE_RADIUS`
  puts the artwork's outermost solid pixel at 34dp of the 108dp canvas, inside
  the middle 66dp that Android guarantees, and the radius is MEASURED off the
  artwork's alpha at generation time so it stays right when the SVG changes.
- **THE SVG'S ROUNDED TILE IS DELIBERATELY THROWN AWAY.** `source.svg` draws its
  artwork on a rounded tile inset from the edge — an icon as a picture of an
  icon. Every platform here masks its own shape out of a full-bleed square, so
  the output is the backdrop carried to all four edges with the artwork over it.
  Keeping the tile's rounding would show as a dark ring cut just short of the
  real mask, and the glass edge with it.
- **iOS needs a real PNG icon, and `tools/make-icons.py` writes it.**
  `apple-touch-icon` will not take an SVG or an adaptive icon, and without one
  the Home Screen shows a screenshot or a bare letter. Run both stages after
  touching the source, and commit everything that moves, or the two platforms
  quietly stop showing one icon.
- **Do not retype an SVG path; copy it.** The settings cog was a hand-shortened
  Feather icon — `1.6` where its arcs need `1.65`, `.1` where they need `.06` —
  and those are large-arc sweeps, so rounding them turned the teeth into loops.
  It drew a flower on every card for six releases and no test noticed, because
  nothing here renders an icon.
- **The card is the whole message.** The post used to carry "**Album** by
  Artist" in Discord's `content`, so a line of text sat above the picture saying
  exactly what the picture says, in worse type, on top of the thing it
  describes. There is no caption any more and no route parameter for one.
  `allowed_mentions` stays regardless: nothing here may notify a server, and a
  field added later must not be what discovers that guard had gone.
- **A webhook can choose its display name and avatar; it CANNOT drop the APP
  tag.** Discord marks every webhook message that way on purpose, so a reader
  can tell a person from an integration, and no field turns it off. Posting as
  the account itself would mean driving a user token — self-botting, against
  Discord's terms, not something to build. `username` and `avatar_url` are as
  close as this goes, and the settings panel says so before anything is sent
  rather than leaving it to be discovered in a channel.
- **Pinch-zoom is off, but NOT via `touchstart`.** The viewport meta covers
  Android and desktop; iOS has ignored `user-scalable` since iOS 10, so the
  `gesturestart`/`gesturechange`/`gestureend` events are refused instead —
  they fire only for a multi-finger pinch. A `touchstart` that calls
  `preventDefault` would kill the long press, which is how iOS copies the card,
  and it would fail silently.
- **A webhook URL never leaves the process.** No route returns one — listings
  carry `Webhook.masked`, the id is a hash rather than the token, and the server
  does the posting so the page never needs it. `CardApiTest` asserts no route
  leaks it; keep that true.
- **A read route must still refuse a POST.** Widening the top-level method gate
  for the webhook routes quietly made `POST /api/now-playing` answer 200. Write
  routes are named in `fixedRoute`; everything else is GET-only.
- **Android cannot put an image on the clipboard.** It arrives as a content://
  URI the pasting app has no grant to read, so the write resolves and nothing
  is pasted. Declining to shim it was NOT enough — the WebView has
  `ClipboardItem` and `clipboard.write` natively, so the page's detect passed
  and drew a button that did nothing. `ShareBridge` now deletes both. Share does
  the same job and works; iOS long-press and desktop Copy are untouched.
- **Never poll.** The app asks a speaker what is playing when somebody opens the
  page or presses Refresh. This runs on a device that is never switched off; a
  timer anywhere means interrogating the household all day to answer a question
  nobody is reading.
- **AND COMING BACK TO THE APP IS NOT A REQUEST EITHER.** The page reloaded on
  `visibilitychange`, which looked like the same rule — ask at the moment
  somebody wants to know — and is not: going to the Home Screen and returning
  fires it, so the card you were looking at was thrown away and replaced by a
  spinner every single time, and one left up deliberately to show somebody
  could not survive a glance at anything else. Reported as "each time I return
  to the Home Screen and open the app it has to refresh itself". A stale card
  is the right trade: it says what it is a picture of, and Refresh is one tap
  away. `PageRefreshTest` scans for that listener and for the shapes the same
  mistake takes next — `pageshow`, `focus`, an `onResume` in the shell — because
  no test here can open a browser and the failure mode is a card that quietly
  went away, which reads as the app working.
- **THE PAGE IS ONE SCREEN, AND EVERY ROW BELOW THE CARD IS A FIXED GRID NOW.**
  The action buttons wrapped, which on a phone meant Share on its own line,
  then the webhook, then the cog — three rows of buttons pushing the links and
  the suggestions off the bottom. They are one flex row of equal cells, and
  `min-width: 0` is the half that actually stops the overhang: a flex item will
  not shrink below its content without it. The LABEL WRAPS INSIDE THE BUTTON
  and the icon sits above it, because one of those buttons is named by the user
  — it is their webhook — so "Discord Now Playing" cannot be shortened and must
  not clip. The old `@media (max-width: 420px)` rule that gave each button
  `flex: 1 1 100%` is gone; it was what put them one per row on every phone
  this runs on.
- **THE PAGE DOES NOT SCROLL, AND THE CARD IS WHAT GIVES WAY.** `body` is a
  fixed `100dvh` with `overflow: hidden` and `overscroll-behavior: none` —
  the second stops iOS rubber-banding a page that has nowhere to go. That is
  only safe because `.stage` is `flex: 0 1 auto` with `min-height: 90px`: the
  card is the one thing here that can be smaller without anything being lost,
  so on a short screen it shrinks (letterboxed by `object-fit: contain`) rather
  than the links and suggestions being cut off. Verified by rendering at
  360x620, where the card comes down to 96px and every row is still there.
- **`100dvh`, NEVER `100vh`, ON iOS.** `100vh` is the height with the browser
  bars retracted — taller than what can actually be seen — so a page cut to it
  hides its own last row behind the toolbar. `dvh` is the live value.
- **THE DIAGNOSTICS ARE THE ONE THING ALLOWED TO SCROLL.** They are a wall of
  facts meant to be read off the screen of a device in another room and typed
  out, so clipping them is worse than the scrolling they replace. `show()`
  decides it from what actually went into the stage — `stage.classList.toggle
  ("scrolls", …)` — rather than trusting a caller to remember.
- **THE SUGGESTION TYPE IS MEASURED, NOT CHOSEN.** The three chips span the
  full width, and at any one fixed size the longest clips while the shortest
  floats in nothing. `fitSuggestions()` measures the longest label ON A CANVAS
  — one call, no DOM write-read-write — and sets the size on the ROW so all
  three inherit it. Sizing each chip to its own text would make "Moby · Disco
  Lies" enormous beside "The Chemical Brothers · Live in Leicester 1995". It is
  re-run on resize and again on `document.fonts.ready`, because a width
  measured in the fallback face is the wrong width.
- **THE ACTION ROW IS UNIFORM ON BOTH PLATFORMS, and the filled Share button
  was the last thing making them differ.** iOS has no Share button at all —
  its share sheet is reached by holding the card — so it draws Download, the
  webhook and the cog as three identical plain buttons, while Android drew a
  filled yellow Share beside two plain ones. Same page, two apps, depending on
  the phone. `.actions .primary` still exists and "Find my speakers" still uses
  it: that one appears only when discovery has failed, it is the single thing
  worth doing at that moment, and it is alone in the row.
- **The two platforms differed because of a 420px BREAKPOINT, not a platform
  check.** There is no Android branch in this stylesheet and never was. An
  `@media (max-width: 420px)` rule stacked the buttons full width, and the
  reporter's iPhone was 430pt while their Android was narrower — so one wrapped
  into a neat row and the other became a column. When two devices disagree
  about a layout here, look for a breakpoint they straddle before looking for a
  platform.
- **`overflow-wrap: anywhere` BREAKS WORDS MID-WORD. Use `break-word`.**
  `anywhere` lets the browser count a break between any two letters when it
  computes how narrow a cell may be, so it takes them at the first opportunity:
  "Bandcam / p" and "Qobu / z" in a four-column grid on a 360px phone.
  `break-word` breaks only when a word genuinely will not fit. Both were tried
  here and the difference was only visible in a render.
- **THE LINKS ARE FOUR EVEN COLUMNS, and nine chips is three rows.** Eight —
  Wikipedia plus the seven services — is two rows of four, which is what was
  asked for. A Pitchfork review makes nine and the ninth starts a third row
  with an orphan. That is the honest outcome of a fixed grid; five columns is
  too narrow for the names on a phone, and the alternative was shortening
  services' own names or an ellipsis that hides the word telling two of them
  apart.
- **THE PREFERRED SERVICE IS CHOSEN BY HOLDING ITS CHIP, and it is NOT a
  setting.** The suggestion chips have to link somewhere and that was Qobuz for
  everybody because Qobuz is first in the list. A settings screen for a one-tap
  preference would be worse than the default it replaced — and this page's one
  settings screen is a credential form. So the choice is made on the thing
  being chosen: hold a chip, it takes a tick, and the suggestions follow it.
  `-webkit-touch-callout: none` is set on `a[data-service]` ONLY, because held,
  an iOS link raises a preview sheet instead — and the card image must keep its
  callout, since that menu is the one way an iPhone copies the picture.
- **THAT PREFERENCE LIVES IN `localStorage`, AND THAT IS TWO DECISIONS.** It is
  per-DEVICE, so the phone and the iPad across the house may reasonably differ.
  And it keeps every route a read: only three things in this app touch disk and
  a fourth would have to go behind `Access.mayConfigure`, which a display
  preference does not earn. Storage can throw outright in a private window, so
  every touch of it is guarded and the default stands. The page names the
  service on `/api/similar`; the URL is still built by `StreamingLinks` on the
  server, where the storefront and encoding rules already have tests.
- **A DIAGNOSTIC THE PAGE DOES NOT DRAW IS WORSE THAN NONE.** The
  similar-artist lookup gained `attempts()` and a `"similar"` key in the
  report, and the page was never taught to draw it — correct, served, and
  invisible to the one person who needed it, whose next report would say
  "there is nothing under Similar artists" meaning "there is no such heading".
  The device is normally in another room with no adb attached, so `/api/debug`
  read off its screen IS the bug report. `DiagnosticsDrawnTest` scans for every
  top-level key `Diagnostics` can put and refuses one the page never reads.
- **THE SOURCE-SCANNING TESTS READ FILES GRADLE DOES NOT TRACK, and that made
  them lie.** `FilePickerContractTest`, `ParserHardeningTest`, `JsonSafeTest`,
  `PageRefreshTest` and `DiagnosticsDrawnTest` all read from `app/` at runtime.
  Gradle knew nothing about it, so editing `app.js` or `MainActivity.kt` and
  running `:core:test` left the task UP-TO-DATE: green, having checked nothing.
  Found by breaking one on purpose and watching it pass. `core/build.gradle.kts`
  now declares those trees as test inputs — keep that list in step with the
  scans, and when proving a scan fails, be sure the task actually re-ran.
- **`Normalize.namesOverlap` ANCHORS AT THE FRONT, and that was a fix.** It
  matched the shorter name anywhere inside the longer, so "The Who" overlapped
  "The Guess Who" — the exact pair its own comment had named as the case it
  rejected, for as long as the function existed. The leading article is
  stripped for matching, which leaves "who" at the END of "guess who", and a
  run-of-words search found it: a stranger's biography on somebody's card,
  which is the one thing the guard exists to stop. Only a test written for a
  new caller found it. It keeps every case it is for (a name qualified on the
  RIGHT — "Jay Z feat. Alicia Keys", "Spiderland (Slint album)") and costs a
  name qualified on the LEFT: "Eno" no longer matches "Brian Eno". That is the
  right way to be wrong. What it CANNOT do is tell "Eagles" from "Eagles of
  Death Metal" — identical in shape to the Jay Z case — and `NamesOverlapTest`
  asserts that limit rather than pretending otherwise.
- **It lives in `Normalize`, beside `text`, for the same reason.** It was
  private to `Metadata` while Wikipedia was the only caller; `Similar` asks it
  of Deezer's top hit. Two copies that drift is how one lookup refuses a
  stranger and the next accepts them.
- **SUGGESTIONS ARE ARTISTS, NOT ALBUMS, AND THE LABEL SAYS SO.** Nothing
  keyless does album-to-album similarity — every route without a developer
  account answers "artists like this artist". So `Similar` finds acts and then
  ONE record by each, and the row is headed "If you like this" rather than
  promising a recommendation engine. Same honesty as "Find it on".
- **`Similar` asks ListenBrainz first and Deezer second, and the ORDER is what
  makes the first one's fragility affordable.** ListenBrainz is keyed on the
  MusicBrainz artist id — which the metadata lookup already gets for free out
  of the release search it was making anyway — so it costs no search of its
  own and answers in MBIDs, keeping the album lookup inside one vocabulary.
  But its similarity endpoint is named after the dataset behind it and those
  names change; **the algorithm string in `Similar` is UNVERIFIED**, because
  the network here refuses both hosts. Any answer that is not a usable list
  falls through to Deezer, and `attempts()` records which one answered — so
  the first real run says which, instead of an empty row saying nothing.
- **A SUGGESTION IS AN ALBUM. Deezer's `/artist/{id}/albums` IS NOT.** That
  endpoint is named for albums and returns every release filed under the act —
  singles and EPs included — and taking the earliest gave a house act its first
  twelve-inch. Reported from the field as "some are just tracks": Gat Decor's
  "Passion", Hyper Go Go's "High". `record_type` is the field that separates
  them and the filter is a WHITELIST — album, and nothing else — so a value
  nobody has seen yet is excluded by default rather than suggested by default.
  An act with no album keeps its name and loses the record; naming a single
  would be the wrong answer where the bare act is an honest one.
- **A FILTER THE SERVER APPLIES IS NOT EVIDENCE THE SERVER APPLIED IT.** The
  MusicBrainz browse asks for `type=album` and now also checks `primary-type`
  on every group that comes back. It was only asking, which is the same trust
  Deezer's endpoint name was given — and that one was misplaced.
- **No MusicBrainz id means ListenBrainz is not asked at all.** Resolving one
  by searching a name is how a row of suggestions ends up being about a
  different act that shares it. Skip and say so in the diagnostics.
- **The suggestion row is NEVER on the card and its links are built on the
  SERVER.** The card is the whole message and what it says is what is playing.
  And the chip URLs go through `StreamingLinks`, because Qobuz's search 404s
  without a storefront segment and the query rides in the path so a space must
  be `%20` — three rules that already have tests and that a copy in `app.js`
  would drift from.
- **The article behind the blurb is a LINK, not just a credit.** The words on
  the card come from Wikipedia and the card says so in type too small to be
  followable, which left the one source the blurb actually came from as the
  only thing on the page you could not open. `Metadata.Bio.url` had carried it
  since the port with nothing ever offering it; `/api/extras` returns it as
  `bioUrl` and the chip is labelled from `bioSource`, so a second source added
  later names its own.
- **Zone ids move.** A regroup in the Sonos app changes which player coordinates
  a room, and a card headed with the wrong room is the result. Resolve through
  `Household.group()`, which follows a member to its coordinator, never from a
  remembered uid directly.
- **Grouped rooms have no transport of their own.** Ask the coordinator. Asking a
  member answers with the coordinator's stream second-hand or not at all.
- **The UPnP-to-Sonos bridge is the reference for anything on the wire.**
  `meltface-80/UPnP-to-Sonos-UPnP-bridge` is working Python against real Sonos
  hardware. Where this app's SOAP differed from it, this app was wrong: faults
  must be parsed even on an HTTP 500, the response wrapper must be matched
  leniently (its own comment: "some devices answer with an unexpected wrapper
  name"), and its ten-second timeout is not generous. Check it before guessing.
- **`optString` is unsafe.** Android's `org.json` returns the literal text
  `"null"` where the desktop one returns `""`. Use `str()` / `strOrNull()`. The
  JVM tests cannot catch this, so `JsonSafeTest` scans the source instead.
- **A `Regex` in a companion object IS A STATIC INITIALISER, and Android's
  engine is stricter than this JVM's.** `java.util.regex` here treats a dangling
  `}` or `]` as a literal; Android's is ICU-backed and refuses it. One unescaped
  `}` in Pitchfork's rating pattern shipped as 0.19.0, and the app would not
  open at all — "The card server could not start. ExceptionInInitializerError:
  null" — because the class never loaded. Same shape as `setXIncludeAware`, new
  place. Every literal `{`, `}` and `]` outside a character class must be
  escaped; `RegexPortabilityTest` scans the source for it, because no test that
  runs on this JVM can catch it. It found two more the moment it was written.
- **Report the CAUSE, not the wrapper.** "ExceptionInInitializerError: null"
  names no class, no line and no reason — everything is in the cause, and the
  startup error printed only the top of the chain. `describe()` walks it and
  names the first frame in this app's own code, which is the class whose
  initialiser threw. Use it anywhere a Throwable is turned into text somebody
  will read.
- **Hardening the XML parser is BEST-EFFORT, and every setting must go through
  `quietly {}`.** `setXIncludeAware` is not implemented by Android and the base
  class throws `UnsupportedOperationException`. In a static initialiser that
  does not fail one parse — the `Xml` CLASS never loads, every later use throws
  `NoClassDefFoundError`, and the app dies. It shipped three times, each release
  diagnosing a different symptom of the same line. No JVM test can reproduce it,
  so `ParserHardeningTest` scans the source instead.
- **Catch `Throwable`, not `Exception`, anywhere a failure must not end the
  process** — the request path, the topology fetch, the per-zone read. A class
  that fails to initialise throws an `Error`, which sails through an `Exception`
  catch and kills the thread. The same Error caught by a `runCatching` one
  release earlier was silently reported as "would not describe the household",
  which is why it took three goes to find. Neither is right: a failed request is
  a 500 and the app stays up.
- **The XML parser is locked down but NOT namespace-aware, and both halves are
  deliberate.** The XXE features guard documents that arrive over the network
  from a device on the LAN — do not relax those. But `isNamespaceAware` must
  stay **false**: a namespace-aware parser rejects the WHOLE document over one
  unbound prefix, and that made a real household unreachable — three players
  answering on port 1400, every one of them "would not describe the household".
  Nothing here reads a namespace URI; every lookup goes through `Xml.localName`,
  which strips the prefix off the tag name.
- **THE SCORE IS IN THE INDEX, NOT ON THE REVIEW PAGE.** A review page served
  to something that is not a browser carries no rating at all — the diagnostics
  said "page read, NO SCORE IN IT" for a review a human reads an 8.0 off. Two
  releases went into parsing that page better. The LISTING at `/reviews/albums/`
  ships its reviews in a `window.__PRELOADED_STATE__` blob with the score, the
  Best New Music flag, the artist and the URL all in it, and MusicD Remote Lite
  has read it that way all along — which is why typing an album into ITS search
  box finds the score. One fetch, cached, shared by every album. Ask the index
  first; the review page is the fallback for anything too old to be listed.
- **A review is recognised by its SHAPE in that blob** — `contentType` plus
  `ratingValue` plus `url` — walked from the root rather than followed down a
  fixed path, so Pitchfork reshuffling its containers empties nothing. And the
  blob is found by MATCHING BRACES, not by a regex: it is a couple of megabytes
  and a brace inside a review's own text ends a naive scan early.
- **Pitchfork's `ratingValue` is an OBJECT, not a number.**
  `"ratingValue": { "score": "8.5", "isBestNewMusic": true, … }` — a pattern
  looking for `"ratingValue": 8.5` cannot match it, because after the colon
  comes a brace. The right review page was being fetched and thrown away for
  two releases because of it. MusicD Remote Lite reads exactly this object out
  of its listing page and always has; only the review-page reader here was left
  on the older scalar. Both shapes are accepted, the object first.
- **Best New Music comes from the flag beside the score, not from the page
  text.** Every Pitchfork page carries "Best New Music" in its own navigation,
  so scanning the HTML for those words marks every record ever reviewed. The
  text scan is the last resort, for a page with no structured flag at all.
- **A diagnostic that cannot tell two failures apart is worse than none.** The
  Pitchfork note said "not this artist" for both a wrong record AND a page with
  no score in it, so the one real dump this app produced pointed at the wrong
  half and two rounds of fixes went after a cause that was never there.
  `Pitchfork.Outcome` now names which it was. When adding a diagnostic, check
  what ELSE reaches that line.
- **Nothing finds an old Pitchfork review whose URL is numeric.**
  `/reviews/albums/5450-rumours/` cannot be constructed from a name and is not
  in the 30-item feed, so "Rumours" comes back empty and always will. Pitchfork
  publishes no search API; MusicD Remote Lite's `search` only filters listings
  it has already fetched. Not a bug to go hunting for.
- **A Pitchfork lookup has THREE tries, and the constructed URL is only the
  first.** `/reviews/albums/<artist>-<album>/` is one request and the only way
  an album from 1994 is found at all, but it fails whenever the speaker's
  spelling is not Pitchfork's — and a just-released album that was sitting in
  Pitchfork's own feed came back with nothing. So: the constructed URL, then the
  same with a trailing `(Deluxe Edition)` stripped, then the RSS feed matched on
  title. Each step only runs when the last found nothing, so an ordinary hit
  still costs exactly one request. The feed and not the listing page: the
  listing's reviews live in a `__PRELOADED_STATE__` blob, and RSS is a contract
  where that is an implementation detail.
- **`Normalize.text` is the ONE folding rule and slugs must go through it.**
  `Pitchfork.slugify` folded by hand and dropped anything outside `[a-z0-9]`, so
  "Björk" became "bj-rk" and no album by an artist with an accent ever resolved
  — invisible, because a missing score looks exactly like a record nobody
  reviewed. NFKD is not enough on its own either: a ligature or a stroked letter
  has no decomposition, so `Normalize` expands æ, œ, ø, ß, þ and friends before
  folding, or "Ænima" becomes "nima". There must never be a second copy of this
  rule — one was briefly added in `meta/` and deleted in the same round.
- **A missing score is silent, so `/api/debug` now lists what was asked.**
  "Pitchfork never reviewed it" and "the URL this app built was not the one
  Pitchfork used" look identical from the card. `Pitchfork.attempts()` keeps the
  last dozen lookups with their outcome, and the page shows them under "Album
  reviews". That distinction took a bug report to notice; it should not take a
  second one.
- **The same goes for a link that opens the wrong screen.** "The Qobuz button
  opens the app but not the album" has three causes that look identical from the
  outside: the app-scheme intent was never built, nothing answered it, or Qobuz
  answered and landed on Home anyway. `LinkLog` records which door each outgoing
  link went through and `/api/debug` shows it. iOS reaching the album while
  Android did not is what narrowed it — that asymmetry proved the album id was
  right and only the Android hand-off was wrong.
- **The Qobuz intent names the app.** `intent://…;package=com.qobuz.music` is
  the shape open.qobuz.com's own page emits, and `setPackage` is the difference
  between "whoever claims this scheme" and "that app". The bare scheme is still
  tried after it, and the https link after that.
- **QOBUZ NEEDS AN ALBUM ID; A SEARCH LINK CAN NEVER OPEN THAT APP.** Shipping
  `StreamingLinks` alone gave Qobuz the same pre-filled search as everyone else,
  and it landed on the download store's search page — reported from the field as
  "opened the Qobuz Download website". open.qobuz.com is the host both platforms
  hand to the app, and its router knows five shapes, all of them ids
  (`/album/:id`, `/artist/:id`, …). There is no search route on it or in the app
  behind it, and play.qobuz.com is claimed by the same app so it lands in the
  same place. `QobuzAlbum` reads the id off Qobuz's own public search page — no
  API, no key — and the page swaps the chip when it arrives. A record Qobuz does
  not carry never upgrades, which is right: **a wrong album is worse than a
  search page**, so `pick` takes the exact album+artist slug wherever it appears
  and never the first hit.
- **The Qobuz link then goes through `qobuzapp://` first, on Android.** The
  https link works but not from cold — the app opens on Home having dropped the
  album, and only a second tap lands on the record. open.qobuz.com's own page
  skips https entirely on a phone. `QobuzAlbum.appUri` builds that scheme and is
  deliberately strict, because the string is handed to `startActivity`: our
  host, our path, an id of letters and digits, and no query or fragment.
- **A streaming link is an https link, never a custom scheme, and the HOST is
  the part that goes wrong.** `spotify://` opens the app and does nothing at all
  when the app is absent; an https link opens the app on a phone that has it and
  the web player on one that does not. But a host whose app claims EVERY path
  opens on its own home screen when the path is one it has no screen for — which
  is how MusicD Remote Lite shipped `open.qobuz.com/search?q=` and made a link
  that looked like it simply did nothing. `www.qobuz.com` publishes no
  assetlinks and reaches the browser. Check `.well-known/assetlinks.json` and
  `.well-known/apple-app-site-association` before adding a service.
- **The link row does NOT check whether a service carries the record, and that
  was decided rather than overlooked.** Only four of the eight can be asked
  without developer credentials — Pitchfork and Qobuz already resolve, and Apple
  Music (the keyless iTunes Search API) and Deezer (its open API) could, which
  would also turn those two into direct album links. Spotify and TIDAL need
  OAuth, Amazon Music has no public API, and Bandcamp would need page-scraping.
  So the row would end up half verified and half blind either way, and hiding a
  chip and greying it out need exactly the same lookup — the only difference is
  whether the row changes shape between albums. Put to the owner with that laid
  out, the answer was to leave it alone. Reopen it as a product question, not as
  something nobody thought of.
- **The search query is percent-encoded, and a slash is spent as a space.**
  `URLEncoder` writes a space as `+`, which four of the six services take as a
  literal plus because they carry the query in the PATH. And `%2F` is decoded
  back into a path segment by Qobuz's own redirect, so "AC/DC" 404s. Both are in
  `StreamingLinks.searchQuery` with a test each.
- **Nothing links to Roon, and that is a SCOPE decision, not a technical wall.**
  Half of it is a wall: Roon publishes no URL scheme and no web player, so there
  is no link to build. Checked against RoonLabs' own `node-roon-api` and
  `node-roon-api-browse` — the whole extension API is MOO over a WebSocket, and
  the only URL anywhere in it is `msg.props.http_port` for the image service.
  Grepping a full Roon remote for `roon://` finds nothing either.

  The other half is NOT a wall, and an earlier note here wrongly implied it was.
  `com.roonlabs.browse:1` has a `"search"` hierarchy, and MusicD Remote Lite
  uses it against real hardware: `browse(hierarchy="search", input=title)`, take
  the "Albums" heading out of the grouped results, `drillActionMenu` the album,
  `invoke` its Play Now with a `zone_or_output_id`. A "Play in Roon" BUTTON is
  entirely buildable and would do more than the six search links do.

  It is left out because it would make this app able to start music. Every route
  here is a read, `TokenStore` is the only thing that writes, and that rule is
  load-bearing — see [Access]. Asked directly, the answer was to leave it out.
  Reopen that decision with the owner, not on the grounds that it cannot be
  done. One thing still unverified if it ever is: whether adding a service to
  the registration re-prompts for approval in Roon → Settings → Extensions.
- **`sharecard.js` is a port, not this project's code.** It is MusicD Remote
  Lite's file, and it is the card's visual definition. A change here that is not
  also made there means the two apps stop producing the same picture — which is
  the reason it was ported rather than rewritten. Change it upstream by
  preference.
- **The webfont weight set is 300–700 with no 800, deliberately.** The card draws
  its score at 800 and the browser synthesises it from 700. MusicD Remote's card
  is drawn against that same synthesis, so requesting a real 800 here would make
  the two cards differ.
- **The long press is the iOS copy path.** `user-select: none`,
  `-webkit-touch-callout: none`, a `touchstart` that calls `preventDefault`, or
  an overlay on the image each kill it silently, and the loss is invisible until
  somebody actually holds a finger on the card.

- **Text selection is off page-wide, and the card is put back explicitly.**
  Holding a finger near a button was selecting the label and raising Copy / Look
  Up / Translate. `html, body` carry `user-select: none`, but `.stage img` sets
  it back to `auto` with `-webkit-touch-callout: default` — without that the
  page-wide rule reaches the card and silently removes the iOS long-press menu,
  which is the only way an iPhone copies the picture. `.diag` (the crash report,
  which exists to be read off the screen and typed out) and every input keep
  `user-select: text`.

- **startForeground() is the FIRST thing CardService.onCreate does.** A start
  delivered as `startForegroundService` must be answered within about five
  seconds or the system kills the process — with no dialog, no trace and nothing
  in the app to say why. Doing the multicast lock, a file read and a socket bind
  before it put all of that inside the window, and the app closed silently.
  Everything else runs on `sharecard-startup`, off the main thread.
- **The app records its own crashes.** `CrashLog` is installed from
  `ShareCardApplication.onCreate`, ahead of the Activity and the Service, and
  the trace is shown on the next launch and served at `/api/debug`. This device
  is normally in another room with no adb attached; without it, "it just closes"
  is the whole bug report.

- **Updating in place needs ONE signing key, for ever.** Android refuses an APK
  signed with a different certificate and says only "App not installed" — no
  reason, nothing in a log. CI mints a fresh debug key per runner, so until
  `SHARECARD_KEYSTORE_BASE64` exists no build can replace another. That is what
  `latest.json`'s `signed` flag is for: `Updater` refuses to download a build it
  knows cannot install, and says to uninstall first, rather than spending two
  megabytes on that dialog. See `docs/signing.md`.
- **The update manifest may not point the installer at another host.** The URL
  in it names a file this app downloads and hands to Android, so a manifest that
  can name anything can install anything. `Updater.parseManifest` requires https
  AND the same host the manifest itself came from — which is why the workflow
  writes `raw.githubusercontent.com/...` and not `github.com/.../raw/...`. The
  app this was ported from carries a comment saying exactly this above a check
  that only tests the scheme; that was fixed here rather than copied.
- **The update bar is drawn ONLY on the device running the app.** The APK
  installs there; a page open on an iPad across the house is looking at
  software it cannot replace, and its Update button asked for a PIN and then
  offered to update a machine in another room. Reported as "shows the update
  button, does nothing". `/api/update/status` carries `onDevice` — taken from
  the socket address, like every other trust decision here — and the page hides
  the whole bar on it, which also stops a LAN page spending a request on GitHub
  for an answer it will not draw.
- **`/api/update/check` and `/api/update/apply` are POST and gated.** Replacing
  the APK on an always-on device in another room is a bigger write than adding a
  webhook, so both go through `Access.mayConfigure` — and both refuse a GET,
  because a GET that installs software is one a link prefetch can fire by
  itself. `/api/update/status` is a read and stays open. `WRITE_ROUTES` names
  every path a POST may reach; nothing infers it.

## Scope and process

- Develop on the branch named in the task. Never push to another branch.
- Do not open a pull request unless asked.
- Bump `versionName` **and** `versionCode` in `app/build.gradle.kts` for any
  build meant to be installed; Android refuses to install over an equal or lower
  `versionCode`. The workflow publishes `dist/` and rewrites the README link from
  `versionName`.
- **The signing keystore is private key material.** It lives in CI secrets. Do
  not commit it, and do not change the key: an APK signed with a different one
  cannot install over the existing app.
- Ask before guessing when a choice is the user's to make. A corner, a layout, a
  default that is hard to reverse — ask, do not assume and apologise later.
