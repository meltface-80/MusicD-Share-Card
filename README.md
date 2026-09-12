# MusicD Share Card

A native Android app that makes a share card for whatever is playing — and
serves the same card to any browser in the house.

Open it and the card is there. It does not matter where the music came from:
Roon, Spotify Connect, Apple Music through the Sonos app, a Sonos playlist, a
radio station, or anything on a DLNA renderer.

**It asks whoever actually knows.** That is the whole design. Roon streaming to
a Sonos speaker hands the speaker a session id where the title should be — read
through the speaker the record is not there at all — so Roon is asked directly,
through its own extension API, and answers with the album, the artist and a real
cover. The speakers are asked about what the speakers themselves stream. A DLNA
renderer is asked in plain UPnP.

| Source | Used for | Gives |
| --- | --- | --- |
| **Roon** | anything Roon is playing, anywhere | album, artist, cover, from the Core |
| **Sonos** | Spotify Connect, Apple Music via the Sonos app, radio | DIDL-Lite off the coordinator |
| **UPnP / DLNA** | any other renderer on the network | DIDL-Lite off its AVTransport |

Adding another source means implementing one interface. The card, the page and
the API do not change.

**The card — and now the Roon client — come from
[MusicD Remote Lite](https://github.com/meltface-80/Android-Random-Remote).**
The card is the same picture it draws. `sharecard.js` is that app's file, ported unmodified, so a record shared
from Sonos and the same record shared from Roon produce matching cards. The album
blurb, the release year and the Pitchfork score come from the same lookups too.

## What it does, and what it deliberately does not

It makes a picture. That is the whole app.

It has **no transport controls** — no play, pause, skip or volume. Not an
omission to fill in later: every route the server answers is a GET and none of
them changes anything on a player, and that is what makes it safe to serve the
page to the whole house without a password.

## How it fits together

```
┌──────────────────────────────────────────────────────┐
│ MainActivity — a WebView          any browser on LAN │
│           └──────────────┬──────────────┘            │
│                 http://<device>:8747                 │
└──────────────────────────┬───────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────┐
│ :core  (plain Kotlin/JVM — unit-tested)              │
│   HttpServer  →  CardApi                             │
│   sonos/   SSDP → SOAP → ZoneGroupTopology → DIDL    │
│   meta/    MusicBrainz · Wikipedia · Pitchfork       │
│   ArtProxy (so the canvas is not tainted)            │
└──────────────────────────┬───────────────────────────┘
                           │  SOAP on :1400
                    ┌──────▼───────┐
                    │ Sonos players │
                    └──────────────┘
```

Three decisions carry the design:

**The card is drawn in the browser, not on the device.** The server sends JSON
and a cover; `sharecard.js` renders the PNG wherever the page is open. That is
why the phone across the room gets a card identical to the one on the device's
own screen, and why the app needs no image encoder.

**The cover is proxied, and that is load-bearing.** A canvas that has drawn a
cross-origin image cannot be read back — `toBlob` throws and there is no picture
to share. Sonos serves art from `http://<player>:1400/getaa?…` with no CORS
header, so it is fetched by the app and re-served same-origin. It also means a
browser never talks to a speaker directly.

**Nothing polls.** The app asks a speaker what is playing when somebody opens the
page or presses Refresh. A timer would mean interrogating the household every few
seconds, all day, on a device that is never switched off, to answer a question
nobody is in the room to read.

## Install

Sideload the APK on Android 8.0 (API 26) or newer. It is built by CI from the
source here, so it is never a hand-built binary of unknown provenance.

Every CI run produces two APKs, and **which one you want depends on whether the
signing secret is set up**:

| Artifact | Installs? | Use it when |
| --- | --- | --- |
| `…-debug.apk` | yes | There is no signing key yet. Signed with the runner's own debug key. |
| `…-UNSIGNED.apk` | **no** | Never — it exists only so a keyless build still fails loudly rather than silently publishing. |
| `…-<version>.apk` | yes | Once `SHARECARD_KEYSTORE_BASE64` is set. This is the real release. |

The debug APK carries two costs worth knowing about. It is `debuggable`, so
anything with adb access to the device can attach to it — fine on a home
network, not something to leave on a machine you do not control. And the debug
key is generated per runner, so the **next** debug build will not install as an
update: uninstall first, which also clears `hosts.txt`. Setting the signing
secret (see [Signing](#signing)) removes both problems permanently.

It is meant to live on something that is always on and always on the network —
a FiiO R7, a tablet in a dock, an old phone on a charger — so the card is
reachable from a browser without anyone having to go and wake it up.

### Getting at it from another device

The app shows its own address, and so does the notification. Type it into any
browser on the same network:

```
http://<the device's IP>:8747
```

**This is open on your LAN with no password**, by design: the server is
read-only, it controls nothing, and the most an uninvited guest can learn is
which record is on. The port is fixed rather than OS-assigned so the URL survives
a reboot and stays worth writing down.

### Copying the card

The three platforms differ, and two of the differences cannot be papered over:

| Where | How |
| --- | --- |
| iPhone / iPad (Safari) | **Press and hold the card.** Copy, Save to Photos and Share, from the system menu. |
| Android (this app) | **Share…**, which leads everywhere a copy would. |
| Desktop browsers | **Copy** writes a real PNG to the clipboard; **Download** saves it. |

There is no Copy button on iOS or in the Android app, and that is deliberate
rather than unfinished. `ClipboardItem` with an image is not available to Safari
at all; on Android the image reaches the clipboard as a `content://` URI that the
pasting app has no grant to read, so the copy reports success and pastes nothing.
The Android shell removes the capability outright rather than leaving a button
that lies — merely declining to add it was not enough, because the WebView has
the API natively and the page's feature-detect passed. Both platforms have
something that genuinely works instead.

### Posting to Discord

Add a webhook once and a button appears on the card for it. Tap it and the card
is posted — no share sheet, no saving a file first. Several can be configured;
each gets its own button.

In Discord: **Edit Channel → Integrations → Webhooks → Copy Webhook URL**, then
paste it into **Webhooks** on the card page.

**It will always carry Discord's APP tag.** A webhook can be given a display
name and a picture — so a card can arrive as "Menzies" with your avatar rather
than as the webhook's own name — but Discord marks every webhook message APP
and there is no setting that removes it. That is deliberate on Discord's part:
a reader should be able to tell a person from an integration. Posting as your
account itself would mean driving a user token, which is self-botting and
against Discord's terms.

**The URL is a credential** — anyone holding it can post to that channel from
anywhere, for as long as it exists. So it is typed once and never comes back
out: no route returns it, the list shows only a mask, and the app itself does
the posting so no browser ever sees it.

Because of that, adding or removing a webhook is the one thing on this server
that is not open:

| Where you are | Adding / removing | Posting a card |
| --- | --- | --- |
| The device running the app | no PIN needed | no PIN needed |
| Any other device | needs the PIN | no PIN needed |

The PIN is shown in **Webhooks** on the device's own screen, and is served
nowhere else — a PIN handed to the network would be decoration. Posting is
deliberately ungated: it is the everyday action, and the worst it offers someone
on your wifi is a picture of your own album in your own channel.

### If it finds no players

**Press "Find my speakers" on the page.** It runs the whole discovery chain and
reports each step: which networks this device is on, what multicast managed on
each interface, what a direct scan of the subnet found, and which addresses were
tried. It ends with the most likely cause in plain words. That is far quicker
than guessing, and it is there because the first install of this app said "No
Sonos players found" and could say nothing else.

The app looks for players two ways, in this order:

1. **SSDP multicast**, sent from *every* network interface rather than whichever
   one the routing table prefers.
2. **A direct scan** of this device's own subnet for anything listening on port
   1400 — no multicast involved. This is what works on mesh systems, guest VLANs
   and switches with client isolation, which drop multicast outright.

If both fail, the near-certain cause is that the device is **on a different
subnet from the speakers** — a guest network, or a mesh putting wifi and
ethernet on separate ranges. A scan cannot cross that, but a hand-entered
address can: the whole household topology comes from any single player.

```
adb push hosts.txt /sdcard/Android/data/com.musicd.sharecard/files/hosts.txt
```

One address per line; `#` starts a comment. The file can also be written with a
file manager on the device itself. Restart the app afterwards.

## Roon needs letting in, once

Roon does not answer an extension until you approve it. On first run the app
says so; go to **Roon → Settings → Extensions** and enable **MusicD Share
Card**. The token is kept afterwards, so it never asks again — even across
restarts of a device that lives in a rack.

The app asks Roon for `transport` only. It does not browse your library, and it
has no transport controls of any kind.

## Verification

`:core` is a plain Kotlin/JVM module, which is what makes the protocol layer, the
zone selection and the whole API testable with no emulator.

```bash
./gradlew :core:test                 # the suite
node --check app/src/main/assets/web/app.js
npx eslint -c tools/eslint.config.mjs app/src/main/assets/web/*.js
node tools/check-css.js
node tools/check-sharecard.js
```

The DIDL parser has the most tests in the repository, on purpose: every source
the app claims to cover populates DIDL-Lite differently, and the differences
between them are the entire problem. The samples in `DidlTest` are the shapes
Sonos really returns, not tidied-up minimal ones.

`:app` cannot be built locally behind a proxy that blocks the Android Gradle
Plugin — CI builds it. See `CLAUDE.md` for the workaround that lets `:core:test`
run anyway.

## Signing

Releases are signed with a fixed key held in CI secrets
(`SHARECARD_KEYSTORE_BASE64`, `SHARECARD_KEYSTORE_PASSWORD`). Android refuses to
install an APK over one signed with a different key, so a per-machine debug key
would make every "update" un-installable. Without the secret the build still runs
and still produces an artifact — it is just named `UNSIGNED` and is not
published.

## Licence

MIT. See [LICENSE](LICENSE).

`sharecard.js` is © 2026 Lewis Menzies, ported from MusicD Remote Lite under the
same licence. Album blurbs come from Wikipedia and are CC BY-SA — the credit
drawn on the card is a licence condition on a picture that leaves the app, not a
nicety, and it does not get trimmed. Pitchfork scores are shown as a number and a
flag; no review text is carried, here or anywhere else.
