# MusicD Share Card

Makes a share card for whatever you're playing and using whatever music app, and posts it wherever you want it.

https://meltface-80.github.io/MusicD-Share-Card/

![A share card: cover, title, artist, release year, Pitchfork score and a short description from Wikipedia](docs/screenshots/card.jpg)

## What it does

Open it and you get a card for the record that's on: cover, title, artist, year,
a short description from Wikipedia and a Pitchfork score where there is one.
Then share it, copy it, save it, or send it straight to Discord.

Everything fits one screen — card, buttons, links, suggestions — and the page
does not scroll. The card stays put until you press **Refresh**; leaving the app
and coming back does not throw it away.

<img src="docs/screenshots/app.jpg" width="360" alt="The app: the card, the room it is playing in, and buttons to download it or post it to Discord">

It works out what's playing by asking whoever actually knows:

- **Roon** — asked directly, through its own extension API
- **Sonos** — for anything the speakers stream themselves (Spotify Connect,
  Apple Music via the Sonos app, radio)
- **UPnP / DLNA** — any other renderer on the network

Every room it can see is in one list, whichever source found it. Leave it on
**Whatever's playing** and it picks the room that is actually playing — and when
two sources see the same room, the one that knows the most about the record
wins.

<img src="docs/screenshots/zones.jpg" width="360" alt="The room picker, listing rooms from Roon, Sonos and UPnP together">

## Install

[**Download musicd-share-card-0.35.0.apk**](dist/musicd-share-card-0.35.0.apk)
and sideload it on an Android device running 8.0 or newer. Open it and the card
is there. That's the whole thing — nothing else to run, no server, no account.

### Updating

The app updates itself. When a newer version is published it says so at the top
of the page; press **Update** and it downloads it, checks it, and hands it to
Android to install. Only the device running the app can start that — from
another device you need the PIN, the same one webhooks ask for.

Every build is signed with the same key, so a new version installs straight over
the top. The one exception was the last unsigned build — if you are still on one
of those, uninstall once and this is the last time.

## Using it from your other devices

If the Android device stays on — a FiiO R7, a tablet in a dock, an old phone on a
charger — anything else in the house can open the same card in a browser:

```
http://<the Android device's IP>:8747
```

The app shows that address on screen, and in its notification. Nothing extra is
installed on the iPad or phone; it's just a web page served by the app.

| | |
| --- | --- |
| iPhone / iPad | Press and hold the card to copy or save it |
| Android | **Share…** |
| Desktop | **Copy** or **Download** |

## Roon

**Roon won't report anything until you let it in.** In Roon, go to
**Settings → Extensions** and enable **MusicD Share Card**. It only asks once —
the approval is remembered, including across restarts.

Until you do, the app will say so rather than looking broken.

It asks Roon for transport only. It doesn't browse your library, and it has no
playback controls of any kind.

## Reading about it, and finding it

Under the card is a row of links for whatever is playing:

- the **Wikipedia** article the description came from, and the **Pitchfork**
  review the score came from
- a pre-filled search on **Qobuz, TIDAL, Spotify, Apple Music, Amazon Music,
  Deezer** and **Bandcamp**

They open the service's app if you have it and its web player if you don't.
They're searches rather than links to the album itself — that needs each
service's own id for the record, and their APIs need credentials. Qobuz is the
exception: it resolves the real album and opens the app on it.

There's no Roon link. Roon has no web player and no link scheme, so there's
nothing a tap could go to.

## If you like this, try these

Three acts worth hearing next, with one album each, under the links. They come
from ListenBrainz where it answers and Deezer otherwise — no account, no key.

Nothing keyless does album-to-album similarity, so these are artists *like* this
artist rather than records like this record, and the heading says so.

**Hold a service chip** — Qobuz, TIDAL, whichever — and it takes a tick. That's
where the suggestions link to from then on. It's remembered per device, so your
phone and the iPad can differ. There's no settings screen for it.

## Discord

Paste a webhook URL once and a button appears on the card for it. Tap it and the
card is posted — no share sheet, no saving a file first. Add as many as you like;
each gets its own button.

You can give it your own name and picture — pick a photo and it's uploaded to
Discord — but Discord tags every webhook message **APP** and there's no way to
turn that off.

Get the URL from Discord: **Edit Channel → Integrations → Webhooks → Copy
Webhook URL**.

<img src="docs/screenshots/webhooks.jpg" width="360" alt="The webhook settings: channel name, the name to post as, the webhook URL, a picture to upload, and the PIN">

## If something looks wrong

Press **Find my speakers**. It runs the whole search and tells you what it found,
what it didn't, and the most likely reason — plus what the Pitchfork and
suggestion lookups were asked and what they answered. An empty row has several
causes that look identical from the card, and that page tells them apart.

It's also where the last crash goes, so "it just closed" comes with a trace.

## Licence

MIT — see [LICENSE](LICENSE).

The card is [MusicD Remote Lite's](https://github.com/meltface-80/Android-Random-Remote),
ported so both apps draw the same picture, and the Roon client comes from there
too. Album text is from Wikipedia under CC BY-SA, which is why the credit stays
on the card.
