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
./gradlew :core:test -Psharecard.serverOnly=true        # see below
./gradlew :server:installDist -Psharecard.serverOnly=true
node --check app/src/main/assets/web/app.js
npx eslint -c tools/eslint.config.mjs app/src/main/assets/web/*.js
node tools/check-css.js
node tools/check-sharecard.js
python3 tools/check-icons.py
sh tools/check-launcher.sh
sh tools/check-publish.sh
sh tools/check-docker-paths.sh   # after :server:installDist
```

**A new test must fail before the fix and pass after it.** Prove it: break the
fix, run the test, show it failing, restore. A test that passes both ways is
decoration.

### Building without an Android SDK

The proxy blocks the Android Gradle Plugin, so `:app` cannot be built here — only
in CI. Add `-Psharecard.serverOnly=true` and it is left out of the build:

```bash
./gradlew :core:test -Psharecard.serverOnly=true
./gradlew :server:installDist -Psharecard.serverOnly=true   # the container's server
```

**THAT FLAG REPLACED A SED, AND THE SED WAS THE HAZARD.** The documented way to
run the tests here used to be stripping the plugins out of `build.gradle.kts` and
`settings.gradle.kts` by hand, with "never commit the stripped versions" written
in capitals beside it — a rule that needs capitals is a rule that gets broken,
and a stripped `settings.gradle.kts` on a branch is a build that silently stops
producing an APK. The plugin VERSIONS now live in `settings.gradle.kts` under
`pluginManagement.plugins`, where they are defaults rather than declarations: a
version is resolved only if something actually applies it, so a build with `:app`
left out never asks for AGP at all. That is also what keeps the container image's
builder stage from pulling an Android toolchain in to compile a JVM server.

## The app is not only an Android app

`:app` and `:server` are two shells around one `:core`. The Android one is a
foreground service, a WebView and a share sheet; the container one is a `main`
and a mounted directory. NOTHING THAT DECIDES ANYTHING MAY LIVE IN EITHER OF
THEM — a rule that has been here since the first line, now with a second way to
break it.

- **ONE PAGE, PUT ON A CLASSPATH, NEVER COPIED.** `:server` declares
  `app/src/main/assets` as a resource root, so `web/app.js` is the same bytes the
  APK carries and `Assets` is the same lookup. Copying it would be a second card
  that drifts from the first, which is exactly why `sharecard.js` is a port
  rather than a rewrite.
- **ONE VERSION NUMBER.** `server/build.gradle.kts` reads `versionName` out of
  `app/build.gradle.kts` with the same expression CI uses. `/api/debug` is how a
  bug report says which build it came from, and a second place to bump is a
  second place to forget.
- **THE THREE THINGS THAT WRITE TO DISK MOVED INTO `:core` AND IMMEDIATELY
  FAILED A TEST.** `FileTokenStore`, `FileWebhookStore` and `FileCacheStore` were
  `RoonTokenFile`, `WebhookFile` and `CacheFile` in `app/`, where the only
  Android-shaped line in each was asking a Context where `filesDir` is. The
  moment they landed in a module the scans look at, `JsonSafeTest` refused them:
  all three read with `optString`, which on Android returns the literal text
  `"null"`. That shipped as a Roon token reading `"null"` handed to a Core, and a
  pairing that looks present and is not. **Nothing was wrong with the move; the
  code had been wrong for as long as it existed and nothing was looking.** That
  is the whole argument for pushing logic down into `:core`, stated by the
  repository rather than by me.
- **NO UPDATER IN THE CONTAINER, DELIBERATELY.** The Android build downloads an
  APK and hands it to the package installer; a container replaces itself with
  `docker compose pull`. `updateInstaller` is simply not passed, which is the
  path `/api/update/status` already had for any host that cannot install — the
  page hides the bar and nothing new was needed.
- **MULTICAST IS THE CONTAINER'S VERSION OF THE MULTICAST LOCK.** On Android
  discovery needs a `WifiManager.MulticastLock` or SSDP silently returns nothing.
  In Docker it needs `network_mode: host`, and for the same reason: SSDP, Roon's
  SOOD and Lyrion's UDP 3483 are all multicast or broadcast and NONE of them
  cross a bridge. On a bridge the container comes up healthy, answers on its
  port, and finds nothing — indistinguishable from a network with no players on
  it, which is why it is the first thing the README says about it.
- **THE PIN DEADLOCK THE CONTAINER CREATES.** [Access] trusts loopback without a
  PIN because on Android loopback IS the device somebody is holding, and it
  serves the PIN only to loopback. A server in a cupboard has no browser, so
  nobody could ever see the PIN and webhooks could not be configured at all.
  `SHARECARD_PIN` sets it, and failing that the minted one is printed to the log
  — which is the container's equivalent of standing in front of the device:
  whoever can run `docker logs` already owns the process and its data directory.
  A PIN that is not six digits is REFUSED and said so, because the page's field
  takes six and silently ignoring it would leave an operator believing they had
  set one.
- **THE CONTAINER IS NOT ROOT, AND THE COST IS PAID WITH A SENTENCE.** It binds a
  LAN port and writes a webhook URL; neither wants uid 0. That makes a bind mount
  created by hand owned by somebody else on a first run — and because every write
  in this app is deliberately survivable, the result is an app that works
  perfectly and forgets everything on restart, with nothing saying why.
  `checkWritable` probes the directory at startup and names the uid and the
  chown. It warns rather than refusing: a card that draws beats a server that
  will not start.
- **`/api/health` IS THE HEALTHCHECK, AND THE NO-POLLING RULE IS WHY IT CAN BE.**
  A check runs every thirty seconds for the life of the container. The rule that
  route must touch no network was written for a different reason and paid for
  itself here.

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
The three file stores moved down for the same reason and a scan caught a real bug
in them on the way — see above.

`server/src/` is a third case again: it is plain JVM, so it can be RUN here. The
container build was verified by building `:server:installDist`, starting it, and
driving the real routes with curl — the page and every asset served, a webhook
added from a non-loopback address with the PIN and refused without it, the file
written 0600, and all of it still there after a restart. What CANNOT be verified
here is the image itself: there is no Docker daemon in this environment, so the
`Dockerfile` is checked by CI and nothing else. Say so when handing it over.

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
- **`Source.notice()` SPEAKS ONLY WHEN A CORE IS ACTUALLY THERE, and that
  NARROWS an earlier rule here.** The rule used to be "every stage that is not
  paired gets a line", written when a Core that was never found said nothing and
  Roon looked simply ignored. That was right when Roon was the reason to run
  this app. With four sources it inverted: most people do not run Roon, and
  "Looking for your Roon Core…" sat on their screen for ever, under a card that
  had worked perfectly, about a product they do not own. Reported as exactly
  that. The dead end the old rule guarded is real only when a Core IS present,
  so AWAITING_APPROVAL still speaks (you must enable the extension) and so does
  ERROR (a Core was found and would not talk). `ABSENT` — asked the network,
  found no Core — is a stage of its own and is SILENT. The fact is not lost:
  `/api/debug` still prints the stage.
- **ROON'S FAILED DISCOVERY USED TO RESCHEDULE ITSELF EVERY THIRTY SECONDS, FOR
  EVER.** On a device that is never switched off that is a multicast sweep of
  the household twice a minute to answer a question nobody asked — the precise
  thing the no-polling rule below forbids, sitting inside the one source nobody
  had checked against it. It looks once now and stops; Refresh calls
  `rediscover()` and starts a fresh look, which is the bargain every other
  source here already makes. `RoonQuietTest` scans for a timer coming back.
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
- **LYRION IS ASKED AT THE SERVER, NOT THE PLAYER, and it ranks with Roon.** A
  Squeezebox, a piCorePlayer or a squeezelite process knows almost nothing about
  what it is playing — the server holds the library, resolves the metadata and
  owns the artwork. So `LmsSource` sits above Sonos and UPnP in the list for
  exactly Roon's reason: asked about one room, the server is the one that knows
  what the record is, and the endpoint sees a stream.
- **A LYRION RADIO STREAM PUTS THE STATION IN `playlist_loop` AND THE SONG IN
  `remoteMeta`.** The loop entry is what was QUEUED; `remoteMeta` is what is
  PLAYING. So remoteMeta wins every field it has and the loop entry fills the
  gaps behind it — the other way round draws a card headed "BBC Radio 6 Music"
  while the server knows perfectly well it is playing Aphex Twin. Same shape as
  reading Roon's `three_line` in the wrong order, and it looks almost right,
  which is what makes it expensive. An EMPTY remoteMeta field is not an answer
  and must not erase the station's artwork behind it.
- **A LYRION ID IS ONLY A COVER PATH WHEN IT IS DIGITS.** Anything remote is
  numbered with a NEGATIVE id, and pasting one into `/music/<id>/cover.jpg`
  builds a URL that 404s on every card. `artwork_url` comes first regardless,
  absolute for a station's CDN and relative for the server's own proxy.
- **A LYRION COVER ID IS AN OPAQUE TOKEN, AND THE FALLBACK MUST TRY EACH ONE.**
  Two bugs in four lines, and together they meant no Lyrion card ever drew a
  cover. First, `artUrl` asked for the first NON-EMPTY of coverid /
  artwork_track_id / id and only then checked whether it was usable — so a
  coverid that was present but not a plain number ended the search there, with
  artwork_track_id sitting beside it never looked at. A fallback chain that
  stops at the first candidate is not a fallback chain. Second, the check
  demanded DIGITS, and current Lyrion writes coverid as hex. What actually has
  to be refused is a NEGATIVE id, which is how Lyrion numbers everything
  remote; plain ASCII letters and digits refuses that, and refuses a slash or a
  dot walking out of the path with it.
- **TWO SOURCES SEEING ONE ROOM IS A FREE CONTROL EXPERIMENT.** The dump that
  settled the Lyrion cover had the same speaker twice — `Lyrion` with `art: ""`
  and `UPnP` with `http://…/music/c8536003/cover.jpg`, the very URL the Lyrion
  source should have built. One row was the bug and the row beside it was the
  expected answer. When a zone appears under two sources, compare them before
  reasoning about either.
- **"NO COVER" HAS FOUR CAUSES AND THEY LOOK IDENTICAL ON A CARD**: the source
  sent no art url at all, the proxy refused the host, the server answered 404,
  or the bytes were not an image. Telling them apart cost two rounds of
  diagnosis before `ArtProxy.attempts()` existed. It now records every fetch
  and its outcome, `/api/debug` carries it under `art`, and the page draws it —
  `DiagnosticsDrawnTest` is what forces that last part, and it was shown
  failing with the section removed.
- **`optBoolean` READS THE NUMBER 1 AS FALSE.** LMS writes its booleans as 1 and
  0, so reading `connected` with `optBoolean` marks a whole household asleep.
  `LmsClient.truthy` takes a number, a string or a real boolean. Same family as
  the `optString` rule above: org.json's opt* accessors are not doing what the
  name suggests.
- **THE LYRION WIRE SHAPES ARE DOCUMENTED, NOT OBSERVED — EXCEPT WHERE A DUMP
  SAYS OTHERWISE.** No Lyrion server is reachable from here, so `LmsStatusTest`
  and `LmsDiscoveryTest` pin the protocol as written down rather than as
  captured. TWO THINGS ARE NOW REAL, both from one `/api/debug` off a DietPi
  box: the broadcast answers (`DietPi at 192.168.0.57:9000`), and a coverid is
  HEX — `c8536003`, which the digits-only rule had been throwing away. The
  test carrying that value says OBSERVED in its name, because the difference
  between a shape somebody wrote down and one a machine actually sent is worth
  being able to see at a glance. That is why the parsing is
  lenient — every field is looked for in more than one place — and why the
  socket is kept out of `parseReply`: if the real thing differs, it is one
  function to correct rather than a broadcast to debug from another room. Treat
  the first real run as the verification, and read `/api/debug` first.
- **LYRION IS FOUND BY BROADCAST, WHICH IS THE FIRST THING A NETWORK BREAKS.**
  UDP 3483 is dropped by mesh systems, guest VLANs, client isolation and any
  Docker bridge — the same list SSDP fails on. `SeedHosts` is therefore shared:
  an address typed in for Sonos is tried as a Lyrion server too, because from
  the user's side it is one question, not one per protocol. The broadcast's
  `JSON` tag carries the real web port, so a server moved off 9000 is still
  found rather than assumed.
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
- **A MUSIC SERVER STREAMING TO A SPEAKER MAY SERVE THAT TRACK'S PICTURE.** The
  art proxy allows a known player or a confidently public https host, and a
  server on the LAN is neither — so a MusicD Server on a DietPi box at
  192.168.0.57:3400 streamed "Heaven or Las Vegas" to a Sonos and the card came
  out with the album, the artist, the blurb and a BLANK SLEEVE, while the Sonos
  app three feet away showed the cover because it fetches that URL directly.
  `/api/debug` named it in one line. `StreamHosts` is the fix: the host in the
  track's own transport URI has been observed carrying the audio, so it is not
  a new trust decision — it is the thing playing the music. Nothing is inferred
  from the ART url, which is the part an attacker would control, and "looks
  local" still earns nothing.
- **THAT BUG HID ONCE BEHIND A COINCIDENCE, WHICH IS WORTH KNOWING.** It
  appeared to fix itself on 0.40.0, because Lyrion runs on the SAME box and
  `LmsSource.artHosts()` put that address in the allowlist for its own reasons.
  Two servers sharing a host is not a fix; stop Lyrion and the sleeve goes blank
  again. When a bug disappears without the change that was meant to fix it, find
  out which unrelated thing is holding it up.
- **THE ALBUM BLURB IS CHECKED AGAINST THE ARTIST NOW, AND IT NEVER WAS.**
  `wikipediaAlbum` put the artist in the SEARCH QUERY and then guarded the
  answer on the album title alone, so Wikipedia's ranking was the only thing
  deciding which record the words were about. "Cult" by To/Die/For came back
  with Static-X's "Cult of Static" — `Normalize.namesOverlap` anchors at the
  front and deliberately accepts a name qualified on the RIGHT, which is what
  makes "Spiderland" match "Spiderland (Slint album)" and is wanted there.
  Reported from the field as the wrong blurb. An album page rarely names the
  act in its TITLE and almost always does in its first sentence, so
  `albumArticleFits` reads the EXTRACT through `Normalize.mentions` — the
  prose counterpart to `namesOverlap`, a run of whole words rather than a
  prefix, because a name sits anywhere in a sentence. "and" is dropped from
  both sides or "Nick Cave & the Bad Seeds" is refused over a conjunction;
  "the" is NOT, or "The Who" lands back inside "The Guess Who". A spelling the
  article does not carry costs the blurb, which is the trade this app keeps
  making: a missing blurb is honest, a confident wrong one is not.
- **ROOMS ARE OPT-IN NOW, AND THAT IS A DEFAULT THAT BREAKS A WORKING APP
  ONCE.** `Settings` stores the exception to each default and the two sets run
  OPPOSITE ways: SERVICES default on, so the set holds the ones switched OFF
  and a service added in a later version appears by itself; ZONES default off,
  so the set holds the ones switched ON and a television powered on next week
  stays out of the picker until it is asked for. Stored the other way round,
  each default inverts the moment the file is written — an upgrade that hides
  every streaming link, or a device that appears unasked. Put to the owner with
  the cost spelled out (every existing install, theirs included, goes blank on
  update until rooms are chosen), the answer was to do exactly that: one rule,
  no migration.
- **SERVICES ARE OPT-IN TOO, AND DEFAULTING THEM ON WAS REPORTED AS A BUG.**
  They stored the set switched OFF, on the reasoning that a service added in a
  later version should appear by itself. The first run said otherwise — "the
  services show enabled already, said disabled" — and the report is right: an
  app whose rooms are opt-in and whose services are opt-out is one rule wearing
  two faces. Both sets hold what is switched ON now, and both default to
  nothing. The older `disabledServices` file is simply not read; everything it
  named is off anyway.
- **A REDRAW THAT CLEARS THE ERROR LINE SILENCES THE ONLY EXPLANATION THERE
  IS.** A refused settings change has to put the switch back, and putting it
  back means redrawing — but every screen clears `errEl` as it opens, so
  setting the message BEFORE the redraw wiped it. What a person saw was a
  switch that flicked back and said nothing, which reads as an app that ignores
  you. The message is set AFTER the redraw now. Same shape as `buildActions`
  clearing `hintEl` and silencing a Roon notice a moment after it was set — the
  second time this exact mistake has been made in this file.
- **A PIN READ OFF THE FIELD WORKS EXACTLY ONCE.** Every settings change
  redraws from the server's answer, which recreates the input EMPTY — so the
  first switch after typing the PIN succeeded and every one after it was
  refused, silently, thanks to the bug above. Reported as changes that would
  not stick in Docker, where every browser is a remote one and the PIN is
  always required; measured in a real browser as Spotify off succeeding and
  Deezer off a moment later not. `heldPin` keeps it for as long as the page is
  open and the field is redrawn carrying it.
- **THE TWO PLATFORMS DIFFERED ONLY IN WHICH ADDRESS THE BROWSER WAS ON.**
  "Works on Android, not in Docker" was not about Android at all: the app's own
  WebView is loopback and needs no PIN, while a container is browsed from
  another device and always does. When the two builds disagree, check the
  socket address before looking for a platform — the same lesson as the 420px
  breakpoint that was mistaken for an Android layout bug.
- **RENAMING THE PUBLISHED ARCHIVE MOVED THE DIRECTORY `installDist` WRITES
  TO.** `distributionBaseName` renames both, so `build/install/server` became
  `build/install/musicd-share-card-server` — and the Dockerfile still copied
  the old path. Locally it kept working, because the old directory was still
  sitting in the build tree from before the rename and everything I ran was
  reading it. CI failed on the image build with "not found", which is exactly
  what that job is for; `tools/check-docker-paths.sh` now catches it in the
  core job instead, and was shown reproducing the same failure locally. WHEN A
  BUILD KEEPS WORKING AFTER A RENAME, CHECK WHETHER IT IS READING THE OLD
  OUTPUT.
- **THE EMPTY STATE MUST NAME THE RIGHT CAUSE.** "No players found on the
  network" is a lie when the players are simply switched off, and the worst
  possible one: it sends somebody to hosts.txt, multicast and VLANs for a
  problem whose fix is two taps. `reasonForNothing` separates "found nothing
  yet" from "found plenty and you have chosen none", and `SettingsApiTest`
  asserts the message points at Settings.
- **SWITCHED OFF MEANS NOT ASKED, NOT MERELY HIDDEN.** `Sources.zoneFilter`
  keys on the zone ID so `inZone` can refuse without a lookup, and every
  consumer inherits it through `zones()`. `allZones()` is the unfiltered list
  and has exactly one caller: the settings screen, which must show the rooms
  that are off. `ZoneFilterTest` asserts the fake speaker is never reached,
  not just that a name is missing from a list.
- **ONE PICK PER SOURCE IS NOT ENOUGH ONCE ROOMS ARE OPT-IN.**
  `source.nowPlaying(null)` answers with that source's OWN best room, which may
  be one that is switched off — and dropping that answer loses the whole
  source, including an enabled room beside it that is playing. The ladder
  filters the volunteered answers and then asks each enabled room the sources
  did not mention. Cost is bounded by how many rooms somebody turned on, and
  `rooms()` already pays exactly that for the chooser.
- **THE SETTINGS SCREEN MUST CLAIM THE STAGE BEFORE IT AWAITS ANYTHING.** Every
  screen draws into the same stage as the card, so a `load()` already in flight
  will paint over it. Claiming after an await is not enough: measured in a real
  browser during a first-run sweep, the first await queued behind the card's
  request and the menu never drew at all — a cog that did nothing. `claimStage`
  bumps the token, aborts the card's fetches and clears `busy`; it is called
  first in every screen, and `SettingsDrawnTest` scans for an await that
  overtakes it.
- **AND THE SERVICES SCREEN MUST NOT ASK FOR ZONES.** Listing zones serialises
  against a discovery sweep, so bundling them into one `/api/settings` payload
  made the Services screen — which needs nothing from the network — wait on a
  sweep it had no use for. `?zones=1` is opt-in: Services and Reviews answer in
  about a millisecond, Zones pays the nine seconds and says "Looking for
  rooms…" while it does, because a list of discovered devices cannot be
  produced without discovering them.
- **THE WEBFONT IS A RENDER-BLOCKING REMOTE STYLESHEET, AND A PENDING
  STYLESHEET BLOCKS EVERY SCRIPT AFTER IT.** `index.html` pulls Manrope from
  fonts.googleapis.com. With no route to the internet the whole page is inert
  until that request gives up — twelve seconds, measured, during which no
  button on the page does anything. This is a LAN app that otherwise needs no
  internet at all. NOT FIXED, and deliberately left rather than changed
  quietly: making it non-blocking changes what the page looks like while it
  loads, which is the owner's call. Reopen it as a product question.

  RE-MEASURED WHILE FIXING THE DISCOVERY SWEEPS, AND IT IS WORSE THAN "SLOW".
  Driven in a real browser against a route that accepts the connection and
  never answers: after six seconds `document.readyState` was still `loading`,
  `app.js` had NOT EXECUTED, not one request had been made, and the page sat on
  "Looking for what's playing…". So this is not the page being sluggish — it is
  the page not being running. It still needs the owner's answer because the
  cost is real on the other side: `ensureFont()` waits for Manrope before
  DRAWING, and a stylesheet that no longer blocks can lose that race and draw
  the card in the system sans. The card is the product, so the fix is a bounded
  wait on the link's own load event, not simply making it async.
- **A source can report an opaque id where a title should be.** Roon streaming
  to Sonos sends "Roon" + 32 hex characters as `dc:title`. A card headed with a
  hash looks like the app working, which is worse than one that admits it knows
  nothing — see `Didl.looksLikeStreamId`, kept deliberately narrow because the
  cost of a false positive is discarding a real album.
- **The cover MUST be proxied.** A canvas that has drawn a cross-origin image
  cannot be read back: `toBlob` throws and there is no card. Sonos sends no CORS
  header, so pointing the page at a player directly can never work, however much
  simpler it looks. See `ArtProxy`.
- **THE TWO SHELLS SERVE ON DIFFERENT PORTS: ANDROID 8748, THE CONTAINER
  8747.** Both answered on 8747, which is fine right up until somebody runs
  both — and running both is the ordinary case while a phone in a dock and a
  box in a cupboard are being compared. Same port on two addresses is not a
  clash any OS reports; it is a bookmark that quietly starts answering for the
  wrong one. `ShareCardApp.ANDROID_PORT` is what `CardService` passes; the
  container keeps `DEFAULT_PORT` and `SHARECARD_PORT` still overrides it.
- **THE CONTAINER ASKS FOR NO PIN BY DEFAULT, AND THAT NARROWS THE RULE
  BELOW.** The gate trusts loopback and challenges everything else, which is
  right on Android: loopback IS the app's own WebView, held by somebody
  standing at the device, and the PIN is on that screen. A container usually
  has no browser on it at all, so every visit is a remote one — the gate
  applied to everybody, for switching a room on, with the PIN only obtainable
  from `docker logs`. Asked for directly, and implemented as `requirePin`:
  unset `SHARECARD_PIN` means no gate, setting it turns the gate on. WHAT IT
  COSTS IS STATED IN THE README rather than hidden: with the gate off, anyone
  who can reach the port can change webhooks and start an update. What does
  NOT change either way is that no route ever returns a webhook URL — a test
  asserts that with the gate off, because widening who may CHANGE things must
  never widen what can be READ.
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
- **AN EMPTY SWEEP IS AN ANSWER, AND IT IS REMEMBERED LIKE ANY OTHER. THIS WAS
  COSTING FORTY-SEVEN SECONDS A PAGE LOAD.** All three network sources cached a
  discovery only when it SUCCEEDED — `zones.isNotEmpty()` in `Household.refresh`,
  `renderers.isNotEmpty()` in `UpnpSource.scan`, `known != null` in
  `LmsSource.scan` — so a source that found nothing searched again on the very
  next question, for ever, inside the request. Measured against the real server
  on a network with no players: `/api/zones` 9.3s and `/api/now-playing` 37s, on
  EVERY request, because the fallback ladder asks each source and then each
  enabled room and every one of those re-swept. With the answer remembered: 9.4s
  once, then about a millisecond until the TTL runs out. Reported as "very slow
  to detect zones and populate sharecards". It is the same fault Roon's
  discovery had — it looked again every thirty seconds for ever rather than
  looking once — and it is this rule wearing a different hat. Refresh still
  forces, which is the bargain every source here already makes: a speaker
  switched on a moment ago is one tap away rather than automatic. `EmptySweepTest`
  counts the sweeps.
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
- **EVERY CHIP IN THAT ROW IS ONE FIXED SIZE, AND A LABEL MAY NEVER SET IT.**
  `align-items: stretch` keeps the grid a grid by making the one-line chips as
  tall as the two-line ones — and it works the other way too, so ONE tall chip
  makes every chip on its row that tall. The artist review chips were labelled
  `"AllMusic: $artist"`, a Roon card answered "Stan Getz / Cal Tjader / Alan Jay
  Lerner / Frederick Loewe", and the row drew as four CIRCLES with a small pill
  orphaned under them. Reported as "button sizes completely off" and reproduced
  in a browser at 390px: heights 30..117px on one row. Two halves to the fix and
  both are needed. A chip label is a CONSTANT out of `Reviews.Source.chip`,
  never built from a record — the row gives it a quarter of a phone. And the
  height is STATED (`height: 44px`, two lines' worth, with the label clamped to
  two lines in a span) rather than grown into, because a row whose height is
  set by its longest label is a trap somebody walks into twice. Measured 44px
  everywhere at 320, 360, 390, 430 and 1200.
- **AND THE TYPE IN IT FOLLOWS THE WIDTH, WITH NO BREAKPOINT.** A quarter of a
  320px phone is 67px and "Wikipedia" at 12px does not fit, so `break-word` did
  what it says: "Wikipedi / a", "Qobu / z", "Bandca / mp" — the outcome
  `anywhere` was rejected for, arrived at honestly. `clamp(10px, 3.2vw, 12px)`
  is 12px above about 375px and smaller below it, continuously. A media query
  would have done the same job and is the thing to avoid: `@media (max-width:
  420px)` is what made one reporter's Android and iPhone lay out differently and
  read as a platform bug. A size that follows the width has no edge for two
  devices to sit either side of.
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
- **A SEARCH'S FIRST ROW IS NOT THE ANSWER — DEEZER EDITION.** The
  similar-artist lookup asked Deezer for `limit=1` and used whatever came back.
  Deezer carries every act sharing a name, and plenty of famous names are also
  carried by somebody with a dozen followers and no related artists — so the
  search succeeded, the name check passed, and the related lookup returned
  nothing. Reported from the field as Sting getting no suggestions while The
  Police, Calexico and The Sea Within each got three. It now takes a page of
  rows, keeps the ones that really carry the name, and tries them **most-
  followed first**; `nb_fan` RANKS and never filters, so a small act with its
  name to itself is still found. A candidate with no related acts is not the
  end either — the next is tried, because an empty answer from the wrong Sting
  says nothing about the right one. Same lesson as `QobuzAlbum.pick` and the
  Pitchfork listing, third service.
- **THE REPORTED SYMPTOM WAS "SPOTIFY CONNECT GIVES NO SUGGESTIONS" AND THE
  SOURCE HAD NOTHING TO DO WITH IT.** Spotify Connect and Qobuz Connect to a
  Sonos speaker are the SAME code path — both `SonosSource` — so a difference
  between them could never have been about where the music came from. It was
  the artist. `/api/debug` is what settled it: the notes print the artist
  string the lookup used and what came back, and "deezer(Sting) -> 0 acts"
  beside "deezer(Calexico) -> 3 acts" named the real variable in one line.
  Check the diagnostics before accepting a correlation.
- **`Similar` NOTES THE HTTP STATUS FOR ListenBrainz, not just "no answer".**
  It has never once answered in the field, and "no answer" cannot tell a
  rejected dataset name (400) from a moved endpoint (404) from a host that was
  never reached (0, which is not a status — it means the request got no answer
  at all). Three different fixes, so the note names which.
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
- **MORE THAN ONE ROOM ON IS A CHOICE, AND THE APP DOES NOT MAKE IT.** Answered
  as one card, "whatever's playing" had to pick a room and silently discard the
  rest — which is the same complaint as the zone bug one step out. Two or more
  rooms playing returns `choose` and a grid of covers instead; ONE room on
  still draws the card, because a grid of one tile costs a tap and shows
  nothing the card would not, and a house where everything is paused still
  falls down the ladder as before.
- **THE GRID RULE LIVES IN `:core`, NOT IN `app.js`.** The page branches on the
  server's `choose` flag and never counts the playing rooms itself. Nothing on
  the page can be tested here, and a second copy of the rule is a second place
  for it to drift — `ChooserDrawnTest` asserts the page has not grown one.
- **`Sources.rooms()` COLLAPSES ONE ROOM SEEN BY TWO SOURCES.** Roon playing to
  a Sonos speaker is seen by both and both say "playing", so a grid that
  counted zones drew two tiles for one record — one of them headed with a
  session id. Rooms folding to the same name through `Normalize.text` collapse
  to one, and the survivor is picked by the ladder's own tie-break, so Roon
  supplies the tile and it says the album. What it CANNOT do is spot one
  speaker under two DIFFERENT names, and `RoomsTest` asserts that limit rather
  than pretending otherwise: a spare tile is visible and tappable, where
  wrongly merging two real rooms would hide one of them.
- **A SILENT ROOM IS LISTED, NEVER DROPPED.** A grid holding only the live
  rooms reads as the others having gone off the network, which is a worse and
  wronger statement than "not playing". They are tappable too — reaching a room
  is how you find out it is silent rather than missing.
- **THE CHOOSER IS THE SECOND EXCEPTION TO THE NO-SCROLL RULE, for the
  diagnostics' reason.** A list of rooms exists to be read and tapped, so a
  room clipped off the bottom is a room you cannot reach. `.stage.choosing`
  keeps the base `flex: 0 1 auto` — growing to fill drew a tall panel with the
  rooms huddled at the top — so the panel hugs them and only scrolls once a
  house has more rooms than fit. Verified by measuring at 320, 360 and 390px:
  three playing and three silent scroll nothing at all, and eight playing with
  six silent scrolls the STAGE while the page stays put.
- **THE ACTION ROW, THE LINKS AND THE SUGGESTIONS ARE NOT DRAWN ON THE GRID.**
  There is no card, so they have nothing to act on, and `load()` already
  empties and hides all three before every request — the chooser simply returns
  before building them. That is also what gives the grid its height.
- **"WHATEVER'S PLAYING" IS THE EMPTY STRING, AND THE EMPTY STRING IS FALSY.**
  `loadZones` took `zoneSel.value || data.selected`, which cannot tell "nobody
  has chosen yet" from "somebody just chose Whatever's playing" — both are "".
  So choosing it fell through to the room the server still remembered, the
  picker snapped back, and `load()` then SENT that room: the server never
  received a request without a zone, so it never cleared `preferredZoneId`, and
  the state latched. Reported as being able to reach every individual room but
  never the grid again. `selected` is only ever a SEED for a page that has just
  opened; once `pickerUsed` is set, the picker is the truth. A page reload with
  a room remembered still restores it, which is the only thing `selected` was
  ever for.
- **THE ORDER IS `loadZones` THEN `/api/now-playing`, and that is what made it
  self-perpetuating.** The server clears its memory correctly when no zone is
  named — but the page rewrote the picker from that memory BEFORE asking, so
  the clearing branch was unreachable. When a page and a server disagree about
  remembered state, check which one runs first.
- **A SOURCE SCAN MUST READ CODE, NOT PROSE.** The regression scan for the
  above matched the comment explaining the fix, because the comment quotes the
  broken expression on purpose. It also first asserted the wrong invariant —
  "never mention `data.selected`" — which forbids the seed that is still
  wanted. Assert what must be TRUE (every use is gated), and filter comment
  lines out before scanning.
- **A TILE DRIVES THE PICKER, it does not go around it.** Tapping one sets
  `zoneSel.value` and re-loads, so the dropdown and the card can never disagree
  about which room is being shown — and the named-zone lock below then applies
  to it like any other choice.
- **THE TILE NAMES ITS SOURCE on the same condition the dropdown does.** It was
  sent and not drawn in the first cut, and `ChooserDrawnTest` caught it: the
  scan is there because a field can be correct, served and invisible, which is
  exactly how the `similar` diagnostic shipped.
- **A NAMED ZONE IS A LOCK, AND THE FALLBACK LADDER IS FOR "WHATEVER'S PLAYING"
  ALONE.** `Sources.nowPlaying` walks a ladder that ends at any room with
  anything in it, and naming a zone used to walk it too — so selecting an idle
  WiiM Pro Plus drew a card headed "Playing in Stereo Fives · via Roon". The
  picker said one room and the card described another, which reads as the app
  choosing for you. `Sources.inZone` asks that room and answers for that room,
  silence included, and `/api/now-playing` uses it whenever a zone is named.
  Each zone is independent; a room that is not playing says so, by name.
- **Sonos was the ONE source walking that ladder for a named zone, one layer
  further down.** Roon and UPnP have always treated a zone id as a filter
  (`client.zone(zoneId) ?: return null`, `renderers.filter { it.udn == zoneId }`),
  but `Household.nowPlaying(preferUid)` took the uid as a *preference* and fell
  through to any playing group — so fixing `Sources` alone left the bug intact
  for the source that had it. `Household.inGroup` is the locked form, and it
  still resolves through `group()`, so a room that has since been grouped
  follows to its coordinator rather than being refused.
- **AN EMPTY `zone` PARAMETER IS NOT A ZONE.** "Whatever's playing" sends the
  parameter blank, and a blank string that reaches `inZone` names no source, so
  the room lookup returns null and the page says nothing is playing while music
  is. `?.takeIf { it.isNotBlank() }` is what separates the two questions.
- **AN UNNAMED CHOICE MUST NOT BE PINNED.** `/api/now-playing` wrote whoever
  answered into `preferredZoneId`, so the first card silently converted
  "whatever's playing" into that room for every request after it — and once the
  named path became a lock, that turned into a card that stopped following the
  house. It moved the PICKER too: `/api/zones` returns that field as
  `selected`, and the page takes `zoneSel.value || data.selected`, so a reload
  came back with a specific room chosen that the user never chose. Only an
  explicitly named zone is remembered, which makes `selected` mean what its
  name says.
- **PER-ZONE DIAGNOSTICS MUST ASK PER ZONE.** `/api/debug` listed every room
  with the same record on it, because it called `nowPlaying(zone.id)` and got
  the fallback's answer five times over — the report contradicted the source
  lines printed directly above it, and a report that disagrees with itself sent
  two rounds of diagnosis the wrong way. It uses `inZone` now.
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
- **A SEARCH BOX GETS THE FIRST CREDITED ACT, NOT THE WHOLE CREDIT.** A Roon
  card came back credited "Stan Getz / Cal Tjader / Alan Jay Lerner / Frederick
  Loewe" — two performers and the two men who wrote the songs — and every chip
  in the row searched for all four names as one. AllMusic answered in as many
  words: "No search results were found for Stan Getz Cal Tjader Alan Jay Lerner
  Frederick Loewe". It is right; there is no such act. `Normalize.primaryArtist`
  runs inside `StreamingLinks.searchQuery`, so one rule covers every service
  chip and both AllMusic links — and `Reviews.artistUrl` passes the artist in
  the ARTIST slot for exactly that reason, having previously passed it as the
  album and skipped the rule. THE CARD STILL SAYS ALL FOUR: this is the query
  only, and shortening the credit under the cover would be inventing a different
  record. THE SPLIT IS DELIBERATELY NARROW, because throwing away part of a real
  name leaves a search that finds nothing — the same failure from the other
  side. A SPACED slash separates and a bare one does not, or "AC/DC" loses half
  its name to the rule two lines below this one; "feat."/"ft."/"featuring" and a
  semicolon separate; a COMMA does not ("Earth, Wind & Fire") and an AMPERSAND
  does not ("Nick Cave & the Bad Seeds", "Simon & Garfunkel"), which is the same
  conjunction `namesOverlap` drops rather than splits on. The METADATA lookups
  were deliberately left alone: `namesOverlap` anchors at the front and accepts
  a name qualified on the right, so a multi-name credit already matches there.
- **The search query is percent-encoded, and a slash is spent as a space.**
  `URLEncoder` writes a space as `+`, which four of the six services take as a
  literal plus because they carry the query in the PATH. And `%2F` is decoded
  back into a path segment by Qobuz's own redirect, so "AC/DC" 404s. Both are in
  `StreamingLinks.searchQuery` with a test each.
- **REVIEW SOURCES DEFAULT ON WHERE SERVICES AND ZONES DEFAULT OFF, and that
  is not an oversight.** Wikipedia and Pitchfork are what the card has always
  drawn — the blurb under the cover, the score in the corner — so defaulting
  them off would empty every card in the house to make a settings screen
  consistent, which nobody asked for. The ARTIST sources are new and are asked
  for, which is what was requested. `Settings.enabledReviews` is therefore
  NULLABLE: null means nobody has chosen, an empty set means somebody chose
  nothing, and an empty set cannot express both. Every install predating the
  screen has no key at all, which is what keeps its blurb.
- **THE STORED SET IS MATERIALISED FROM THE DEFAULTS BEFORE ONE IS CHANGED.**
  Without that, switching Pitchfork off writes a set containing nothing and
  takes Wikipedia and AllMusic with it — every card loses its blurb because
  somebody turned off a score. `ReviewsTest` asserts each switch leaves the
  others exactly as they were, in both directions.
- **ALLMUSIC IS A SEARCH LINK AND THE SHAPES ARE DOCUMENTED, NOT OBSERVED.**
  Their album URLs end in an opaque id (`…-mw0000190771`) that cannot be built
  from a name, so a direct link would mean reading it off their search page the
  way `QobuzAlbum` reads Qobuz's — a real option, not this change. allmusic.com
  is not reachable from where this was written, so the first real run is the
  verification. The URL goes through `StreamingLinks.searchQuery` like every
  other search link, because that is where the `%20`-not-plus and slash-as-space
  rules live.
- **THE ARTIST BLURB WAS ALREADY BEING FETCHED AND THROWN AWAY.**
  `Metadata.extras` has always brought back the artist's article because it
  comes out of the same search as the album's; the card does not draw it
  because a card is about a record. Offering it under Reviews costs no request
  at all — which is why it is the one artist source that is words rather than
  a link.
- **ROON IS STILL NOT IN THE LINKS ROW, AND IT WAS ASKED FOR AND DECLINED
  AGAIN.** Asked to add Roon as a service link that opens the app on the album.
  Re-checked in 2026: no URL scheme, no web player, still an open feature
  request on RoonLabs' own forum. Put to the owner with the three real options
  — play it via the extension API, leave it out, or launch the app on whatever
  screen it was last on — and the answer was to leave it out. What WAS asked
  for instead is queueing a SUGGESTION into Roon when the card came from a Roon
  zone and the record is in the library, which needs `com.roonlabs.browse:1`
  and is its own change: no Core is reachable from here, so none of that wire
  work can be exercised.
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

  IT WAS REOPENED, AND THE ANSWER CHANGED — but not to what was asked for. Asked
  for a Roon chip in the links row that opens the app on the album; re-checked
  in 2026 and there is still no scheme and no web player, only a feature
  request. Offered play-it, leave-it-out, or launch-the-app-blind, the owner
  chose to leave it out of the links row and asked instead for this: a
  SUGGESTION, on a card that came from a Roon zone, tapped to go on the END of
  that zone's queue. That is `RoonBrowse`, and these are its rules:

  - **ONE ACTION, AND IT IS "Queue".** Roon's album menu opens with Play Now,
    so anything reaching for "the first action" would stop what somebody is
    listening to and start something else, from a tap on a suggestion.
    `pickQueueAction` requires the title to BE "Queue" — not a prefix, not a
    contains, not the first action. An installation in another language finds
    nothing and the tap falls back to opening the record in a streaming
    service, which is the right way to be wrong. `RoonBrowseTest` is mostly
    about this one function.
  - **THE LINK STAYS ON THE CHIP AND IS THE FALLBACK.** A suggestion is
    deliberately a record you have not played, so Roon often will not have it.
    Queue if possible, open the search if not; the tap always does something.
  - **BROWSE IS OPTIONAL, NOT REQUIRED.** A required service is a condition of
    pairing at all — a Core that refused it would leave the app unable to read
    what is playing, which is the whole product, to support one tap. STILL
    UNVERIFIED, and it is the risk in the change: whether adding a service to
    the registration re-prompts for approval in Roon → Settings → Extensions.
  - **EVERY BROWSE REPLY IS READ NOW, AND THE LAST ONE WAS THE EXPENSIVE
    OMISSION.** Each `browse` was fired and its answer dropped. Roon does not
    answer one with a list unconditionally: `action` may be `message` and
    `is_error` may be set. So a refusal partway down was followed by a `load`
    of whatever screen was still open, and the reason finally reported named
    whichever LATER step then failed — the Pitchfork lesson again. Worse, the
    FINAL invoke, the one that actually queues, was never read at all, so Roon
    refusing it reached the page as "Added to the end of the queue in Roon". A
    tap that claims to have worked is the one failure nobody goes looking for,
    and that is very likely why this came back as "it hasn't been added"
    rather than as an error. `is_error` is read with a lenient truthy, because
    `optBoolean` reads the number 1 as false — same family as `LmsClient
    .truthy`.
  - **THE ZONE IS NAMED WHEN THE ACTION MENU IS OPENED, NOT ONLY WHEN THE
    ACTION IS INVOKED.** Roon decides which actions to offer from the zone they
    would apply to, so a menu opened without one can come back with no playback
    actions in it — which the app then reported as "Roon offered no Queue
    action", naming the wrong cause. UNVERIFIED, like everything else on this
    wire.
  - **WHAT GOES IN ROON'S SEARCH BOX IS NOT WHAT THE SUGGESTION SAYS, AND THE
    OWNER OF THE LIBRARY DIAGNOSED THIS ONE.** A suggestion comes from Deezer,
    and Deezer's copy of a record is whichever pressing it sells: "The
    Offspring · Ignition (2008 Remaster)". The copy in somebody's Roon library
    is called "Ignition". So the search went out with four words no record in
    that house is named, and Roon answered `action: "none"` — which is what the
    new reply check surfaced, photographed on the page, one release after the
    silent failure. Asked directly: "could this be because the version I have
    isn't labelled as 2008 remaster in my Roon library... artist name and album
    title minus (2008 Remaster) should be used. This applies to every album."
    It does. `RoonBrowse.searchInput` folds both sides through rules that
    ALREADY EXISTED for other callers — `Normalize.stripEdition`, written for
    Pitchfork because a review is filed under the plain name, and
    `Normalize.primaryArtist`, written for the links row after a four-name
    credit was spent as one act. The full title is still what gets MATCHED
    against Roon's rows, because `namesOverlap` accepts a name qualified on the
    right either way round.
  - **AND `stripEdition` MOVED INTO `Normalize` RATHER THAN BEING COPIED.**
    Same move `namesOverlap` made when `Similar` became its second caller, for
    the same reason: two copies of a folding rule is how one lookup strips an
    edition and the next does not. Its narrowness is the half to keep — only a
    TRAILING bracket, and only one whose words are editions — because
    "(What's the Story) Morning Glory?" is bracketed at the front, Sigur Ros
    named a record "( )", and "(Taylor's Version)" is a different record rather
    than a dressed-up one. Stripping too eagerly puts the WRONG record in
    somebody's queue, which is the thing this feature tries hardest never to
    do. Shown by deleting the edition check and watching both the Pitchfork
    test and the new one fail together.
  - **`RoonBrowse.attempts()` IS THE TEST, BECAUSE THERE CANNOT BE ANOTHER
    ONE.** Reported from the field: a suggested album that IS in the library
    was tapped and did not arrive in the queue. `/api/debug` had NOTHING to say
    about it — no section, no note — and the page threw the server's `detail`
    away and silently opened a streaming search, so a record Roon refused
    looked exactly like a record Roon had never heard of. The chain has seven
    places to stop and they are seven different fixes. Each attempt is now a
    line under "Queue in Roon" naming the step AND the rows Roon actually sent,
    because "not in your library" and "it is right there and the match refused
    it" are the same sentence from outside. The page shows the reason too.
  - **THE PARSING IS SEPARATED FROM THE SOCKET** because no Core is reachable
    from here, so none of this has been seen on the wire. The shapes are the
    ones `node-roon-api-browse` documents and MusicD Remote Lite drives against
    real hardware; the decisions that can be wrong on their own are pure
    functions with tests. Treat the first real run as the verification.
  - **`/api/roon/queue` IS THE ONLY ROUTE THAT CHANGES ANYTHING OUTSIDE THIS
    APP.** POST only, because a GET that touches playback is one a prefetch can
    fire; gated by `Access.mayConfigure`; and it refuses any zone id that is
    not `roon:`, because choosing a Roon room on somebody's behalf is choosing
    which room to play into.
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
- **THE PUBLISH STEP REGENERATES THE DERIVED FILES EVERY RUN AND COPIES THE APK
  ONCE.** It used to exit the moment `dist/$APK` existed, treating "the file is
  here" as "the manifest is right". Those came apart as soon as a BRANCH
  published before main did: the branch wrote a manifest whose `url` names the
  branch, the merge carried both the APK and that manifest onto main, and
  main's own run then skipped — leaving main telling every device to fetch the
  APK from a branch url that stops resolving the day the branch is deleted. The
  app reads MAIN's manifest, so that is not cosmetic. It happened twice: once
  caught by hand inside a merge conflict, and once shipped because the next PR
  merged cleanly and nothing corrected it.
- **`notes` NAMES THE BUILD, NOT THE PUSH, and that is what keeps the churn
  away.** Regenerating it from `GITHUB_SHA` every run made the manifest differ
  on every push even when the APK had not moved — a commit per push, which is
  the churn the old early-exit existed to prevent. When the APK is already
  committed, the note that came with it is still the true one and is preserved.
  The commit is skipped entirely when nothing actually changed.
- **ONE PUBLISHER, AND IT IS THE DEFAULT BRANCH. TWO OF THEM IS A CONFLICT IN
  EVERY PULL REQUEST, BY CONSTRUCTION.** `dist/latest.json`, `README.md` and
  `docs/index.html` are all GENERATED by the publish step, from the version and
  from `GITHUB_REF_NAME` — and it ran on every branch. So main and a branch did
  not merely both write those files, they wrote DIFFERENT bytes into every line
  carrying a version or a ref: `.../main/dist/musicd-share-card-0.51.0.apk`
  against `.../claude/sonos-…/dist/musicd-share-card-0.52.0.apk`. Every pull
  request conflicted on all three, every release, resolved by hand each time —
  reported as "this keeps happening". A conflict between two generators of one
  file is not a merge going wrong; the only permanent fix is one writer. Nothing
  was lost by narrowing it: a branch manifest is read by nothing (the app reads
  MAIN's) and names a url that stops resolving the day the branch is deleted,
  which is the bug two entries below this one, the one that shipped twice. The
  branch build still compiles, signs, checks and uploads the APK as an artifact.
  `tools/check-publish.sh` asserts the invariant rather than the line: the step
  is gated on the repository's own default branch, AND nothing else in the
  workflow commits at all, because a second publisher added later is how this
  comes back wearing another name. WHAT IT COSTS is that an open pull request's
  `dist/` and README link still name the previous version until it merges.
- **A DUPLICATE PUBLISH IS NOT A FAILURE.** Two pushes a minute apart build the
  same `versionName`, write the same APK path, and the loser's rebase hits an
  add/add conflict on a file identical but for its notes — reddening a build
  whose only fault was being second. The push now retries onto the moved
  branch, and on a conflict checks whether the REMOTE already carries this
  version at this ref: if it does, it stands down; anything else still fails
  loudly rather than being forced through.
- **THE WORKFLOW WAS TESTED BY RUNNING IT, not by reading it.** The publish step
  is extracted from the YAML and driven against throwaway git repos — a branch
  publishing then merging to main, two concurrent runs at one version, a
  docs-only push, and a real bump. That harness is what caught the `notes`
  churn, which reading the diff had not. Any change here should be exercised
  the same way; CI is not the place to discover it.
- **THE CONTAINER UPDATES ITS OWN CODE, AND IT IS NOT GIVEN THE DOCKER
  SOCKET.** Pulling a real image needs `/var/run/docker.sock`, which is root on
  the host — handed to a process that answers the whole LAN and whose every
  route is a read precisely so it holds nothing worth attacking. Put to the
  owner with that laid out, the answer was to move the CODE instead: download
  the published `:server` zip, unpack it beside the running build, exit, and
  let `restart: unless-stopped` bring the container back on it. The cost is
  stated in the README rather than hidden — the JRE and the OS packages
  underneath change only when somebody pulls an image by hand.
- **THE TWO BUILDS DO NOT SHARE A LAST STEP, AND THE BAR SAID THEY DID.**
  Android writes an APK and hands it to the system installer, which asks a
  human; the container has already unpacked the new build and is about to exit
  so its launcher can start it. One message served both, so a Docker install
  sat under "Android is asking you to confirm…" — reported from a machine with
  no Android anywhere near it. `/api/update/status` carries `variant`; the page
  branches on it, and `UpdateDrawnTest` scans for the wording going back to
  being unconditional.
- **AND THE WATCHER GAVE UP EXACTLY WHEN THE UPDATE WAS WORKING.** The
  container exits mid-update by design, so the status request fails for a few
  seconds — and `watchUpdate` called `clearInterval` on the first failure,
  freezing the bar on whatever it had last read. It waits through the gap now,
  bounded by `MAX_UPDATE_POLLS`, and reloads the page once the server answers
  again, because everything on it came from the build that just went away.
- **PROMOTE WHAT THE LAUNCHER LAUNCHED, NOT WHAT THE BUILD CALLS ITSELF.** The
  version directory is named after the MANIFEST; the running process reports
  whatever was baked into it. Two names for one thing, from different places —
  and `promote` wrote the second into the file the launcher reads as the first.
  The moment they disagree, `active` names a directory that does not exist, the
  next boot finds nothing there and falls back to the build in the image: an
  update that appears to work and quietly undoes itself, which is close to
  undiagnosable from outside. In practice they agree, because both come from
  `versionName` — `SHARECARD_VERSION` is the documented way to make them
  differ, and it is what exposed this while watching a real update restart.
  `promote` reads the `trying` marker now, so the launcher is the only source.
- **THE INSTALLER IS TOLD THE VERSION; IT MUST NEVER READ IT OFF A FILENAME.**
  The downloader writes one fixed name, replaced each time, and that name has
  never carried a version — so the container's installer parsed nothing and
  fell back to a placeholder. The placeholder was the word "pending", which is
  also the marker file beside it, so the unpack made a DIRECTORY called pending
  and the marker could not be written: `pending (Is a directory)`. Every update
  failed. NOTHING CAUGHT IT UNTIL A REAL ONE WAS APPLIED, because every test
  until then called the unpacker directly with a version already in hand —
  which is exactly the step that was broken. `install` takes `(File, String)`
  now.
- **UNPACKED BUILDS LIVE BELOW THE MARKERS, NEVER BESIDE THEM.** A version is a
  name that came off the network; the markers are names this app chose. In one
  flat directory those namespaces collide, which is what the bug above turned
  into. `versions/<version>` means they cannot touch whatever a manifest calls
  a release, and `ServerReleaseTest` unpacks builds named after each marker to
  prove it.
- **AND THE DOWNLOAD SCRATCH DIRECTORY IS NOT THE ONE HOLDING THE BUILDS.** The
  downloader empties its directory before every attempt, so pointing it at the
  directory that also holds the unpacked versions and the markers would have it
  deleting them. `updates/download` sits below `updates/`, and a test asserts a
  download cannot remove a build or a marker.
- **ONE MANIFEST, TWO HALVES.** `latest.json` carries the APK at the top level
  and the server build under `server`. `Updater.Variant` decides which half is
  read. A second manifest would be a second thing to fall out of step, which
  this repo has already watched happen once. A version published before the
  server build existed has no `server` block, and the honest reading of that is
  "nothing here for you" — never the APK's url, which a container would
  download and fail to unpack.
- **THE SIGNING FLAG IS ANDROID'S ALONE.** It exists because Android refuses an
  APK signed with a different certificate and says only "App not installed".
  A server build has no certificate to match, so applying the flag to it would
  have refused every container update for a reason with nothing to do with it —
  and until the keystore secret existed, that would have been all of them.
- **A ZIP FROM THE NETWORK IS UNPACKED AND THEN EXECUTED, so every entry is
  checked against the destination.** An archive naming `../` writes wherever it
  likes; the bug is old enough to have a name and common enough to still be
  shipped. An entry that escapes ends the whole unpack rather than being
  skipped — a build that lies about its contents is not one to install the rest
  of. `ServerReleaseTest` proves it, and that test escapes only as far as its
  own fixture: an earlier version aimed three levels up and, when the guard was
  removed to show the test could fail, wrote a real file into `/tmp` that then
  failed the next run for the wrong reason.
- **THE ROLLBACK HANGS ON `promote` BEING LATE.** The launcher writes `trying`
  before running a pending build and never clears it; only a build that gets as
  far as SERVING clears it, from `Main` after the socket is bound. So a build
  that crashes on startup leaves the marker, and the next boot reads it, throws
  that version away and falls back — to the last good build, or to the one in
  the image, which is known to run because it is what shipped. A container
  cannot be bricked by an update it could not run, and `tools/check-launcher.sh`
  drives all thirteen cases including a real crashing build booted twice.
- **`fromAnyDevice` IS WHY THE UPDATE BAR IS NOT HIDDEN IN DOCKER.** The bar is
  hidden off the socket address because an APK installs on THIS device and a
  page on an iPad across the house cannot replace it — reported as "shows the
  update button, does nothing". A container update replaces the machine serving
  the page, which is the same machine whichever browser asked, and that machine
  usually has no browser on it at all — so hiding it from other devices would
  hide it from everybody. The server says which case it is; the page does not
  guess.
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
