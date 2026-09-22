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
- **AND THAT CHECK WAS LEAKING A ZOMBIE EVERY THIRTY SECONDS, BECAUSE PID 1 WAS
  THE JVM.** `launch.sh` execs the start script and that execs java, so nothing
  stood in front of the JVM. A `HEALTHCHECK` is `runc exec`ed into the container
  and the helper that starts it exits at once, so every finished check is
  REPARENTED TO PID 1 — and a JVM reaps only the children it forked itself. One
  defunct `bash` per interval, for the life of the container. FOUND IN AN HTOP
  OFF A REAL HOST and nowhere else: nineteen of them under the java process
  after about ten minutes, PIDs climbing, while every test here passed and the
  container reported healthy. Nothing visibly breaks until the pid limit is
  reached and it cannot fork at all, and `restart: unless-stopped` resets the
  count on every restart — a leak whose only symptom is a process table nobody
  looks at. `tini` is PID 1 now, in the image so that `docker run` is covered
  too, and `init: true` in `docker-compose.yml` beside it.
  **THE ENV VAR AND NOT THE `-s` FLAG, WHICH COULD NOT HAVE BEEN GUESSED.** With
  both in play tini is PID 2, and it WARNS when it is not PID 1 — into the log
  this app tells people to read for their PIN. Both `-s` and `TINI_SUBREAPER`
  silence it by registering tini as a subreaper, but `-s` is parsed inside
  tini's `#ifndef TINI_MINIMAL` block, so a build made that way passes `-s`
  STRAIGHT THROUGH to the launcher and on to the server, while TINI_SUBREAPER
  is read by `parse_env`, which is compiled either way. Ubuntu's tini 0.19.0
  accepts `-s` — measured here, not assumed — so that is a hazard the package
  avoids today rather than one it has, and the env var is preferred only
  because it cannot be wrong if that changes. `tools/check-docker-init.sh` asserts the shape of all of
  it — an init in front of the launcher, installed, the launcher still at the
  end, Compose asking too — and was shown failing against the files as they
  were, plus once per assertion. WHAT IT CANNOT DO IS WATCH A CONTAINER REAP;
  that is the first real `docker run`, as the Dockerfile has always been.

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
- **ONE RENDERER, TWO SERVICES, ONE OF THEM DRAWING NOTHING — AND `UpnpSource`
  HAD ONLY EVER ASKED HALF THE QUESTION.** Reported from the field: Spotify
  Connect to a WiiM Pro Plus makes a card and Qobuz Connect to the SAME BOX
  makes none. One code path and one device, so the difference could never have
  been the source; it had to be the reply. `GetPositionInfo` describes the
  TRACK on the transport and `GetMediaInfo` describes what the transport as a
  whole is playing, and [SonosSource] has asked the second whenever the first
  came back short since the first release — radio and line-in put the station's
  name there and nowhere else. `UpnpSource` never asked it at all, so a
  renderer answering "PLAYING" with an empty `TrackMetaData` was read as
  describing nothing and dropped, with the record sitting in the reply this app
  declined to fetch. THE SAME OMISSION APPEARS ONE FIELD OVER: `r:streamContent`
  is parsed by `Didl` and was read by Sonos and thrown away here. Both are
  fixed, both shown failing first. **THE SECOND ROUND TRIP IS GATED EXACTLY AS
  SONOS GATES IT** — only when the first reply would not draw a card — because
  a renderer that fully described its record must not pay for one, and a test
  asserts the second call is not made in that case (shown failing against an
  unconditional version).
- **AND `merge` MOVED INTO `Didl` RATHER THAN BEING COPIED.** Same move
  `Normalize.namesOverlap`, `Normalize.stripEdition` and `quality` each made on
  their second caller: two copies of a gap-filling rule is how one source
  learns a station's name and the next does not.
- **WHAT ACTUALLY SETTLES IT IS THE RAW REPLY, AND UNTIL NOW THERE WAS NONE.**
  Every line the UPnP diagnostics printed was this app's READING of a reply
  rather than the device's words, which is the fault that cost the Roon queue
  four releases. `/api/debug` now carries `GetTransportInfo`, `GetPositionInfo`
  and `GetMediaInfo` verbatim per renderer — BOTH metadata replies always,
  whatever the parse made of them, because the whole question is which of the
  two a Connect session fills in and printing only the short one would hide the
  half that answered. `GetTransportInfo` is there because "the box says
  STOPPED" and "the box says PLAYING with nothing in it" are two different
  bugs. THE FIX ABOVE IS UNVERIFIED AGAINST THE BOX THAT REPORTED IT — no WiiM
  is reachable from here, the tests drive a real `MockWebServer` and not a
  WiiM. Read `/api/debug` first on the next run.
- **CHROMECAST IS NOT A UPnP SESSION AND NO SOURCE HERE SPEAKS IT.** Cast is
  mDNS discovery and a TLS protobuf channel on port 8009; every source in this
  app is SSDP/SOAP, JSON-RPC, MOO or Android's own media sessions. Whether a
  LinkPlay box mirrors a Cast stream into its `AVTransport` is the box's
  choice and is not knowable from here — which is exactly what the raw reply
  above answers, in one line, on the first run. Nothing was built for it, and
  that is a report rather than a fix on purpose.
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
- **A HASH BEFORE A DIGIT IS THE WORD "NUMBER", BECAUSE MEDIAWIKI CANNOT PUT
  ONE IN A TITLE.** `#` is the fragment separator, so a record named with one
  is filed under the word: Big Star's "#1 Record" is at
  `/wiki/Number_1_Record`. `Normalize.text` dropped the hash as ordinary
  punctuation, leaving "1 record" to be matched against "number 1 record" —
  which `namesOverlap` then refused, correctly, because it anchors at the
  front. Reported from the field as a card with no blurb and no Wikipedia
  chip. **WHAT NAMED IT WAS THE ARTIST CHIP SITTING BESIDE IT WORKING**: one
  lookup, two halves, and only the half keyed on the TITLE failed — the free
  control experiment again, this time inside a single function. It is the
  `LIGATURES` lesson one character over, and the fix is in the same place: a
  character NFKD will not expand, silently dropped, is how a record ends up
  with nothing found and no explanation. **ONLY BEFORE A DIGIT**, because `#`
  is also how a key signature is written and "Prelude in C# Minor" must not
  fold to "prelude in c number minor". The knock-on is that a Pitchfork slug
  for such a record is now `…-number-1-record` rather than `…-1-record`;
  pitchfork.com is not reachable from here so which of the two they use is
  UNVERIFIED, and both were guesses.
- **THE YEAR ON THE CARD IS THE RECORD'S, NOT THE EARLIEST PRESSING IN AN
  ARBITRARY WINDOW OF FIVE.** Reported with a photograph: "#1 Record", a 1972
  album, drawn as **RELEASED 2003**. `musicBrainzRelease` asked for RELEASES —
  every CD, LP and remaster MusicBrainz holds — with `limit=5`, and took the
  earliest of whatever came back. Five is nothing for a record reissued over
  fifty years, and the search is ordered by TEXT RELEVANCE rather than by date:
  every pressing of one album scores the same, so which five arrive is
  arbitrary and the original is routinely not among them. A RELEASE GROUP is
  the record itself and `first-release-date` is MusicBrainz's own answer to
  exactly this question — no window and no arithmetic over pressings. The
  release search is KEPT BEHIND IT, because a release-group search result's
  shape is documented rather than observed (musicbrainz.org answers 403 to the
  CONNECT from here), so if that field is not carried the card is exactly as
  well off as it was and `/api/debug` says which of the two answered. The
  first row is still not taken on trust: the group's title must overlap, or a
  search for "#1 Record" dates the record from the "#1 Record / Radio City"
  twofer.
- **AND A WRONG YEAR WAS ON EVERY DISK FOR A WEEK, WHICH WOULD HAVE READ AS
  THE FIX NOT WORKING.** `extras` is cached for seven days and persisted, so
  fixing the lookup alone leaves the old answer on screen for every record
  already looked up. The stored entry carries `v` now and one without it is
  read as NO ENTRY — a miss, looked up again, overwritten in place. A version
  marker rather than a renamed namespace, because the shelf is one JSON file
  per namespace and a rename would leave the old file in the data directory
  for ever, unread. Bump it only when a stored answer would be WRONG rather
  than merely thin: `artistMbid` was simply absent from older entries and
  decodes to null, which costs one search and needs none of this.
- **"GENERALLY REVIEW RETRIEVAL IS POOR" WAS A COMPLAINT NOTHING IN THE REPORT
  COULD NARROW.** The year and the blurb both come from `Metadata`, and it had
  never said a word — so "Wikipedia has never heard of it", "it was asked the
  wrong question" and "it answered and this guard refused the article" arrived
  as one silence, which is the `Pitchfork.Outcome` lesson in the one lookup it
  had not reached. `Metadata.attempts()` names the search, the candidates it
  came back with and the reason each was turned down, and the ROWS rather than
  a count — "not in Wikipedia" and "it is right there and the title match
  refused it" are the same sentence otherwise. Three sources now share that
  section, so every line is prefixed with its own (`musicbrainz:`,
  `wikipedia:`, `pitchfork:`) and the page's heading is "Album lookups" rather
  than one source's name — the same rule the Roon and Lyrion queue attempts
  already follow.
- **AND THE TWO HOSTS ARE INJECTED NOW, WHICH IS WHAT MADE THE YEAR FIX
  PROVABLE.** `Metadata` takes `musicBrainzBase` and `wikipediaBase`, defaulted
  to the real ones and passed by nothing but a test. Both answer 403 to the
  CONNECT from this working environment, so the REQUEST THIS APP BUILDS is the
  part most likely to be wrong and was the part nothing could look at;
  `MusicBrainzYearTest` drives the whole lookup through a real `MockWebServer`
  and was shown answering 2003 before the fix and 1972 after. Same seam
  `LmsSource` takes for discovery, for the same reason.
- **ALLMUSIC STILL RESOLVES NOTHING, AND THAT IS THE DOCUMENTED DESIGN RATHER
  THAN A BUG.** Reported alongside the above as "no allmusic review", with a
  manual search finding the record immediately. The chip is a SEARCH link —
  their album ids are opaque (`…-mw0000459534`) and cannot be built from a
  name — so there has never been a review or a score behind it to be missing.
  Making it resolve means reading the id off their search page the way
  `QobuzAlbum` reads Qobuz's, which `Reviews` has called "a real option later"
  since it was written. allmusic.com is not reachable from here either, so it
  would be entirely unverified work. Put to the owner rather than guessed at.
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
- **THE WEBFONT WAS A RENDER-BLOCKING REMOTE STYLESHEET, AND IT IS SERVED FROM
  THIS APP NOW.** `index.html` pulled Manrope from fonts.googleapis.com, and a
  pending stylesheet blocks every script after it - so this LAN app, which needs
  no internet for anything else, was INERT without one. It stood unfixed for a
  long time and deliberately, as a product question: making the link async is
  the smaller diff and changes what the page looks like while it loads.
  **MEASURED IN A REAL BROWSER, BEFORE AND AFTER, ON THE SAME MACHINE** - which
  became possible only when a browser turned up in the working environment, and
  is the reason this finally moved:

      before, font reachable    first api request  265ms   interactive  284ms
      before, font stalled                      30063ms    interactive  NEVER
      after  (self-hosted)                        126ms    interactive  140ms
      after, google blocked                       120ms    interactive  150ms

  Not "the page is sluggish" - the page is NOT RUNNING. Twice as fast on a good
  network, and no longer dependent on one at all.
- **SELF-HOSTED RATHER THAN MADE ASYNC, WHICH IS WHY THE PRODUCT QUESTION WENT
  AWAY INSTEAD OF BEING ANSWERED.** Async keeps the third-party request and
  swaps the type mid-load; serving the font from the same origin the page came
  from is identical type, no third party, and works offline. THE PROOF IT IS
  IDENTICAL is a render comparison against Google's own copy in the same
  browser - the same sample in Latin, accented Latin, Cyrillic, Greek and
  Vietnamese, screenshotted per weight and hashed: **twelve comparisons, twelve
  identical**. What that harness does NOT show is six visually distinct weights
  (headless grouped them into two), so it proves EQUALITY rather than coverage
  of every weight; said here rather than left to be assumed.
- **ALL SIX SUBSETS AND ALL FIVE WEIGHTS ARE COMMITTED, AND `unicode-range` IS
  WHY THAT IS NOT EXPENSIVE.** Google served every subset, so shipping only
  latin would draw a Russian or Greek artist name in the system sans on the
  card - a regression for somebody, invisible from here. Thirty files is 366 KB
  in the repository; a browser fetched TEN of them for a Latin-and-Cyrillic
  sample, measured, because a subset is requested only when a character in its
  range is rendered.
- **THE LICENCE TRAVELS WITH THE FONT: `web/fonts/OFL.txt`.** Manrope is under
  the SIL Open Font License 1.1, which permits bundling and asks that the
  licence go with it. That file is the Manrope project authors' own, not
  anything this project had to write - which is worth stating, because the
  owner's first reaction was "I don't have an OFL licence file", and nobody
  needs to: it ships with the font.
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
- **AND THE ROOMS THAT ARE LEFT ARE ASKED AT ONCE, ONE THREAD PER SOURCE.**
  The empty-sweep fix removed the repeated DISCOVERY; what was left was the
  asking, and every one of those is a round trip to a device on somebody's wifi
  made one after another. MEASURED against fakes shaped like a real household -
  Roon with one zone at 120ms, Sonos with three at 200ms, UPnP with two at 250ms
  - `rooms()` took **1259ms** and `nowPlaying(null)` **1225ms**, nearly all of
  it a thread doing nothing. Asked at once it is the slowest SOURCE rather than
  the sum.
- **THE GRAIN IS THE SOURCE, AND THAT IS THE WHOLE SAFETY ARGUMENT.** A thread
  per ZONE would be faster still and is not safe to do blind: `Household` keeps
  its topology and its `sweptAt` in plain `@Volatile` fields with no lock, so
  two threads asking two rooms of one household can both read a stale sweep time
  and both run a discovery sweep - a multicast sweep of the house twice for one
  question, which is the exact cost the rule above exists to remove. Per source,
  each source is touched by one thread at a time, which is precisely the
  invariant it has always had. `SourcesParallelTest` asserts the ceiling and was
  shown failing against a thread-per-zone version.
- **AND THE TWO CALLERS ASK THROUGH DIFFERENT FUNCTIONS, WHICH ALMOST GOT
  COLLAPSED.** The chooser's rooms go through `inZone`, which drops an answer
  describing nothing; the ladder's go through `ask`, which keeps it so that
  `candidates` is non-empty and the "N answer(s), none of them describing a
  record" line is logged. Both end in the same null, so merging them onto one
  helper deletes that diagnostic and no test fails. Caught by re-reading the
  diff, not by the suite.
- **THE TEST FOR IT IS A LATCH, NOT A STOPWATCH — AND THE FIRST CUT WAS
  DECORATION.** A wall-clock assertion on a shared runner is a flake, and "it
  was quick" is not the property that matters: every source must ARRIVE before
  any may leave, which is impossible serially and immediate in parallel. The
  first version waited on that latch and then ANSWERED ANYWAY, so a serial
  implementation merely took ten seconds and passed every assertion. Returning
  null on the time-out is what made it load-bearing. Found by running it against
  the serial code rather than by reading it, which is this repository's rule
  applied to its own new test.
- **AND TWO ORDERING TESTS ARE GUARDS RATHER THAN PROOFS, SAID PLAINLY.**
  Reversing the order answers are collected in fails NEITHER of them, because
  `rooms()` re-indexes by zone id and the ladder's tie-break asks
  `sources.indexOfFirst` for a POSITION rather than reading the candidate list's
  order — so arrival order cannot reach the decision by any path that exists
  today. They are kept for the refactor that makes it reachable, and the comment
  says which of the two things it is, because a test that cannot fail is
  normally decoration and this is the exception worth naming.
- **`no-store` AND `no-cache` ARE OPPOSITES, AND THE PAGE WAS SERVED THE WRONG
  ONE.** Every asset carried `Cache-Control: no-store`, which forbids keeping a
  copy at all - so the whole bundle came down again on every visit. MEASURED
  against the real server in a real browser: **263 KB and 14 requests, identical
  on a revisit, zero 304s.** The requirement behind that header is real and
  unchanged - the page is versioned by the APK rather than by its URL, so a
  browser running yesterday's JavaScript against today's API is a genuine way to
  break after an update, on the one device nobody can see. But `no-cache` is the
  accurate word for it: keep a copy, and ASK before every use. With an ETag
  beside it, asking costs a 304 with no body. Measured after: **74 KB on a
  revisit**, with app.js, style.css and sharecard.js answering 304 and nothing
  else changing. The tag is over the BYTES, not the version - `SHARECARD_VERSION`
  is set by hand and two builds could share it, and an asset inside an APK has
  no useful mtime.
- **AND THE FONTS ARE STILL RE-DOWNLOADED, WHICH IS RECORDED AS UNSOLVED RATHER
  THAN DRESSED UP.** Chromium fetches every woff2 in full on every visit, never
  offering an `If-None-Match`, while app.js on the same page revalidates
  correctly. Two fixes were tried and BOTH CHANGED NOTHING, measured each time:
  `public, max-age=604800` on fonts and images, and adding the `Date` header the
  writer had never sent. The max-age split was REVERTED rather than shipped with
  a comment crediting it for a fix it did not make; `Date` was kept on its own
  merit, because HTTP/1.1 requires an origin server to send one, and is
  described as correctness rather than as a saving.
  **AND ONE PROBE ALONG THE WAY WAS WORTHLESS AND NEARLY BELIEVED.** Fetching a
  url three times from the page and counting network hits "proved" fonts were
  uncacheable - until the same probe was run against `app.js`, which is KNOWN to
  revalidate, and showed 3 of 3 as well. The control is what saved it. A
  navigation waterfall is the only measurement here that means anything, because
  it is also what a person actually does.
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
- **THE SUGGESTION TYPE WAS MEASURED, AND IS NOT ANY MORE — THE LAYOUT MOVED
  UNDERNEATH IT.** `fitSuggestions()` measured the longest label on a canvas
  and set one font size on the whole row, because the chips were full width,
  single line, and at any fixed size the longest clipped while the shortest
  floated in nothing. It was the right answer to that layout. A suggestion is a
  ROW now — the record on the left, a review pill on the end — so nothing is
  full width, and the label WRAPS rather than being shrunk: "The Chemical
  Brothers · Live in Leicester 1995 (1995)" set at 9.5px to stay on one line is
  smaller than the credit under the card. With the text wrapping there is
  nothing left to measure, and the resize listener that existed only to re-fit
  went with it. THE HEIGHT WRAPPING COSTS CAME OUT OF THE ACTION ROW, which is
  why those became pills in the same change.
- **THE ACTION ROW IS PILLS, THE SAME 44px AS A REVIEW CHIP, AND WITH NO
  ICONS.** Asked for: "pill shaped and sized the same as the review pill
  buttons". The icons went too, and that is not tidying — a pill is wide and
  shallow, so an icon beside the label eats the width the label needs, and at
  320px "Discord Now Playing" came out as "Discord Now…". That label is the
  user's own webhook name and this page has a standing rule never to clip it.
  Without the icon it wraps to two lines and fits, measured at 320 and 390.
  `icon()` is untouched and still used by the header, the update bar and "Find
  my speakers" — that one is alone in its row and has the width for one.
- **DOWNLOAD IS DRAWN ONLY WHERE THE BROWSER WILL NOT DO IT.** The card is an
  `<img>`, so iOS long-press gives Save to Photos and a desktop right-click
  gives Save image as — on both, the button is a third way to do something the
  platform already does better, and the row it sits in is the height the
  suggestions need. Asked for as exactly that: remove it on iOS and on the
  container. The ANDROID app keeps it, because its WebView has no long-press
  save at all. `/api/setup` carries `variant` for this — `/api/update/status`
  has carried it all along but is fetched for the update bar and may not have
  landed when the first card is painted. "android" is the DEFAULT, because a
  page that has not been told what it is running on should keep a control
  rather than remove it on a guess. Where the button goes, the hint says what
  to do instead: a control that is simply absent teaches nobody.
- **A SUGGESTION OFFERS SOMEWHERE TO READ ABOUT IT, AND ALLMUSIC IS THE ONE
  THAT COSTS NOTHING.** Asked for: an album review link on each suggestion,
  "either wiki, pitchfork or Allmusic". AllMusic's is built from the two names
  with no lookup at all, which is what makes three of them free — Wikipedia's
  is the ARTICLE the blurb came from and Pitchfork's is a real review, and
  neither exists for a record nobody has played, so both are a request apiece:
  six requests to two rate-gated hosts to decorate something nobody has tapped
  yet. It is a search link exactly as the card's own AllMusic chip is, so it
  promises no more than that chip does, and switched off in Settings → Reviews
  there is no chip at all.
- **THE VERSION IS AT THE FOOT OF THE SETTINGS MENU.** It was only ever in
  `/api/debug`, which is a wall of facts somebody has to be told to open — so
  "which version are you on" was the first line of every bug report. One quiet
  line on the screen people already go to, beside a link to the project page
  where the release notes and the Docker commands are.
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
- **A REVIEW CHIP AND A SERVICE CHIP NEVER SHARE A LINE.** One grid held both,
  so whatever the reviews left of a line was filled by the first service —
  Qobuz on the end of the review row, Spotify and Bandcamp starting a row of
  their own beneath it. Two chips that do entirely different things shared a
  line, and WHICH ones did depended on how many review sources happened to be
  switched on that day. Reported as exactly that. `.links` is the column now
  and each `.links-row` is the four-column grid it used to be, so each kind
  takes as many lines as it needs and the other starts fresh. An empty row is
  not added at all, because a gap is still a gap. Verified with five reviews
  and seven services at 390px: two lines each, nothing mixed, and nothing
  scrolls at 780 or 844 tall.
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
- **AND A REPORT NOBODY CAN OPEN IS THE SAME FAULT ONE STEP OUT.** Reported
  as "The device section - where what??", which is exactly the right question:
  there was no way in. `offerDiagnostics()` draws its button only when
  discovery has FAILED (`noPlayers && !notices.length`) — right for a "why
  can't it see my speakers" control, and it means an app that is finding every
  room perfectly offers no route to the report at all. The only way was typing
  `/api/debug` into a browser, which is the identical complaint the version
  line at the foot of the settings menu was added to fix. It is a menu row now,
  beside Services, Reviews, Zones and Webhooks, and it calls the SAME `report()`
  the failure path always has — a door, not a second renderer.
  **`DiagnosticsDrawnTest` MADE THIS HARDER TO SEE RATHER THAN EASIER**: proving
  the page can draw every key reads as "the report is fine", while nobody could
  reach it. Drawn and reachable are two different claims and it now asserts
  both.
- **AND THE `claimStage` SCAN NAMED ONE SCREEN, WHICH IS HOW A NEW ONE SLIPS
  PAST.** It asserted `showSettings` by name — the mechanism rather than the
  invariant, the same mistake as the scan that named `repeat(4` and broke on a
  change that kept four across. Every ASYNC screen is scanned now, which is
  where the hazard actually is: a synchronous screen draws in one go and has no
  window for a `load()` to overtake it, and scanning every `show*` swept in
  render helpers (`showNode`, `showChooser`, `showUpdate`) that take a value
  and paint it and have no stage to claim.
- **AND THE SCAN LOOKED ONE WAY ONLY, WHICH LEFT TWO DEAD READS IN THE ROOMS
  SECTION SINCE THE DAY IT WAS WRITTEN.** `DiagnosticsDrawnTest` asked "is
  everything SERVED also DRAWN", which is the fault it was written for. The
  converse was never asked, so the page read `z.ip` off every zone row and
  nothing in any version of `Diagnostics` has ever put an `ip` - every row of
  every report anybody has ever read said **"Stereo Fives (undefined)"**.
  `z.raw` was the same fault with a worse consequence: the whole "Raw reply"
  section was gated on it, so a section whose own comment calls it "the section
  to send on when a card comes out wrong for one source and right for another"
  had NEVER ONCE BEEN DRAWN. Both are invisible in a running app - `undefined`
  reads as a missing value, and a section that never renders reads as a section
  with nothing to say. Found in a field dump where one room appeared twice
  under two sources, which is precisely the case the missing field distinguishes.
  **AND THE FIRST CUT OF THE NEW SCAN LIED IN THE OTHER DIRECTION**: it matched
  `z.` anywhere in the page, and three other places map a zone from
  `/api/zones` and also call it `z` - rows that genuinely carry `uid` and
  `enabled`. So the debug row is named `debugZone`, the scan keys on that, and
  it refuses to find NOTHING, because a scan quietly matching nothing is how
  one becomes decoration.
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
- **THE PRESS IS READ AS RECORDS, NOT AS ARTICLES, AND THAT IS THE WHOLE
  LEGAL POSITION.** Asked for as "what's good right now… as album cover art and
  when tapped opens to the review sources as links", with "I just don't want to
  breach copyright of articles" attached. So what `Editorial` takes out of a
  feed is AN ARTIST AND AN ALBUM: the headline identifies the record and is then
  thrown away, the article is never fetched, and the only thing that survives is
  a link with the publisher's name on it. A feed's `description` is deliberately
  NOT READ AT ALL — not to shorten, not to excerpt, not for a hover — because
  the safest way not to publish somebody's writing is not to hold it, and
  `NewMusicDrawnTest` asserts the word does not appear in the code rather than
  trusting it.
- **A TITLE ONLY YIELDS A RECORD WHERE THE PUBLISHER WRITES IT TO A SHAPE, SO
  TWO OF THE FOUR FEEDS ASKED FOR WERE LEFT OUT.** Pitchfork's album feed is
  "Artist: Album" and NME's is "Artist – 'Album' review: …", both declared per
  feed in `Editorial.FEEDS` rather than guessed. The Quietus and Bandcamp Daily
  mix features, lists and interviews into the same feed, so their headlines are
  prose — "The Strange World of…" names no record, and guessing one out of it
  puts a WRONG SLEEVE UNDER A RIGHT REVIEW, which is this repo's oldest rule
  wearing another hat. A shape that cannot be read is skipped and `attempts()`
  says so. `mustContain` is the second guard: a publisher putting a news item in
  a review feed is ordinary, and the link's own path is what tells them apart.
- **THE COLON IS THE FIRST ONE, BECAUSE AN ALBUM CARRIES ONE AND AN ARTIST DOES
  NOT.** A subtitle, a reissue, a deluxe edition named after itself — all
  ordinary; an act with a colon in its name is rare enough that nobody here can
  name one. Splitting at the LAST put the album's own subtitle on the end of the
  artist, which is a name that matches nothing and a row of links about nobody.
- **THE REVIEW A RECORD CAME FROM REPLACES THE LOOKUP'S GUESS; IT DOES NOT SIT
  BESIDE IT. A RENDER FOUND THAT.** Added as an extra chip, a record out of
  Pitchfork's own feed drew TWO chips both reading "Pitchfork review" — one the
  publisher's link, one a URL this app built from an artist and an album slug —
  with nothing on the row to tell a reader which was which. The publisher's is
  the one that is certainly right, so it takes the review slot. AND THE CHIP IS
  NAMED BY WHOEVER WROTE IT: the label was the literal `"Pitchfork review"`,
  true for as long as a review link could only come from the score lookup, so an
  NME review drew under Pitchfork's byline. That is the `bioSource` rule —
  "named by whoever the blurb came from rather than hard-coded" — made in the
  one place it had not reached, and it took a browser to see. `readAtName` is
  one of `FEEDS`' own names, never a string off the wire, which is what keeps it
  inside a quarter of a phone (see `LinkChipsTest`).
- **A SLEEVE SIZED BY ITS WIDTH CANNOT GIVE WAY, AND WHAT GETS CLIPPED IS THE
  RECORD'S OWN NAME.** `.newone-art` was `width: 100%` with `aspect-ratio: 1/1`,
  which derives the HEIGHT from the width — so the picture was 240px tall
  whatever room the stage had. Measured at 320x700: stage 245px, panel 223, and
  the album title and artist laid out at y=390 inside a stage that ends at 368
  and clips. A detail screen that never says which record it is about, shipped
  in 1.0.2 and invisible in every test here, because nothing reads a layout.
  Driving it from the HEIGHT inverts it — the width follows the ratio, and
  `flex: 0 1 auto` with `min-height: 0` lets the picture shrink while the two
  lines of text keep theirs. Same trade the card makes on a short screen and for
  the same reason: the picture is the one thing that can be smaller without
  anything being lost. Re-measured at 320, 360, 390 and 430 with both lines
  inside the stage every time, and `NewMusicDrawnTest` scans the declaration —
  PER DECLARATION, not per substring, because `max-width: 100%` carries
  `width: 100%` inside it and the first cut of that scan failed on the fix.
- **THE PICTURE BESIDE AN ARTICLE IS NOT THE RECORD'S SLEEVE.** Reported on
  the first real run as "no album artwork", and it looked like two bugs: NME's
  tiles drew the placeholder because its feed carries no image this could use,
  and Pitchfork's drew a BROKEN IMAGE because its feed carried one that then
  failed to fetch. One cause underneath. What a publisher puts at the top of a
  review is a press shot, a live photo or a collage, and a press shot under an
  album title is a confident wrong answer — the thing this repo refuses
  everywhere else. The sleeve is resolved from the RECORD now, out of the same
  Deezer API the new-release list already comes from, and the feed's own image
  is kept only for a record Deezer does not carry. THE SEARCH IS LOOSE AND THE
  CHECK IS STRICT, as every lookup here is: the query is the two names plainly
  and both have to overlap on the ROW, because the first row is not the answer
  — `QobuzAlbum.pick` and the Deezer artist search are already written from
  that lesson.
- **THE ART PARAMETER IS `u`, AND THE SECOND PLACE THAT BUILT THE STRING BY
  HAND WROTE `url=`.** Every Discover sleeve asked for a parameter `artwork()`
  does not read, was answered 400 before `ArtProxy` was ever called, and drew a
  broken image — twelve a screen, on a feature whose whole point is cover art.
  It shipped in 1.0.2 and survived TWO rounds of "no album artwork", because
  the sleeve LOOKUP was what got suspected both times, and by 1.0.4 the lookup
  was working perfectly: `/api/debug` said "sleeves -> 10 of 11 resolved from
  the record" over a grid of broken images.
  **WHAT NAMED IT WAS THE DIAGNOSTICS BEING EMPTY.** The report had no "art"
  section at all — and `ArtProxy` notes every outcome it has, refusals
  included, so no notes means it was never reached. The ABSENCE of the report
  was the report, and it is the only thing that separated "the URL is wrong"
  from "the host was refused". Every art URL goes through `artLink` now, and
  `NewMusicDrawnTest` asserts that as an invariant — every `"/api/art?…="` in
  `CardApi` is the same one — rather than naming the routes somebody thought
  of, which is what the old assertion did and why it did not cover the third.
- **A PART-FULL ROW OF CHIPS SITS IN THE MIDDLE.** A fixed four-column grid
  left-aligns whatever it holds, so three review chips drew three-across with a
  quarter of the row empty on the right and the block reading as if it had
  slipped sideways. Reported as "centre the source and review buttons". A grid
  CANNOT centre its own tracks while they are `1fr` — they fill by definition —
  so the row is flex with a quarter-width basis: four fill it exactly and
  anything fewer is centred. Every chip is still one size and a label still may
  never set it. AND THE TEST THAT BROKE ON THIS WAS ASSERTING THE MECHANISM:
  it named `repeat(4` and failed on a change that kept four across. Assert what
  must be TRUE — four across, a quarter each — not how it is done.
- **AN EMPTIED ROW STILL CARRIES ITS MARGIN, AND THREE OF THEM IS A BLACK
  BAND.** `clearCardRows()` empties the action row and the room caption and
  HIDES the links and suggestions — but `display: none` was only ever on the
  two it hides, so the other two kept 14px and 10px of top margin plus their
  own line boxes on every screen that has no card. Under the Discover grid that
  is dead space at the bottom of the phone, reported as "remove big black
  section at bottom of the screen", and it was equally there under the chooser
  where nobody had noticed. The rule already existed one element over —
  `.hint:empty` and `.err:empty` were added when an empty paragraph pushed the
  card and its caption apart — so this is that rule reaching the rows it had
  missed. Measured at 390x780: 62px of dead space under the stage before, 26px
  after, and the 26px is the hint itself.
- **A SLEEVE IS ASKED FOR AT TILE SIZE, AND THAT IS A CACHE DECISION.**
  Deezer's `cover_big` is 500px and its `cover_xl` is 1000px; a tile is about
  115px on a phone and the record a tile opens onto is 240px, so xl is four
  times the bytes for a picture nothing draws that large. Twelve of them at
  once is what made `ArtProxy`'s shelf too small to hold one screen.
- **A CACHE SIZED FOR THE OLD SCREEN DOES THE OPPOSITE OF ITS JOB ON THE NEW
  ONE.** `ArtProxy` held EIGHT pictures, which was right for as long as the
  only picture on the page was the card — one in flight, the one on screen, a
  couple behind it, and its comment said exactly that. Discover asks for
  TWELVE at once, so every visit evicted everything the last visit had fetched
  and downloaded the lot again. Invisible, because the tiles still drew. WHEN A
  SCREEN IS ADDED, GO AND LOOK AT WHAT THE OLD ONES SIZED THEMSELVES FOR.
- **THE WHOLE DISCOVER SCREEN IS REMEMBERED, AND NOT DOING SO WAS THE EMPTY
  SWEEP AGAIN.** Opening it cost a ListenBrainz window, two RSS feeds, a Deezer
  list and a sleeve lookup per record — every time, including tapping Playing
  and tapping back. Measured against the real server: 0.97s cold and 0.001s
  warm, with `?refresh=1` going back to the network at 0.86s. THE KEY IS THE
  HISTORY, FOLDED, because "based on your listening" has to follow the
  listening: a record played since the last look reshapes the screen at once
  rather than at the end of a TTL. TWO SHELVES, because they go stale for
  different reasons — the screen is a this-week question and is held in memory
  for an hour, while a record's cover does not change and is written to disk
  for a week, which is what stops the same twelve lookups being paid again
  tomorrow. `forget()` throws away the screen and NOT the sleeves.
- **AND A BARE FUNCTION HANDED TO AN EVENT IS CALLED WITH THE EVENT, WHICH
  UNDID ALL OF THAT REMEMBERING.** `tabNew.addEventListener("click",
  showNewMusic)` and `back.onclick = showNewMusic` both read as "call this when
  tapped" and are not: the browser passes a MouseEvent as the first argument.
  `showNewMusic(force)` takes `force`, and an event object is TRUTHY — so
  merely OPENING the Discover tab, and coming back to it from a record, each
  asked for `?refresh=1`, which makes the server `forget()` the screen and go
  out to ListenBrainz, two RSS feeds, Deezer and a sleeve lookup per record.
  Reported as Discover being slow to populate and appearing to fully reload on
  the way back. **The cache was working perfectly the entire time and being
  thrown away on every tap** — an hour of remembering undone by two missing
  brackets, and invisible because the screen still drew.
  `HandlerArityTest` asserts the INVARIANT rather than those two lines: a
  parameterless function is safe to hand over bare and one with a parameter is
  not. `showSettings`, `addWebhook` and `startUpdate` are all passed bare and
  all take nothing, which is why the scan passes rather than being written to
  exclude them.
- **AND REFRESH ON DISCOVER WAS ALREADY RIGHT — CHECKED RATHER THAN ASSUMED.**
  Asked whether it also refreshes zones: it does not. The button branches on
  the lit tab, `/api/new` touches no source and runs no sweep, and driving it
  in a real browser showed the tab clicks sending `/api/new` and Refresh
  sending `/api/new?refresh=1` and nothing else at all.
- **REFRESH REFRESHES THE SCREEN YOU ARE LOOKING AT.** It called `load(true)`
  unconditionally, so pressing it on Discover threw away the sleeves and drew
  the card — which reads as the button navigating rather than refreshing, and
  left Discover with no way to ask again at all the moment it started
  remembering its answer. A cache and a Refresh button are one change, not two:
  whatever you start remembering, something has to be able to forget.
- **THE WALL OF SLEEVES IS FULL-BLEED; THE CARD AND THE ONE RECORD ARE NOT.**
  Reported as "not using the full screen, doesn't need to be inside a window".
  Measured at 390x780 before: 16px of `.wrap` padding, a 1px border and 10px of
  stage padding EACH side, so 336px of a 390px phone reached the grid and a
  tile was 104px. After: 0px gutter, 370px of grid, a 115px tile. The frame is
  what a CARD wants — it is one picture and the border is its edge — and a list
  of records is the opposite: the sleeves ARE the screen. So the rule keys on
  the GRID alone, and one record opened from a tile keeps the frame, because it
  is the same kind of object as the card on the other tab. The old warning
  against `flex: 1 1 auto` here still stands for `.choosing`: what it guarded
  was a tall BORDERED box with its contents floating in the middle, and with no
  border and the grid top-aligned there is no panel edge left to reveal.

  AND IT TOOK THREE GOES, EACH TIME BECAUSE SOMETHING SMALLER WAS LEFT. First
  the border and the 16px page gutter came off; then 10px of stage padding was
  still a mount round a picture, and 60px sat underneath. Measured at 393x852
  with a 34pt home indicator: 26px of that was the caption and 34px was the
  inset. The inset was the interesting half — held OUTSIDE a scrolling element
  it is a band the content can NEVER reach, however far you scroll. Moved
  inside the scroller as its own `padding-bottom`, the sleeves run through it
  and it is blank only at the very end of the list, which is where a home
  indicator belongs. The caption went with it on this screen alone: what it
  explained was a grid of pictures with borders that are visibly buttons. A
  SCROLLING WALL IS NOT A COLUMN OF ROWS — a column finishes, so page padding
  keeps it off the edges and reads as margin; a wall is clipped at both ends by
  definition, so the same padding reads as a frame. 0px each side, 0px under
  it, and the grid starts at the tab underline.
- **NEITHER FEED HAS BEEN REACHED FROM HERE.** The proxy answers 403 to the
  CONNECT for pitchfork.com and nme.com, checked rather than assumed, so the
  shapes are the documented ones pinned as fixtures and the socket is kept out
  of `parse`. Same posture as Lyrion and Roon: treat the first real run as the
  verification, and read `/api/debug` first — the feed reads land in the same
  section as the rest of Discover.
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
- **AND IT ANSWERED — 400 — AND THE STATUS STOPPED ONE WORD SHORT.** Off a real
  network: `listenbrainz(a2eb319d-…) -> HTTP 400, falling through to Deezer`,
  with the MBID resolved and Deezer picking up behind it exactly as designed.
  That is the class comment's own prediction coming true ("A wrong one is a
  400, which is why Deezer is behind it rather than beside it"), so the
  UNVERIFIED `ALGORITHM` string is wrong. What the note could NOT say is WHICH
  parameter — and the answer was in the response body, which `fetch` threw away
  on every non-2xx (`response.code to null`). **THE BODY OF A REFUSAL IS THE
  THING THAT SAYS WHY.** Same fault as the Roon invoke that was never read, and
  the same fix as the notes carrying `reply.toString()` rather than this app's
  reading of it. It is peeked and BOUNDED (an error page is not necessarily
  small and this runs on a phone), flattened to one line and capped, and an
  empty body still reads exactly as it always did.
- **`ok` IS WHAT SEPARATES AN ANSWER FROM A REFUSAL, NEVER THE BODY BEING
  NULL.** That was the old meaning, and the moment a refusal started carrying a
  body, `text()` — which is what MusicBrainz and Deezer read through — would
  have handed a 404 page to a JSON parser as though it were a release list. A
  test drives exactly that.
- **THE ALGORITHM STRING IS STILL NOT SETTLED, AND THE APP THREW AWAY THE
  ANSWER TWICE.** Both hosts were re-checked rather than assumed and the proxy
  still refuses them outright, so this cannot be settled from here. What CAN be
  done from here is carrying ListenBrainz's own words back, and that took two
  goes: the first release did not read a refusal's body at all, and the second
  read it and then CUT IT OFF ONE WORD SHORT. Off a real network the note read,
  in full:

      listenbrainz(88679ca2-...) -> HTTP 400 (<!doctype html> <html lang=en>
      <title>400 Bad Request</title> <h1>Bad Request</h1> <p>1 validation error
      for SimilarArtistsViewerInput<br>algorithm<br> value is not a valid
      enumeration member; permitt...), falling through to Deezer

  `permitt` is where `MAX_REASON` fell, which is the exact word that introduces
  the list of values the request needs. TWO CAUSES AND ONLY ONE OF THEM WAS THE
  NUMBER: **88 of those 200 characters were markup** - doctype, html, title, h1
  and p - so nearly half a budget meant for a sentence was spent on tags.
  `Similar.unmarkup` strips them, and the cap is then large enough for a LIST
  rather than for its heading.
  **AND THE TEST FOR IT MUST NOT OVERCLAIM.** The observed body ends at
  `permitt`; what a pydantic enumeration error lists after that has not been
  seen, so the fixture says which half is real and asserts only the two things
  that are certain - the markup does not eat the budget, and a list survives the
  cap. Raising the cap alone proved nothing until the fixture carried two values
  rather than one: with the markup gone, 200 was enough for the fixture and the
  new number was decoration, by this repository's own rule. Both halves are now
  shown failing separately.
  **THE STRIP ONLY RUNS ON SOMETHING THAT ANNOUNCES ITSELF AS MARKUP**, because
  an error message may legitimately contain an angle bracket ("expected
  <artist>"), and deleting the words this exists to carry is the expensive way
  to be wrong. An unclosed `<` is kept literally rather than swallowing the rest
  - a scan with no bottom is how a body silently becomes empty.
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
- **THE INDEX STEP WAS THE SILENT ONE, AND IT IS THE STEP THAT MATTERS
  MOST.** Reported from the field as a report that read, in full:
  `…/kelly-lee-owens-dreamstate/ -> page read, NO SCORE IN IT` and
  `recent reviews (30): no "Dreamstate"`. The constructed URL was RIGHT — that
  is the real review page — and the step that runs BEFORE both of those, the
  index, which is the ONLY place the score lives, said nothing whatsoever.
  `fromListing` had two bare `return null`s: the index being unusable, and the
  record not being in it. Those are one silence and two completely different
  fixes, which is the `Pitchfork.Outcome` rule being broken one function away
  from where it was written.
- **THE REASON IS CACHED WITH THE ANSWER, NOT LOGGED WHERE IT WAS FOUND.** A
  bare `List<Listed>` cannot carry why it is empty, and noting at fetch time is
  worse than useless here: the index is fetched once an hour and shared by every
  album, so the note appears for one lookup and is missing from the next fifty
  — a reader cannot tell that silence from a success. `Index` carries the
  reviews AND the reason, so every lookup explains itself, cached or not. The
  four states are distinct on purpose: not fetched at all, no preloaded state
  (Pitchfork moved its blob — a fix in THIS app), would not parse, and parsed
  but held nothing.
- **AND THE INDEX IS FETCHED ONCE PER LOOKUP, NOT PER SPELLING.** The ladder
  asks it for the full title and again with the edition stripped, so noting
  inside that step printed an unreachable index twice for every deluxe edition.
  A report that repeats itself is one people stop reading. The failure is said
  once; a record not being there is said per spelling, because those are
  genuinely different searches.
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
- **AND THE SUGGESTIONS GET THAT UPGRADE TOO, WHICH THEY DID NOT FOR FOUR
  RELEASES.** Photographed: a tap on "U2 · Rattle And Hum" landing on
  qobuz.com's DOWNLOAD STORE — "Results for U2 Rattle And Hum, 1-60 of 1000
  albums", the first of them by somebody called ItsLee. That is what a Qobuz
  search link does, and it is the failure the rule above already exists for.
  The CARD's chip had been upgraded to a real album link since that was first
  reported; the suggestion chips had not, because their URLs are built on the
  server in one go — correct, the encoding rules live there — and nothing went
  back to resolve them. So the row whose whole purpose is to send you somewhere
  new was the one place still landing on a shop's search page. `upgradeSuggestions`
  runs after the row is drawn, never before it, and only for the service the
  chips actually point at. CONFIRMED FROM THE FIELD SINCE:
  "suggestions are reliably opening to the relevant streaming service". Worth
  recording, because that row landed on a shop's search page for four releases
  and "reliably" is the word that says the upgrade runs every time rather than
  when a lookup happens to resolve.
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
  - **A SEARCH RESULT IS NOT THE ALBUM. IT IS A ROW THAT OPENS ONTO IT — AND
    THAT WAS THE WHOLE BUG.** Four releases of this feature never queued
    anything, and the diagnostics added for it printed the answer in two
    consecutive lines off a real Core: `"The Offspring Ignition" matched
    Ignition / The Offspring`, then `no action_list row on the album screen:
    Ignition / The Offspring [list]`. Read together they say it plainly — the
    search matched perfectly, and browsing the matched row did not open the
    album's own screen, it opened a screen holding ONE row, and that row was
    the album again. The app stood one level above the record the entire time
    and reported it as Roon offering no actions, which is why it read as a Roon
    problem rather than an off-by-one in a walk. It descends now, bounded by
    `MAX_DESCENT`, because a walk with no bottom is a hang on a request thread.
    **THE DESCENT IS NARROW ON PURPOSE**: `pickSameRecord` takes a row only
    when exactly one on the screen can be opened, is not itself an action list,
    and carries the name we arrived with — so a screen full of TRACKS ends the
    walk rather than queueing track one, and two records sharing a title end it
    rather than being guessed between. Tests both ways: the descent shown
    failing when removed, and the guards shown failing against a reckless
    "first openable row".
  - **THE DIAGNOSTICS ARE WHAT SOLVED IT, AND THAT IS THE ARGUMENT FOR THEM.**
    Three rounds of this feature were spent reasoning about a protocol nobody
    here can reach. The round that fixed it did no reasoning at all: the app
    printed which step it stopped at and what Roon actually sent, somebody
    photographed it, and the answer was in the first two lines. Build the
    report before building the theory.
  - **THE SEARCH IS A LADDER, NOT A GUESS, AND THE RAW REPLY IS IN THE
    DIAGNOSTICS NOW.** Reported as U2's "Rattle And Hum" — in the library, on a
    Roon card — falling through to a streaming search, with "this must work"
    attached. The search is the ONE step whose input this app invents, and
    which string finds a record in somebody else's library cannot be settled
    from here, so `searchQueries` tries the forms in order of how specific they
    are and stops at the first that resolves: act+album with the edition
    stripped, the album alone (an artist is extra words to fail on, and the act
    is still checked on the ROWS), then both again exactly as the suggestion
    spelled them. A record Roon has still costs one request, which is the same
    bargain the Pitchfork ladder makes. AND the notes carry `reply.toString()`
    now, not this app's reading of it — when the reading is the thing that is
    wrong, an interpretation is precisely the wrong thing to be shown, and one
    screenshot of /api/debug should settle any of this.
  - **A ROW THAT NAMES NOBODY IS MATCHED ON ITS TITLE; ONE THAT NAMES SOMEBODY
    ELSE IS STILL REFUSED.** Roon's subtitle is not guaranteed to be the
    artist — a box set, a soundtrack, something filed under Various Artists —
    and demanding it turned a library that HOLDS the record into "not in your
    Roon library". The loosening stops exactly there, and the test for where it
    stops is the pair this repo has already been burned by: "Cult" by
    To/Die/For against Static-X's record of the same name. A blank subtitle
    contradicts nothing; a wrong one contradicts everything. Two blank
    candidates is a coin toss and neither is taken — no queue beats the wrong
    record in somebody's queue, which is the one failure this feature must
    never have. Both sides are folded through `stripEdition` and
    `primaryArtist` too, because the row is the LIBRARY'S spelling and the
    album is DEEZER'S.
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
- **A CACHE MAY NEVER BREAK THE THING IT CACHES, AND CI IS WHERE THAT RULE HAD
  NOT REACHED.** The image job went red on `ERROR: failed to solve: failed to
  reserve cache` — the buildx layer cache, exported to the GitHub Actions cache
  service, refused. Everything the job exists to do had ALREADY SUCCEEDED by
  then: both architectures built, `:latest` and `:1.0.15` pushed to ghcr.io,
  `#43 DONE 4.4s`. The red check was a cache.
  **WHAT MADE IT LOOK LIKE A CODE FAULT IS THAT ONE COMMIT WAS BOTH GREEN AND
  RED.** The same SHA passed on its push build and failed on its pull-request
  build, which reads as a difference between the two events and is not one. The
  runner says what it actually was, at the top of its own log, in three lines
  nobody reads: `Cache mode: read`, and `refs/heads/main: read` under "GitHub
  Actions runtime token ACs". The failing run was a RE-RUN of a pull request's
  build requested after that pull request had merged — so it checked out main,
  took the publishing path correctly (the gate was right; `github.ref` really
  was `refs/heads/main`), pushed the image a second time, and was then handed a
  read-scoped token for the cache. Nothing here decides that, so the only thing
  to do with it is survive it: `ignore-error=true` on the export.
  This is the `TtlCache` rule one layer out, and it is worth saying in the same
  words — a permission that changed underneath has to end as "we did not
  remember that one", never as a failure of the work.
  `tools/check-ci-cache.sh` asserts the INVARIANT, that every cache export in
  the workflow is non-fatal, rather than naming the one line: an exporter added
  later for some other job is covered without the check being edited. It strips
  comments first, because the script quotes the broken form on purpose — the
  same rule as the `data.selected` scan that matched the comment explaining its
  own fix.
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
- **"THE UPDATE BUTTON DOESN'T WORK" HAD NOWHERE TO BE ANSWERED, AND THAT WAS
  THE WHOLE OF WHAT COULD BE FIXED FROM HERE.** Reported from a container. The
  reasons DO exist — a failure sets `Phase.ERROR` with its message and the bar
  draws it — but only while the update is still offered, and the container
  EXITS mid-update by design, so the page reloads over the one place that said
  why. `/api/debug` had no update section at all. What was left was `docker
  logs`, which is the identical complaint the version line at the foot of the
  settings menu and the reachable diagnostics button were each added to fix: a
  fact that exists and cannot be reached. `Updater.diagnostics()` now names the
  running version, whether anything newer is published, the last failure's
  reason, and the state of the directory the download has to land in.
- **AND THE DIRECTORY IS THE LINE WORTH HAVING, BECAUSE IT IS THE ONE FAILURE
  THAT LOOKS LIKE NOTHING.** The container is not root, so a bind mount created
  by hand belongs to somebody else — and every write in this app is deliberately
  survivable, so the card draws, the page serves and discovery works perfectly
  while the one route that MUST write fails with "Permission denied". Nothing on
  the screen connected the two. Reproduced against the real server as uid 10001
  with a root-owned data directory: the update fails, and the report now reads
  `… IS NOT WRITABLE by sharecard — an update cannot be downloaded. In Docker:
  chown -R 10001 <the directory you mounted at /data>`. That is the README's own
  chown, printed beside the reason rather than in a startup log line nobody
  scrolls back to.
- **IT IS A READ, AND THAT IS NOT A DETAIL.** `/api/debug` is a GET and no route
  in this app writes to disk. Probing writability with a temporary file would
  break that rule for a diagnostic, so it is `File.canWrite` — a permission
  check and nothing else, which is why the wording is "looks writable" rather
  than a promise. **AND THE PROBE IS INJECTED**, because the tests run as ROOT
  in this environment and as somebody else in CI, and root's `canWrite` is true
  whatever the mode says: a test that made a directory read-only would pass for
  the wrong reason on one machine and fail on the other. Same seam `LmsSource`
  takes for a socket.
- **WHAT WAS RULED OUT, SO NOBODY RE-DERIVES IT.** 1.0.27 was suspected because
  it was the release in hand, and it is not the cause: the diff from 1.0.26
  touches the Dockerfile, the compose file, the workflow, the version and docs,
  and **not one line of `core/`, `server/` or `app/src`** — so the server
  program is identical but for its version string and the update logic cannot
  have regressed in it. The whole cycle was then driven end to end against the
  REAL published artifact under tini — manifest parsed, zip downloaded, sha256
  matched, unpacked to `versions/1.0.27`, `pending` written, process exited,
  launcher restarted into it, `trying` cleared and `active` written. Where the
  update DOES fail is the data directory, above.

## Discover, and the fourth thing that writes to disk

- **"BASED ON YOUR LISTENING" NEEDED A SOURCE OF TRUTH AND THIS APP HAD NONE.**
  It drew a card and forgot. Offered a ListenBrainz username instead, or keying
  off whatever is on screen right now, the owner chose `PlayHistory`: it needs
  no account, it works the same for Roon, Sonos, Lyrion and UPnP, and it is the
  only one of the three where the words mean what they say. THE COST IS STATED
  RATHER THAN DISCOVERED — it starts empty, so the screen is thin until the app
  has been used for a while, and the empty state says exactly that rather than
  "nothing new".
- **IT HOLDS AS LITTLE AS IT CAN.** An artist, an album and when it was last
  seen — no track, no per-play timestamps, no counts. None of that is needed to
  ask "what is new by acts this house hears", and a record of what somebody
  played and when is worth more to a stranger than a list of names. Capped at
  60 acts, oldest out first, so an act nobody has played for months stops
  shaping the screen. It FOLDS ON THE ACT, not the pair: a household that plays
  six Bowie records weighs as Bowie once, or one act crowds out everyone else.
- **IT IS WRITTEN OFF THE REQUEST THREAD, like the cache.** `remembering` is a
  single daemon thread and the append happens after the card's answer is built.
  A card must not be a millisecond slower because something is being remembered
  about it, and a full disk must not be able to fail one. Throwable is caught at
  both levels, because a rejected execution and a class that will not initialise
  are both Errors.
- **THE SCREEN IS SLEEVES, AND THAT IS THE LEGAL POSITION RATHER THAN A STYLE.**
  Asked for as "anything we can legally scrape and make our own page rather than
  more links", with "I just don't want to breach copyright of articles". So: a
  title and an artist are facts, a sleeve identifies the record the same way the
  card already does, and everything anybody has WRITTEN stays a link to whoever
  wrote it. No article text is reproduced, and no route could return any. RSS
  feeds are published for syndication and a digest of headlines would be
  defensible too — that is the next increment — but reproducing the prose never
  is, whatever it is wrapped in.
- **DISCOVER WAS SEEDED BY THE HISTORY ALONE, SO IT COULD ONLY EVER SUGGEST
  SOMEBODY YOU ALREADY PLAY.** Reported: "I recently listened to Ty Segall - it
  shouldn't always return another Ty Segall album. It appears to do this with
  all artists listened to recently. Needs to be broader - same style/genre."
  That was the design rather than a bug: the fresh-releases window was matched
  against the HISTORY, so the only act that could appear was one already in it.
  A new record by somebody you love is the most relevant thing this screen can
  hold; it just cannot be the whole of a screen called Discover.
  `seedsFrom` widens the filter with `Similar` - the SAME instance the card's
  suggestion row uses, so one shelf answers both - and a widened pick says
  "Similar to Ty Segall" rather than claiming you played it.
- **THE COST IS PER ACT, NOT PER RECORD, WHICH IS THE ONLY REASON THIS IS
  AFFORDABLE.** The window is still ONE request however many seeds there are:
  widening the filter does not widen the fetch. Each lookup is rate-gated and
  written to disk for a week, and only the most recent `SEED_ACTS` are
  expanded - expanding all sixty would be the exact mistake this file avoids
  elsewhere, sixty rate-limited lookups being a minute of waiting for a screen.
  A host that passes no `Similar` gets the old screen unchanged, which is what
  keeps every existing test honest.
- **TWO RULES CARRY THE COMPLAINT, AND BOTH ARE TESTED BY BREAKING THEM.** ONE
  RECORD PER ACT, because two Ty Segall releases in one window is two tiles
  saying the same thing; and `MAX_SAME_ACT` of `WANTED`, so acts you already
  play LEAD but cannot fill the screen, with the rest going behind everything
  similar. Not zero and not unlimited - zero would throw away the best signal
  here, unlimited is what was reported.
- **TWO SOURCES, ANSWERING DIFFERENT QUESTIONS.** ListenBrainz's fresh-releases
  window is ONE request for every release in a date range, so the filtering
  against the history happens in `NewMusic` — sixty acts would otherwise be
  sixty rate-limited MusicBrainz browses, which is a minute of waiting for a
  screen. Deezer's editorial list is not personal at all and is what fills a
  FIRST RUN. It only ever fills the space left over: a record by an act this
  house actually plays is never pushed out by one that is new to everybody.
- **NEITHER ENDPOINT HAS EVER BEEN REACHED FROM HERE.** Checked rather than
  assumed — the proxy answers 403 to the CONNECT for both hosts, and for
  musicbrainz.org, nme.com, pitchfork.com, daily.bandcamp.com and thequietus.com
  with it. So the shapes are the documented ones, the parsing is lenient (every
  field looked for in more than one place, a ragged row dropped rather than
  taking the screen), the socket is kept out of it, and `attempts()` lands in
  `/api/debug` under "Discover". Treat the first real run as the verification —
  the same posture as Lyrion and Roon.
- **ALBUMS ONLY, AND HALF THE WINDOW WAS NOT ONE.** Reported as "the discover
  page still offers singles. I only want albums. This is a must." Nothing was
  filtering release type at ALL — not the ListenBrainz window, not Deezer's
  list — so the screen was whatever those endpoints happened to list. The
  numbers are off the wire rather than out of a document: one week of
  fresh-releases is 1445 rows, **701 Single, 483 Album, 220 EP, 28 with no
  type at all, 8 Broadcast, 5 Other.** `release_group_primary_type` and
  Deezer's `record_type` are the fields that separate them, and each is a
  WHITELIST — Album, and nothing else — so a type nobody here has seen, and a
  row that states NO type, are both left out rather than put on the screen.
  Same rule `Similar.readDeezerAlbums` has applied to `/artist/{id}/albums`
  since "some are just tracks" was reported; a release list had the identical
  fault and none of the guard.
- **THE TYPE IS CHECKED BEFORE THE ONE-RECORD-PER-ACT RULE, AND THE ORDER IS
  LOAD-BEARING.** An act who put out a single on Tuesday and an album on
  Thursday is in that window twice. Refusing after `seen` had already claimed
  the act spends their one slot on the single and then throws it away — a
  filter that HIDES the record it was added to find. Shown failing by swapping
  the two lines.
- **A SECONDARY TYPE IS STILL AN ALBUM AND IS KEPT — the one judgement call
  in it, stated rather than buried.** MusicBrainz files a soundtrack, a
  compilation and a live record as primary type Album with the rest in
  `release_group_secondary_type` (58 of the 483 above), and those are records.
  It also means spoken word costs nothing to exclude: Audiobook, Interview and
  Audio drama each carried a primary type of their own. Narrow it if that is
  wanted; do not narrow it by accident.
- **AND THE FIXTURES COULD NOT HAVE CAUGHT THIS, WHICH IS THE LESSON WORTH
  MORE THAN THE FIX.** Every fresh-release fixture in `NewMusicTest` was
  written WITHOUT `release_group_primary_type`, because the shapes came from
  documentation rather than from a wire. A fixture missing the field a feature
  turns on cannot fail, whatever it asserts — the same fault as a scan that
  quietly matches nothing. The endpoints are REACHABLE from here now (both 200
  on a plain curl, as is nme.com), so they were driven and the fixtures carry
  observed fields. **RE-CHECK A HOST THIS FILE CALLS UNREACHABLE BEFORE
  BUILDING ROUND IT.**
- **`editorial/0/releases` ANSWERS `{"data":[],"total":0}`, AND THAT IS
  REPORTED RATHER THAN FIXED.** Found by the same re-check: Deezer's list is
  empty today, for every limit and for the only editorial id Deezer lists. So
  the third rung of the ladder — the one that fills a FIRST RUN — currently
  contributes nothing, and a household with no history sees what the press has
  reviewed and otherwise an empty screen. Replacing it means choosing another
  list, and `chart/0/albums` is NOT the same question: a chart is what is
  popular, not what is new. That is the owner's call, so `/api/debug` says
  "deezer -> 0 new this week" and nothing was guessed.
- **A SLEEVE PREFERS THE ALBUM'S OWN COVER OVER THE SINGLE NAMED AFTER IT.** A
  lead single usually shares the record's title and act, so both rows pass
  `pickSleeve`'s name check and Deezer ranks whichever it likes first.
  `record_type` is a PREFERENCE here rather than the whitelist it is above:
  albums are tried first and then anything matching, because by this point
  something else has already decided to draw the tile and all that is left is
  choosing a picture — and a chain that stops at its first candidate is the
  Lyrion coverid fault again.
- **THE PAGE NEVER DECIDES WHY A RECORD IS ON THE SCREEN.** "Because you played
  Slint" against "New this week" is the difference between this screen meaning
  its name and being a new-releases list, and that rule lives in `:core` where
  it has tests. The page draws `why`. Same argument as the chooser grid's rule,
  and `SettingsDrawnTest` scans for a second copy appearing in `app.js`.
- **A SCREEN THAT IS NOT ABOUT A RECORD MUST TAKE THE CARD'S ROWS DOWN.** The
  caption, the action row, the links and the suggestions all describe a record.
  Drawn over the grid on the first cut: the sleeves came in and under them sat
  "Playing in SR11 · via Roon", a Download button and two rows of links about
  something else — and because those rows kept their height, the grid was
  squeezed into a strip and its first row of sleeves was clipped.
  `clearCardRows()` is one function rather than four lines in each screen,
  because the fourth screen is where somebody forgets one.
- **AND `.browsing` IS NOT `.scrolls`.** `.scrolls` is the diagnostics' class
  and sets `flex: 1 1 auto`; using it drew a tall panel with the sleeves
  floating in the middle of it — the same mistake the chooser grid already made
  once and has a comment about. `restage()` decides every one of these classes
  from what actually went into the stage, because a caller that has to remember
  to CLEAR a class is a caller that will one day leave the diagnostics' scroll
  on a card.

## Queueing a suggestion into a zone that is not Roon

- **THE REPORT CAME FIRST, AND NOTHING ELSE WAS BUILT — AND THE REPORT IS WHAT
  SAID WHICH HALF WAS BUILDABLE.** Asked to explore adding suggestions to other
  zones — Lyrion first, then "Spotify, Qobuz and Tidal which are all detected on
  UPnP zones". The honest answer to the second half is that it depends entirely
  on the box and CANNOT BE SETTLED FROM THIS MACHINE, so the only thing shipped
  was a probe: `UpnpSource` now carries every `serviceType` its description
  lists and `/api/debug` prints them with a one-line reading. Chosen
  deliberately over writing speculative queue code — three rounds of the Roon
  queue were spent reasoning about a protocol nobody here could reach, and the
  round that fixed it printed what the Core actually sent and read the answer
  off the first two lines. THREE PROBES LATER Lyrion was built (below) and UPnP
  is still a report, which is the split the probes earned rather than one
  guessed at up front.

### Lyrion, which turned out to be the easy one

- **LYRION HAS A CONTROL API, SO THE FEATURE IS TWO CALLS.** `["albums", 0, N,
  "search:<terms>", "tags:la"]` then `[<player>, ["playlistcontrol", "cmd:add",
  "album_id:<id>"]]`. `RoonBrowse` is a seven-step browse walk that took four
  releases; `LmsQueue` is a search and an append, and THREE things that were
  hard in Roon are simply free here. **`cmd:add` IS "Queue"** — a named
  parameter rather than a menu row to identify, so neither `pickQueueAction`'s
  hazard (Roon's menu OPENS with Play Now) nor its other one (an installation in
  another language) exists. **THE REPLY SAYS HOW MANY TRACKS WENT IN**, so
  success is a number that is asserted rather than assumed — the expensive Roon
  bug was the final invoke never being read, so a refusal reached the page as
  "Added to the end of the queue". And **A SEARCH RESULT IS THE ALBUM**, not a
  row that opens onto a screen that holds rows, so the off-by-one that cost Roon
  four releases has no equivalent. `cmd:load` replaces and plays and must never
  appear in that file; a test asserts the wire carries neither it nor
  `cmd:insert`.
- **WHAT IS NOT FREE IS THE MATCHING, AND IT IS PORTED WHOLESALE RATHER THAN
  REDECIDED.** `pickAlbum` is `RoonBrowse.pickAlbum`'s shape: both names must
  overlap where the row NAMES an artist, a row naming NOBODY is matched on its
  title alone (a library files box sets and soundtracks under no artist, and
  demanding one turns a library that holds the record into "not in your
  library"), and TWO anonymous candidates is a coin toss where neither is taken.
  The search string is folded through `Normalize.stripEdition` and
  `Normalize.primaryArtist` for the reason the owner diagnosed on Roon: a
  suggestion is spelled DEEZER's way ("Ignition (2008 Remaster)") and a library
  is spelled its owner's ("Ignition").
- **ONE ROUTE, DISPATCHING ON THE ZONE'S SOURCE.** `/api/roon/queue` became
  `/api/queue`. The alternatives were a second gated write route — a second
  place to get the gate wrong — or the PAGE choosing a URL per source, which is
  a rule, and rules live in `:core` where they have tests. Still POST, still
  behind `Access.mayConfigure`, and a zone whose source cannot queue is REFUSED
  rather than guessed at. 400 means "that room cannot take a queue" and 200
  means "the library was asked and said no": the difference between them IS the
  dispatch, which is what the route test asserts.
- **`canQueue` IS COMPUTED FROM THE SAME LIST THE ROUTE DISPATCHES ON.** The
  page tested `uid.indexOf("roon:") === 0` — a second copy of the rule, in the
  one file with no tests, which would have silently gone on offering the chip to
  Roon alone. The card says whether the room can take a queue and the page
  believes it. Same argument as the chooser grid's `choose` flag.
- **A LYRION PLAYER IS NAMED BY ITS MAC ADDRESS, WHICH IS THE ONE RAW ID IN THIS
  APP MADE OF COLONS.** `ZoneRef.rawOf` splits at the FIRST colon, so
  `lyrion:00:04:20:aa:bb:cc` yields the whole MAC; splitting at the last would
  hand the server `cc` and queue into nothing, silently. Shown failing by
  changing it to `substringAfterLast`.
- **TWO SOURCES' ATTEMPTS IN ONE DIAGNOSTICS SECTION MUST EACH SAY WHICH.** "no
  match in 3 row(s)" is the same sentence from Roon and from Lyrion and the
  fixes are in different files, so every line is prefixed with its source and
  the heading names the ACTION rather than one source. Same rule as
  `Pitchfork.Outcome` naming which failure it was, and the same mistake as the
  "Pitchfork review" chip drawing an NME review under Pitchfork's byline.
- **NO LYRION SERVER IS REACHABLE FROM HERE, SO THE SOCKET IS KEPT OUT OF EVERY
  DECISION THAT CAN BE WRONG ON ITS OWN.** What IS verified: `LmsQueueTest`
  drives the whole thing through a real `MockWebServer`, so the JSON-RPC
  envelope this app builds is exercised rather than stubbed, and `/api/queue`
  was driven with curl against the running `:server` for every source. What is
  NOT verified is a real Lyrion answering — the documented command set is what
  the shapes come from. Read `/api/debug` first on the first real run.
- **BASE UPnP HAS NO QUEUE, AND THAT IS THE WHOLE DIFFICULTY.** `AVTransport`
  holds ONE uri plus one "next" slot, so anything sent to it REPLACES what is
  playing rather than joining a list behind it. "Queue" and "play" are one word
  apart and a very long way apart in what they do to a room somebody is
  listening to — the same rule `RoonBrowse.pickQueueAction` exists for. Every
  real queue is a VENDOR EXTENSION: OpenHome's `Playlist` (Linn, WiiM, Volumio,
  BubbleUPnP), Sonos's `Queue`, LinkPlay's `PlayQueue`. Which of those a box
  speaks is written in its description and nowhere else.
- **THE WALK MUST NOT STOP AT AVTransport, AND IT USED TO.** `parseDescription`
  broke out of the service loop the moment it had the control URL — correct
  while that URL was the only thing wanted, and it would have made this report
  depend on a manufacturer's ordering. OpenHome's `Playlist` is commonly listed
  AFTER `AVTransport`, so the one service this probe exists to find is exactly
  the one the old loop would have hidden. Shown by putting the `break` back and
  watching both tests fail.
- **THE FIRST PROBE ANSWERED ONE STEP SHORT OF USEFUL, AND THE SECOND ASKS THE
  DEVICE.** Off a real network it said `services: upnp/AVTransport,
  upnp/ConnectionManager, upnp/RenderingControl, wiimu/PlayQueue,
  tencent/QPlay` for a WiiM Pro Plus — so there IS a queue service, and a
  service NAME does not say whether it can append, only replace, or anything
  at all. Every UPnP service publishes an **SCPD** enumerating its actions and
  arguments, FROM THE DEVICE ITSELF, so that question has an authoritative
  answer for one GET. This app should never reason from a vendor's PDF or
  somebody's reverse engineering about a box it can talk to directly.
  `SCPDURL` is captured per service and read for the ones [queueability]
  recognises — one or two GETs out of six, inside the scan that already
  fetched the descriptions and behind the same TTL. `isQueueService` is the
  same list [queueability] reads, so what gets REPORTED and what gets ASKED
  cannot drift apart.
- **AND THE `break` FIX PAID FOR ITSELF ON THE FIRST REAL RUN.** `wiimu/
  PlayQueue` was listed FOURTH on that WiiM, after `AVTransport` — so the old
  loop, which stopped the moment it had the control URL, would have reported
  the first three services and answered "nothing. AVTransport alone holds ONE
  uri". A confident wrong answer that would have closed the question. The
  hazard was reasoned about before the device was seen; the device then
  demonstrated it.
- **AND THE SCPD SETTLED IT: `AppendQueue` EXISTS.** The WiiM published
  thirty-four actions, and the two that matter are `AppendQueue` and
  `AppendTracksInQueue` — a queue that can be APPENDED to is one a suggestion
  can join without stopping what somebody is listening to. `ReplaceQueue`
  sitting beside them is the proof the device draws that distinction too, and
  it is the verb this app must never reach for by accident: exactly what
  `RoonBrowse.pickQueueAction` exists for, one protocol over. `BrowseQueue`
  means an append can be VERIFIED rather than assumed, which is the omission
  that cost the Roon queue four releases.
- **SO THE ARGUMENTS ARE PRINTED TOO, FOR THE VERBS THAT ADD.** A name says a
  queue can be appended to; it does not say what to put in one, and that is the
  whole remaining question. The same SCPD answers it at no extra request.
  `worthSigning` is a RULE and not a list — append/insert/add/search/browse/
  online — because the next box will name its verbs differently and a list
  would silently answer "nothing" for it. Thirty-four signatures would bury the
  answer rather than give it, so the ones that log a user in, set a loop mode
  or rate a track are left out.
- **THE REMAINING WALL IS WHAT GOES IN IT, NOT WHETHER IT CAN BE SENT.** A
  suggestion comes from Deezer, and Deezer gives a thirty-second preview, not a
  track a renderer can play. `SearchQueueOnline` is the interesting shape,
  because it would have the DEVICE resolve a record from a service it is
  already logged into - `UserLogin`, `GetUserInfo` and `SetSpotifyPreset` on
  that same list say the credentials live on the box. That would be keyless
  from this app's side, which is the only shape the owner's decision leaves
  open.
- **AND THE TWO SIGNATURES FIT EACH OTHER, WHICH IS THE WHOLE FINDING.** Read
  off the device rather than guessed:

      SearchQueueOnline(in QueueName, in SearchKey, in Queuelimit,
                        out QueueContext)
      AppendQueue(in QueueContext)
      AppendTracksInQueue(in QueueContext)
      BrowseQueue(in QueueName, out QueueContext)

  The search's OUTPUT is exactly the append's INPUT, and `BrowseQueue` reads
  back what is in there - so search, append, verify is a complete round trip
  with no credential anywhere in it, and the verify step is the one whose
  absence cost the Roon queue four releases. AN EARLIER NOTE HERE RECORDED THAT
  SIGNATURE WRONG - "out Queue", with `Queuelimit` missing. Small, and exactly
  the kind of thing this file exists to be right about: a shape a machine
  actually sent is worth more than a shape somebody wrote down, which is the
  rule the OBSERVED Lyrion test is named after.
- **WHAT IS STILL UNVERIFIED IS EVERY VALUE.** What `QueueName` names (a
  service? a queue already on the box?), what `SearchKey` accepts, what a
  `QueueContext` actually looks like, and whether any of it reaches a streaming
  service at all. None of that can be settled from here and none of it should
  be guessed.
- **AND FIRING ONE OF THESE IS NOT A READ.** `ReplaceQueue` sits on the same
  service, `AppendTracksInQueueEx` takes an `in Play` argument, and
  `GetQueueOnline` takes `in QueueAutoInsert` - so a vendor action sent
  speculatively at a box somebody is listening to can stop the music. That is
  the exact failure `RoonBrowse.pickQueueAction` exists to prevent, one
  protocol over. So the probe for this must be EXPLICITLY INVOKED and gated,
  never something `/api/debug` fires on its own: the report is read constantly,
  and by definition at moments when something is already wrong.
- **NO OPENHOME ON THAT BOX, WHICH IS WORTH KNOWING BEFORE BUILDING
  ANYTHING.** WiiM is widely described as an OpenHome renderer and this one
  advertises no `av-openhome-org` service at all. `tencent/QPlay` is QQ
  Music's casting protocol and nothing to do with this. Whatever gets built
  for UPnP has to be built against what a description actually says, per box,
  which is the whole reason this is a report rather than a guess.
- **A REPORT THAT REWRITES WHAT IT WAS GIVEN IS WORSE THAN NONE.** `shortService`
  tidies a URN for reading off a phone in another room, and anything that is not
  a service URN is printed exactly as it arrived. `queueability` names what the
  box ADVERTISES — which is not the same as it working, not the same as having a
  URI worth sending, and not the same as this app being able to build one.
- **SPOTIFY, TIDAL AND QOBUZ CANNOT BE QUEUED KEYLESSLY, AND THAT WAS DECIDED
  RATHER THAN OVERLOOKED.** Spotify's queue endpoint needs OAuth, a registered
  application and Premium; TIDAL needs OAuth and a developer account; Qobuz has
  no public API at all, which is why `QobuzAlbum` reads their search PAGE. Put
  to the owner with that laid out, the answer was to keep the app keyless: no
  OAuth, no fourth credential on disk, and those suggestions keep opening in
  their own app as they do today. Reopen it as a product question, not as
  something nobody thought of — the same shape as the Roon-link decision and the
  link row's "does this service carry the record" decision above.
- **AND A CONNECT SESSION IS NOT A UPnP SESSION.** When Spotify Connect, Qobuz
  Connect or TIDAL Connect plays to a renderer, the device is driven by the
  SERVICE's own protocol; UPnP is only reporting the metadata, which is how
  `UpnpSource` reads it. So even on a box with a real queue service, sending to
  it does not join a Connect session — it takes the device off one.
- **AND THE OWNER HAS NOW CLOSED IT: "I don't think adding to the queue will be
  possible."** Said after the probes had answered everything they could, so
  this is a decision on evidence rather than a guess, and it is recorded here so
  nobody re-derives the same three rounds.
  WHAT THE PROBES DID SETTLE, and it is not nothing: the WiiM advertises
  `AppendQueue`, `AppendTracksInQueue`, `BrowseQueue` and `SearchQueueOnline`,
  read off its own SCPD, and the search's output is exactly the append's input.
  So SENDING a queue command was never the wall. WHAT WAS NEVER SETTLED is what
  goes IN one - what a `QueueContext` actually contains, what `SearchKey`
  accepts, and whether any of it reaches a service the box is logged into - and
  none of that can be answered from here. The remaining step was one explicitly
  invoked `SearchQueueOnline` against a real box, and it was offered and not
  taken.
  **AND THE REASON THAT IS AN EASY DECISION IS THAT THE FALLBACK ALREADY
  WORKS.** `RoonBrowse`'s rule has always been "the link stays on the chip and
  is the fallback" - queue if possible, open the record in a streaming service
  if not - and the field confirms the fallback: "suggestions are reliably
  opening to the relevant streaming service". So the queue was an upgrade on a
  path that works every time, never a fix for a broken one. THE PROBE STAYS:
  `queueability` and the SCPD signatures cost one or two GETs inside a scan that
  already runs, and they are the evidence any future reopening would otherwise
  have to gather again. Roon and Lyrion queueing are untouched - they are built,
  and they are the two protocols where a library answers rather than a service.

## What is playing on the phone itself

- **EVERY SOURCE IN THIS APP IS A NETWORK SOURCE, AND THAT IS WHY THE PHONE IS
  INVISIBLE.** Roon over a socket, Lyrion over JSON-RPC, Sonos and UPnP over
  SOAP — not one of them can see the device's own audio. So the distinction is
  not Spotify against Qobuz against Roon ARC, it is WHERE THE SOUND COMES OUT:
  casting to a speaker has always worked (Spotify Connect and Qobuz Connect to
  a Sonos are the same `SonosSource` path), and the same app playing to
  headphones is seen by nothing. Asked directly whether the Android app could
  detect it.
- **ANDROID CAN ANSWER IT AND iOS CANNOT, WHICH IS THE WHOLE REASON IT IS
  WORTH DOING.** `MediaSessionManager` is what drives the lock screen, the
  Bluetooth buttons and Android Auto, so anything with those controls publishes
  a session. iOS gives a third-party app no equivalent — `MPNowPlayingInfoCenter`
  reports only that app's own playback. A fifth source is buildable on one
  platform and not the other.
- **IT WAS A PROBE FIRST, AND THE PROBE IS WHY THE SOURCE IS SHORT.** It shipped
  as one `/api/debug` section with no zone, no card and no route - the same
  decision as the UPnP queue exploration one section up, for the same reason:
  three rounds of the Roon queue were spent reasoning about a protocol nobody
  here could reach, and the round that fixed it printed what the Core actually
  sent. ONE PHOTOGRAPH OFF A REAL PHONE THEN ANSWERED EVERY QUESTION IT WAS
  BUILT FOR: two apps holding sessions, each with a title, an artist AND an
  album, one PLAYING and one PAUSED, and one of them offering a cover the art
  proxy already allows. `DeviceSource` turns that into a room, and it NEEDED NO
  NEW ANDROID CODE AT ALL - the reading was already right and only the deciding
  was missing, which is the argument for the seam stated by the change rather
  than by a comment.
- **ONE ZONE, NOT ONE PER APP.** Two apps holding sessions on one phone is not
  two rooms; it is one room and a question about which to believe, and
  `quality` already answers exactly that question for the house. Per-app zones
  would also make every newly installed music app an unasked-for room, each
  needing switching on by itself.
- **AND `quality` MOVED OUT OF `Sources` RATHER THAN BEING COPIED.** It was a
  member while the ladder was its only caller; a source holding several answers
  of its own needs the identical rule. Same move `Normalize.namesOverlap` and
  `Normalize.stripEdition` each made on their second caller, for the same
  reason: two copies is how one caller ranks an album over an artist and the
  next does not. Proved by a test that ranks two REAL sessions both ways round
  - a source taking whichever Android listed first passes the playing-beats-
  paused test by accident, and fails that one.
- **THE ROOM IS LISTED WHILE THE PERMISSION IS REFUSED, AND THE NOTICE IS NOT.**
  Those pull opposite ways and both are deliberate. `zones()` offers "This
  device" on DENIED because otherwise there is nothing in Settings to switch on
  and no way to reach the explanation - a source that hides until it is
  permitted is unreachable. `notice()` stays SILENT until the room is switched
  on, because rooms are opt-in and a permission notice shown before anybody
  asked would sit on every Android install for ever, about a feature nobody
  requested. That is precisely the bug the Roon notice was narrowed after
  ("Looking for your Roon Core..." under a card that worked). Switching the
  room on IS the request; UNSUPPORTED is silent either way, like Roon's ABSENT.
- **IT IS LAST IN THE SOURCE LIST, AND THAT IS THE TIE-BREAK SPEAKING.** Source
  order decides only between answers that are otherwise equally good. Every
  source above it is a room in the house; this one is the device in somebody's
  hand, and when both are playing a full record the house is what this app is
  for. Naming the zone still reaches it directly, because a named zone is a
  lock rather than a preference.
- **SPOTIFY'S COVER IS RECONSTRUCTED FROM ITS `content://`, AND I REFUSED THAT
  ONCE BEFORE THE FIELD OVERTURNED IT.** Qobuz's session gives
  `https://static.qobuz.com/...`, which `ArtProxy` allows unchanged on its
  public-https rule, so those cards always drew a sleeve. Spotify's is a
  `content://` belonging to Spotify, which this process holds no grant to read -
  reported plainly as "Spotify no artwork / Qobuz has artwork".
  THE FIRST REFUSAL WAS RIGHT ON THE EVIDENCE THEN AVAILABLE. One dump carried
  `.../image/<id>?cdn=i.scdn.co` and the next, minutes later off the same phone,
  carried `.../spotify%3Aimage%3A<id>` with no cdn at all - a parser written
  against the first finds nothing in the second - and nothing proved a rebuilt
  url would actually fetch.
  **THE REPORT THEN SUPPLIED BOTH MISSING HALVES IN ONE LINE.** Under "Album
  art": `https://i.scdn.co/image/ab67616d0000b2736900383e72eb02bf27bbd482 ->
  79813 bytes, image/jpeg` - an id BYTE-IDENTICAL to the one inside the previous
  dump's content uri, fetched successfully through this app's own proxy. Nothing
  here builds an i.scdn.co url, so it came from a SOURCE reporting one: Spotify
  Connect to a Sonos, the absolute-CDN case `ArtProxy` was widened for long ago.
  That is the free control experiment again - one row was the failure and the
  row beside it was the answer. And three observed uris across three dumps all
  carry the same 40-character hex id whatever the path around it, so the ID is
  read and the PATH is ignored rather than parsed.
  **AND IT IS VERIFIED ON A DEVICE NOW, WHICH CLOSES THE CLAIM IT SHIPPED
  UNDER.** 1.0.19 handed this over as "NOT tested: a phone" - nothing here
  renders a card, so the reconstruction was evidenced and unproven at the same
  time. The next run answered it: "Spotify album art working / Qobuz still
  working". Both halves, including the one that could have regressed silently -
  a change that fixed Spotify by breaking the https path Qobuz uses would have
  looked like a success from one card.
- **THE ID IS THEIRS; THE HOST IS OURS.** The `cdn` parameter is NOT honoured
  even where it is present. It is a string another app put in its own metadata,
  and composing a url from it would let any app on the phone choose a host this
  one fetches - the `StreamHosts` rule ("nothing is inferred from the ART url,
  which is the part an attacker would control") one protocol over. `i.scdn.co`
  is hard-coded: the only value ever observed, and the one the report proved.
  Only Spotify's own authority is reconstructed, and anything else yields
  nothing rather than a guess. A `content://` still never reaches `ArtProxy`,
  where it would be refused on every card for ever.
- **AND TWO TESTS HAD TO BE CORRECTED BEFORE THEY PROVED ANYTHING.** The old
  assertion said a Spotify session yields NO art, which is the behaviour this
  change deliberately reverses - so it was REWRITTEN to the invariant it was
  actually guarding rather than deleted. And the length guard's test exercised
  the wrong branch: its 41-character case ran off the END of the string, while
  every real uri carries `?transformation=NONE` after the id, so relaxing
  "exactly 40" to "at least 40" slipped straight past it. A tail was added and
  the mutation then failed. WHEN PROVING A GUARD, CHECK THE FIXTURE TAKES THE
  SAME PATH THE REAL INPUT DOES.
- **WHAT IS STILL NOT DONE IS A SLEEVE FOR EVERY OTHER APP.** This is Spotify
  and Qobuz; a third music app with a `content://` and no known CDN still draws
  no cover. Resolving one from the RECORD, the way `NewMusic.sleeveFor` already
  does out of Deezer with a loose search and a strict check, is the universal
  version and would need extracting from `NewMusic` rather than copied. Serving
  the bitmap the shell already holds is the other. Neither is built.
- **THE PERMISSION IS THE TRAP, AND IT IS THE REASON `DeviceAudio` EXISTS AT
  ALL.** `getActiveSessions` needs an enabled notification listener, and an
  empty list is what a silent phone looks like too. So "no app is playing" and
  "you never granted access" can arrive as the same value, which is the
  `Pitchfork.Outcome` lesson waiting to be repeated. [Access] is carried
  separately from the sessions, the three states are printed in different words,
  and the refused one names the Settings screen. `DeviceAudioTest` asserts the
  two can never read the same.
- **AND AN EARLIER NOTE HERE ASSERTED SOMETHING NOBODY HAD CHECKED.** It said
  flatly that an unpermitted caller gets an empty list RATHER THAN an exception.
  That was written from memory, not from a device, and it shaped the design. A
  `SecurityException` is caught AND an empty list is disambiguated now, so the
  report is right under either — but the lesson is the claim, not the code: this
  repository refuses confident wrong answers everywhere else and one got written
  into its own notes.
- **THE CALL DECIDES; THE SETTING ONLY EXPLAINS.** The first cut read
  `enabled_notification_listeners`, decided the permission from its own parse of
  it, and only then called. Reported from the field as NOT GRANTED on a phone
  whose owner had just granted it — and at that point three causes wore one
  sentence: the grant did not take, it went to a different app, or this app's
  reading was wrong. Nothing could separate them. `getActiveSessions` itself is
  the authority (a refusal is the system's, not an inference), and the setting
  is read only to say what was looked for and how many listeners the system
  holds. ONE PARSE, TWO CALLERS: deciding with one reading and explaining with
  another is how a report contradicts the thing it reports on.
- **AND THE ANSWER WAS ANDROID REFUSING THE GRANT OUTRIGHT: RESTRICTED
  SETTINGS.** Photographed from the phone: tapping the toggle the report
  pointed at gave *"App was denied access — access to this permission can put
  your personal and financial info at risk"*. Since Android 13 a
  notification-listener grant is refused to anything installed outside the Play
  Store; the switch appears, does nothing, and the setting never changes. So
  the grant genuinely never landed — the probe's reading was right all along,
  and what was missing was the way PAST it: Settings → Apps → this app → the
  three dots → **Allow restricted settings**.
  **A PATH SOMEBODY HAS ALREADY WALKED AND BEEN REFUSED ON IS WORSE THAN NO
  PATH** — it reads as the app being wrong about the state rather than as a
  block with a key. The refusal names the escape now, in the system's own
  words ("App was denied access") so it is recognisable on the screen it
  appears on.
- **AND IT IS NOT A ONE-OFF SIDELOAD.** `ApkInstaller` hands the APK to
  `ACTION_VIEW`, the legacy install flow, so EVERY self-update this app
  performs is marked restricted the same way and the block returns after each
  one — which is why the message says so rather than reading as a first-run
  chore. The session-based `PackageInstaller` API is the shape that would avoid
  it, and it is a real change rather than a line: a session, a status receiver
  and the APK streamed in, none of which can be exercised from here. Not built;
  reopen it if the repeat becomes annoying.
- **THE NOTE COUNTS THE OTHER LISTENERS AND NEVER NAMES THEM.** That setting is
  every app somebody has given notification access to, and this report gets
  pasted into chat windows and bug reports. A count answers the question; the
  list would be their installed software. Enforced by the signature —
  `listenerNote` is handed a NUMBER, so there are no names available to leak
  later.
- **THE SHELL READS, `:core` DECIDES.** `DeviceSessions` copies Android's
  objects into plain data classes and judges nothing; every line of the report —
  what a cover situation means, what to tell somebody to do — is in `:core`
  where it has tests. Same seam `LmsSource` uses for discovery, "injected so a
  test need not open a socket". `MediaAccess` is an EMPTY
  `NotificationListenerService`: it exists only so the permission can be
  granted, and it deliberately overrides nothing, because a listener that
  actually read notifications would be a different app with a different privacy
  question.
- **WHAT A CARD COULD DRAW IS THE OPEN QUESTION, SO THE THREE ART CASES ARE
  NEVER COLLAPSED.** The art pipeline is a URL fetched by `ArtProxy`, and a
  media session reliably offers neither half: `ART_URI` is usually a
  `content://` belonging to the OTHER app, which this process has no grant to
  read and no proxy can fetch; a bitmap is already in memory with no URL at all;
  and plenty of sessions carry nothing. Three different amounts of work, so the
  report names which — and both a uri and a bitmap are printed when both exist,
  because a chain that stops at the first candidate is not a chain (the Lyrion
  coverid lesson).
- **A NULL DEFAULT MADE THE HONEST BRANCH UNREACHABLE, AND ONLY THE REAL SERVER
  FOUND IT.** `DeviceAudio` has an UNSUPPORTED branch saying "not an Android
  build … this is the container", written deliberately so a section does not
  silently vanish on one of the two shells. It was unit-tested and it never ran:
  `ShareCardApp` took `DeviceAudio? = null`, so the container printed no section
  at all. Correct, served and invisible — this repository's oldest diagnostics
  fault, in a change whose whole subject is diagnostics. No test of `DeviceAudio`
  could see it, because the fault was in how a HOST wired it; driving
  `:server:installDist` with curl is what showed it. The parameter is
  non-nullable now, so "no probe" cannot be expressed at all.
- **NOTHING IN `app/` HERE IS TESTED.** `MediaSessionManager` cannot be reached
  from a JVM and there is no device in this repository, so CI compiles
  `DeviceSessions` and `MediaAccess` and that is the whole of it. Re-reading the
  file adversarially before pushing caught a real compile error — `when (state?
  .state) { null -> … else -> state.state }` does not smart-cast `state`, so the
  else branch would not build. Treat the first real run as the verification.
- **`internal` IS PER MODULE, AND `:app` IS NOT `:core` — THE CHECK LIST CANNOT
  SEE THIS.** `listenerNote` shipped `internal`, inside an `internal companion
  object`, and CI refused it: an internal member is invisible from another
  module, and an internal COMPANION cannot be resolved from one at all, so
  making just the function public would have failed a second time. Both halves
  were there; reading the declaration rather than trusting the one-word fix is
  what found the second.
  **THE TESTS WERE EVIDENCE OF THE WRONG THING**, which is this repository's
  founding rule stood on its head. `./gradlew :core:test` compiles `:core`
  alone, and `DeviceAudioTest` lives in :core's own test source set where
  `internal` IS visible — so the test passed while the app would not build.
  "Compiling is not evidence" has a twin: a green suite is not evidence either,
  when the thing it cannot compile is the module the suite does not touch.
  `ModuleSeamTest` scans `app/` for references to anything `:core` hides and
  names the file and line, because a CI round trip is the slowest loop here and
  this seam gets crossed every time the shell is given something new to ask.
  It flags a reference only when the TYPE and the MEMBER both match, and it
  strips block comments — the first cut fired on `[DeviceAudio.artNote]` inside
  a KDoc, which is the "a source scan must read code, not prose" rule caught by
  the person who wrote it down.

## Scope and process

- Develop on the branch named in the task. Never push to another branch.
- Do not open a pull request unless asked.
- Bump `versionName` **and** `versionCode` in `app/build.gradle.kts` for any
  build meant to be installed; Android refuses to install over an equal or lower
  `versionCode`. The workflow publishes `dist/` and rewrites the README link from
  `versionName`.
- **THE NUMBERING IS v1.0.x, AND v1.1.x ONLY WHEN THE OWNER SAYS.** Every
  incremental build is the next 1.0.x; a jump to 1.1.x is a decision, not
  something to take because a change felt large. `1.0.0` itself came out of a
  near miss worth remembering: the next release after 0.56.0 was asked for as
  **0.6.0**, and `Updater.compareVersions` is NUMERIC PER COMPONENT — 6 is less
  than 56, so every installed app would have read it as older and never offered
  the update. Nobody would have seen it in the app again until a version above
  0.56.0 shipped. Put to the owner with that spelled out, the answer was 1.0.0.
  **A version number is published to every device and cannot be walked back, so
  it is the owner's to choose — ask.**
- **The signing keystore is private key material.** It lives in CI secrets. Do
  not commit it, and do not change the key: an APK signed with a different one
  cannot install over the existing app.
- Ask before guessing when a choice is the user's to make. A corner, a layout, a
  default that is hard to reverse — ask, do not assume and apologise later.
