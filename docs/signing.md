# Turning on updates over the top

Android refuses to install an APK over one signed with a **different**
certificate. All it says is *App not installed* — no reason, nothing in a log
anybody will see.

Without a signing key CI mints a fresh debug key on every runner, so every
published build carries a new certificate and none of them can replace another.
The in-app updater knows this: while `dist/latest.json` says `"signed": false`
it shows the new version but refuses to download it, and says to uninstall
first.

**The key has already been made.** All that is left is pasting two values into
the repository settings. Nothing needs to be installed and nothing needs to be
run.

## The one thing to do

**Settings → Secrets and variables → Actions → New repository secret**, twice:

| Name | Value |
| --- | --- |
| `SHARECARD_KEYSTORE_BASE64` | the contents of the `SHARECARD_KEYSTORE_BASE64.txt` file you were given — one long line, no spaces and no line breaks |
| `SHARECARD_KEYSTORE_PASSWORD` | the 32-character password you were given |

That is the whole change. The workflow already looks for exactly these names and
starts publishing the **release** APK instead of the debug one on the next
build.

Keep a copy of both somewhere safe — a password manager, not this repository.
They are the only copy of the key, and losing them means everybody with the app
installed has to uninstall before they can install again.

## What is already done

- **The keystore.** EC secp256r1, alias `sharecard`, `CN=MusicD Share Card`,
  valid until January 2054. A certificate that expires is a certificate that
  ends updates, so it is deliberately long-lived.
- **The pin.** `tools/release-key.sha256` already holds that certificate's
  fingerprint, so from the first signed build CI **fails** anything signed with
  a different key. That check is skipped while the secret is unset, so
  committing it early costs nothing.

## What this does not fix

The build installed right now was signed with a throwaway key, so it has to be
uninstalled once. Install the first signed release onto a clean slate; every
version after that installs over the top, and the app offers them itself.

## If a new key is ever needed

```bash
keytool -genkeypair -v \
  -keystore sharecard-release.jks \
  -storetype PKCS12 \
  -alias sharecard \
  -keyalg EC -groupname secp256r1 \
  -validity 10000 \
  -dname "CN=MusicD Share Card, O=Music Duck"

base64 -w0 sharecard-release.jks                # Linux
base64 -i sharecard-release.jks | tr -d '\n'    # macOS
```

`-alias sharecard` matters — it is what `app/build.gradle.kts` looks for. Then
replace both secrets, and replace the fingerprint in `tools/release-key.sha256`
with the new one (`keytool -list -v` prints it as SHA256; lower-case it and
remove the colons). Everybody reinstalls once.
