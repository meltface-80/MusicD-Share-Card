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
- **Only `TokenStore` writes anything**, and nothing on the network can reach
  it. The rule that every route is a read is unchanged.
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
- **iOS needs a real PNG icon.** `apple-touch-icon` will not take an SVG or an
  adaptive icon, and without one the Home Screen shows a screenshot or a bare
  letter. `app/src/main/assets/web/icons/` is generated from the same geometry
  as `ic_launcher_foreground.xml`, so the two platforms show one icon.
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
- **A streaming link is an https link, never a custom scheme, and the HOST is
  the part that goes wrong.** `spotify://` opens the app and does nothing at all
  when the app is absent; an https link opens the app on a phone that has it and
  the web player on one that does not. But a host whose app claims EVERY path
  opens on its own home screen when the path is one it has no screen for — which
  is how MusicD Remote Lite shipped `open.qobuz.com/search?q=` and made a link
  that looked like it simply did nothing. `www.qobuz.com` publishes no
  assetlinks and reaches the browser. Check `.well-known/assetlinks.json` and
  `.well-known/apple-app-site-association` before adding a service.
- **The search query is percent-encoded, and a slash is spent as a space.**
  `URLEncoder` writes a space as `+`, which four of the six services take as a
  literal plus because they carry the query in the PATH. And `%2F` is decoded
  back into a path segment by Qobuz's own redirect, so "AC/DC" 404s. Both are in
  `StreamingLinks.searchQuery` with a test each.
- **Nothing links to Roon, deliberately.** Roon publishes no URL scheme and no
  web player, so there is no link to build — and searching its library from here
  would need the BROWSE service this app deliberately does not ask for. A link
  that opened nothing would be worse than its absence.
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
