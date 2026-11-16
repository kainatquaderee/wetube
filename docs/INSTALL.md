# Installing WeTube

WeTube ships as a signed APK attached to every [GitHub release](https://github.com/kainatquaderee/wetube/releases).
It is not on the Play Store, so installation is a one-tap sideload — pick whichever
route below suits you.

- [Requirements](#requirements)
- [Recommended: install with Obtainium and get updates automatically](#recommended-install-with-obtainium-and-get-updates-automatically)
- [Install the APK by hand](#install-the-apk-by-hand)
- [Install a CI build](#install-a-ci-build)
- [Build from source](#build-from-source)
- [Verify the download](#verify-the-download)
- [Updating](#updating)
- [Uninstalling](#uninstalling)
- [Troubleshooting](#troubleshooting)

## Requirements

- An Android phone or tablet running **Android 8.0 (API 26) or newer**. WeTube
  targets Android 15 (API 35).
- Roughly 30 MB of free space for the app, plus room for anything you download
  for offline playback.
- No account, no Google Play Services and no sign-in are required to install or
  watch. Signing in is optional and only adds your own feed and subscriptions.

There is one build for every device; there are no per-architecture variants to
choose between.

## Recommended: install with Obtainium and get updates automatically

[Obtainium](https://github.com/ImranR98/Obtainium) is a small open-source app that
installs and updates other apps straight from their GitHub releases. Point it at
the WeTube repository once and every future version arrives with a tap — no
side-loading from a browser again.

1. **Install Obtainium.** Download it from the
   [Obtainium releases page](https://github.com/ImranR98/Obtainium/releases/latest)
   and install the APK the same way you would install WeTube (your browser will
   ask to allow installs from this source the first time).
2. **Add the WeTube repository.** Open Obtainium and tap **Add App**. Paste the
   repository address into the *App source URL* field:

   ```text
   https://github.com/kainatquaderee/wetube
   ```

   Tap **Add**, and Obtainium reads the project's releases and picks the APK for
   you.
3. **Install.** Tap the WeTube entry, then **Install**. Obtainium asks for the
   usual install permission once.
4. **Let it keep you current.** From now on, when WeTube publishes a new release
   Obtainium shows it in its own list. Tap to update — no need to revisit this
   page.

A few things worth knowing about the Obtainium route:

- Obtainium reads releases, so an app that only publishes CI artifacts (see
  below) is not tracked automatically. WeTube publishes both.
- You can point Obtainium at a specific tag or pre-release if you want to pin a
  version, and it will leave that app alone until you change the setting.
- Obtainium needs no GitHub account. It talks to the public API the same way a
  browser would.

## Install the APK by hand

If you would rather not use an updater, install each release yourself:

1. Open the [latest release](https://github.com/kainatquaderee/wetube/releases/latest) and download
   `app-release.apk` from its **Assets**.
2. Open the downloaded file. Android asks once to allow installs from your
   browser or file manager — allow it, then confirm the install.
3. Launch **WeTube**.

Alternatively, with a computer and `adb` (Android platform-tools) connected over
USB:

```bash
adb install -r app-release.apk
```

`-r` reinstalls over an existing copy, keeping your data. An update will only be
accepted if it is signed with the same key as the installed build — release
builds all use the project key, so they update cleanly in place.

## Install a CI build

Every push that passes runs the **Build** workflow, and its APK is attached to
the run as an artifact. These track the newest code rather than a tagged
release:

1. Open the [Actions tab](https://github.com/kainatquaderee/wetube/actions/workflows/android.yml) and pick
   the most recent successful run.
2. Under **Artifacts**, download the debug APK zip, unzip it, and install the
   APK on the device.

The debug build has a `.debug` application id, so it can sit beside a release
install without replacing it. Debug builds are signed with a debug key and are
not interchangeable with release builds for updates.

## Build from source

Requirements: JDK 17+, Android SDK 35, and either Android Studio or the Gradle
CLI.

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Then install whichever one you built:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Signing a release build yourself means it will *not* update over the published
release, because the signing key differs. Uninstall the published build first, or
stay on debug builds while developing.

## Verify the download

Release APKs are signed with the project key. With Android platform-tools you can
confirm the signer before installing:

```bash
apksigner verify --print-certs app-release.apk
```

The certificate subject prints `CN=WeTube`. If it does not, the file did not come
from this project — do not install it.

## Updating

- **With Obtainium**, updates appear in the app automatically; tap to install.
- **By hand**, download the APK from the newest release and install it over the
  old one. `adb install -r app-release.apk` does the same.
- Your history, favourites, resume points, local subscriptions and preferences
  are kept across an update. Back them up any time from **Settings → Backup &
  restore** if you want an extra copy.

Never mix builds signed with different keys: if Android refuses an update with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall the existing app first (which
clears its local data) and install fresh.

## Uninstalling

Uninstall WeTube like any other app. Everything it stored on the device —
history, favourites, downloads and preferences — is removed with it. If you want
to keep a copy, export a backup first; the file is yours and it is the only thing
that preserves your library across a reinstall.

## Troubleshooting

**Android blocks the install.** Your browser or file manager needs permission to
install unknown apps. Android offers to open the right settings screen when you
tap the APK the first time.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** The new APK is signed with a different
key than the installed one. Uninstall the old build and install the new one.

**`INSTALL_FAILED_VERSION_DOWNGRADE`.** You are installing an older version over
a newer one. Install the newer build, or uninstall first.

**Obtainium shows no releases.** Check that the *App source URL* is exactly the
repository address above. If you only see CI builds and no releases, you are
probably pointing at a fork or a project that has not published a release yet.

**Playback says it could not start.** That is a YouTube-side check rather than an
install problem — retry, or try again later. The player says plainly which wall
it hit.

---

Still stuck? [Open an issue](https://github.com/kainatquaderee/wetube/issues) with your Android version and
what you tried.

---

WeTube exists because Android lets you install software from anywhere. Google's
planned developer-verification requirement would change that — see
[Keep Android Open](https://keepandroidopen.org) to learn more and add your
voice. Installing over `adb` is expected to keep working.
