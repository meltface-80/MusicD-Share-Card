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

## Getting the card

Sideload the APK on Android 8.0 or newer. It's meant to live on something that
stays on — a FiiO R7, a tablet in a dock, an old phone on a charger.

Then open it from any browser in the house:

```
http://<the device's IP>:8747
```

The app shows its own address, and so does its notification.

| | |
| --- | --- |
| iPhone / iPad | Press and hold the card to copy or save it |
| Android | **Share…** |
| Desktop | **Copy** or **Download** |

## Discord

Paste a webhook URL once and a button appears on the card for it. Tap it and the
card is posted — no share sheet, no saving a file first. Add as many as you like;
each gets its own button.

You can give it your own name and picture, but Discord tags every webhook
message **APP** and there's no way to turn that off.

Get the URL from Discord: **Edit Channel → Integrations → Webhooks → Copy
Webhook URL**.

## If it can't find anything

Press **Find my speakers**. It runs the whole search and tells you what it found,
what it didn't, and the most likely reason.

For Roon, enable **MusicD Share Card** in **Roon → Settings → Extensions**. It
only asks once.

## Building it

CI builds the APK on every push; `dist/` always has the latest one.

```bash
./gradlew :core:test
```

`:core` holds the protocol layer, the source selection and the API, and is a
plain Kotlin/JVM module so all of it is testable with no emulator. See
[CLAUDE.md](CLAUDE.md) for how to work on it.

## Licence

MIT — see [LICENSE](LICENSE).

The card is [MusicD Remote Lite's](https://github.com/meltface-80/Android-Random-Remote),
ported so both apps draw the same picture, and the Roon client comes from there
too. Album text is from Wikipedia under CC BY-SA, which is why the credit stays
on the card.
