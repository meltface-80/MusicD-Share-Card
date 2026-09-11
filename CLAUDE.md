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

## Things about this codebase that are easy to get wrong

- **The cover MUST be proxied.** A canvas that has drawn a cross-origin image
  cannot be read back: `toBlob` throws and there is no card. Sonos sends no CORS
  header, so pointing the page at a player directly can never work, however much
  simpler it looks. See `ArtProxy`.
- **Every route is a GET and none of them changes anything.** That is the only
  reason this server can answer the whole LAN without a password. Do not add a
  route that writes — to a player, to disk, or to a setting — without putting an
  authentication gate in front of the socket in the same change.
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
- **The XML parser is locked down but NOT namespace-aware, and both halves are
  deliberate.** The XXE features guard documents that arrive over the network
  from a device on the LAN — do not relax those. But `isNamespaceAware` must
  stay **false**: a namespace-aware parser rejects the WHOLE document over one
  unbound prefix, and that made a real household unreachable — three players
  answering on port 1400, every one of them "would not describe the household".
  Nothing here reads a namespace URI; every lookup goes through `Xml.localName`,
  which strips the prefix off the tag name.
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
