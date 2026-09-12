# MusicD Share Card

Makes a share card for whatever you're playing, and posts it wherever you want it.

<!-- Screenshots go here. -->

## What it does

Open it and you get a card for the record that's on: cover, title, artist, year,
and a short description from Wikipedia. Then share it, copy it, save it, or send
it straight to Discord.

It works out what's playing by asking whoever actually knows:

- **Roon** — asked directly, through its own extension API
- **Sonos** — for anything the speakers stream themselves (Spotify Connect,
  Apple Music via the Sonos app, radio)
- **UPnP / DLNA** — any other renderer on the network

## Install

[**Download musicd-share-card-0.14.0.apk**](dist/musicd-share-card-0.14.0.apk)
and sideload it on an Android device running 8.0 or newer. Open it and the card
is there. That's the whole thing — nothing else to run, no server, no account.

### Updating

The app updates itself. When a newer version is published it says so at the top
of the page; press **Update** and it downloads it, checks it, and hands it to
Android to install. Only the device running the app can start that — from
another device you need the PIN, the same one webhooks ask for.

One catch, for now: builds are signed with a throwaway key, so Android sees each
one as a different app and refuses to replace the old one. Until that changes
the app says so instead of offering the button, and you have to uninstall first.
Two repository secrets fix it permanently — see
[docs/signing.md](docs/signing.md).

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

## Finding the record elsewhere

Under the card is a row of links for whatever is playing: the Pitchfork review
the score came from, and a pre-filled search on Qobuz, TIDAL, Spotify, Apple
Music, Amazon Music and Deezer. They open the app if you have it and the web
player if you don't.

They're searches rather than links to the album itself — that would need each
service's own id for the record, which needs their APIs and their credentials.

There's no Roon link: Roon has no web player and no link scheme to open.

## Discord

Paste a webhook URL once and a button appears on the card for it. Tap it and the
card is posted — no share sheet, no saving a file first. Add as many as you like;
each gets its own button.

You can give it your own name and picture — pick a photo and it's uploaded to
Discord — but Discord tags every webhook message **APP** and there's no way to
turn that off.

Get the URL from Discord: **Edit Channel → Integrations → Webhooks → Copy
Webhook URL**.

## If it can't find anything

Press **Find my speakers**. It runs the whole search and tells you what it found,
what it didn't, and the most likely reason.

## Licence

MIT — see [LICENSE](LICENSE).

The card is [MusicD Remote Lite's](https://github.com/meltface-80/Android-Random-Remote),
ported so both apps draw the same picture, and the Roon client comes from there
too. Album text is from Wikipedia under CC BY-SA, which is why the credit stays
on the card.
