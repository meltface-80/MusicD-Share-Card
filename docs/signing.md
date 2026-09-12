# Turning on updates over the top

Android refuses to install an APK over one signed with a **different**
certificate. All it says is *App not installed* — no reason, nothing in a log
anybody will see.

CI mints a fresh debug key on every runner, so today every published build
carries a new certificate and none of them can replace another. The in-app
updater knows this: while `dist/latest.json` says `"signed": false` it shows the
new version but refuses to download it, and tells you to uninstall first.

Setting the two secrets below is what switches that off, permanently. It takes
about two minutes and it is the only thing standing between here and updates
that install themselves.

## 1. Make a keystore

Run this **on your own machine**, not here. The file it writes is private key
material — it must never be committed, and it should be backed up somewhere you
will still have in a year.

```bash
keytool -genkeypair -v \
  -keystore sharecard-release.jks \
  -storetype PKCS12 \
  -alias sharecard \
  -keyalg EC -keysize 256 \
  -validity 10000 \
  -dname "CN=MusicD Share Card, O=Music Duck"
```

It asks for a password twice. Use one password for the store; the build uses the
same one for the key.

- **`-alias sharecard` matters.** That is the alias `app/build.gradle.kts` looks
  for. A different one needs a third secret, `SHARECARD_KEY_ALIAS`.
- **EC rather than RSA** only so the base64 below is a few lines instead of
  dozens. Either works.
- **`-validity 10000`** is about 27 years. A certificate that expires is a
  certificate that ends updates, so this is deliberately long.

## 2. Turn it into one line

```bash
base64 -w0 sharecard-release.jks > sharecard-release.b64     # Linux
base64 -i sharecard-release.jks | tr -d '\n' > sharecard-release.b64   # macOS
```

## 3. Put it in the repository

**Settings → Secrets and variables → Actions → New repository secret**, twice:

| Name | Value |
| --- | --- |
| `SHARECARD_KEYSTORE_BASE64` | the whole contents of `sharecard-release.b64` |
| `SHARECARD_KEYSTORE_PASSWORD` | the password you typed into `keytool` |

Nothing else changes. The workflow already looks for exactly these, and starts
publishing the **release** APK instead of the debug one the moment they exist.

## 4. Pin the certificate (one commit, after the first signed build)

The next build prints a line like:

```
No tools/release-key.sha256 yet. Commit the SHA-256 printed above to pin it.
  a1b2c3…  # the certificate this APK is signed with
```

Create `tools/release-key.sha256` with that hash on a line of its own and commit
it. From then on CI **fails** any build signed with a different key, which is
the check that would have caught the original problem before it shipped.

## What this does not fix

The build you have installed right now was signed with a throwaway key, so it
has to be uninstalled once. Every version after that installs over the top, and
the app will offer them to you itself.

## If the keystore is lost

A new one can be generated, but everybody with the app installed has to
uninstall before they can install again. Keep the backup.
