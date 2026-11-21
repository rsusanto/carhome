# CarHome

[![Build APK](https://github.com/rsusanto/carhome/actions/workflows/build.yml/badge.svg)](https://github.com/rsusanto/carhome/actions/workflows/build.yml)

A small, dependency-free Android launcher for car head units (landscape, big touch targets, dark theme).

> This is a vibe-coded app: it was written almost entirely by an AI coding assistant, with a human steering, testing on a real head unit, and reviewing.

- Clock, date and now-playing controls (works with YouTube Music, Spotify, etc.)
- 5 pinned apps + app drawer in square tiles, with simple line icons or the original ones
- Drive panel: GPS speed and heading, and 3 one-tap Google Maps destinations with custom icons
- System info: screen, CPU, RAM, storage, internet, temperature, and the real Android version (from the API level)
- Screen-edge correction for panels whose borders are hidden under the bezel

Min Android 5.0 (API 21), target API 23. About 90 KB.

## Download

Grab the latest signed APK from [Releases](https://github.com/rsusanto/carhome/releases/latest).
On the head unit you can open this link directly in a browser:

```
https://github.com/rsusanto/carhome/releases/latest/download/CarHome.apk
```

## Install

Copy `CarHome.apk` to the head unit (USB stick or download), open it, then press Home and choose CarHome → Always.
For now-playing controls: ⚙ → Enable now-playing controls.

## Project layout

```
AndroidManifest.xml        package id.rsusanto.carhome, base versionName
build.sh                   the whole build (no Gradle)
src/id/rsusanto/carhome/   Java sources; the UI is built in code, no layout XML
res/drawable/ti_*.xml      Tabler outline icons converted to VectorDrawables
res/values/                colors, strings, styles
.github/workflows/         CI build and release
```

Constraints: Java 8 source level with no lambdas or method references (the old `dx` can't handle them),
and no AndroidX or other libraries.

## Build locally

No Android Studio or Gradle needed. On Ubuntu/Debian (or WSL):

```bash
sudo apt install openjdk-17-jdk aapt apksigner android-sdk-platform-23 dalvik-exchange zipalign
./build.sh
```

This produces `CarHome.apk` (version from git, see below). The pipeline is `aapt` → `javac` → `dalvik-exchange` (dx) → `aapt` → `zipalign` → `apksigner`.

**Signing key:** `build.sh` signs with `carhome.keystore` (alias `carhome`). It isn't in this repo on purpose.
Put your existing keystore in the project folder before building, so the new APK installs as an update.
Without it, the script creates a new key, and the APK will only install after uninstalling the old version.
The key password is read from `KEYSTORE_PASSWORD` and defaults to `android`.

## Versions and releases (automatic)

Nothing to bump by hand. `build.sh` takes the version from git:

- `versionCode` = 100 + the number of commits, so every new build installs as an update over any older one
- `versionName` = the base in `AndroidManifest.xml` + the commit count, e.g. `1.5.13`
  (`-dev` is added to local builds with uncommitted changes)

Change `android:versionName` in the manifest only for a milestone (say `2.0`); the build number keeps counting.
Don't force-push or rewrite history on `main`: a lower commit count means a lower `versionCode`, which won't install.

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs on every push to `main`:
it builds the signed APK and publishes it as a Release (`v1.5.13`), keeping the last 10.
Install whenever you like from the `releases/latest` link above. A failed build publishes nothing.
You can also start a rebuild from the **Actions** tab (**Run workflow**); that only uploads an artifact.

### One-time setup

Add the keystore as a repository secret named `KEYSTORE_BASE64`
(repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**), or with the GitHub CLI:

```bash
gh secret set KEYSTORE_BASE64 < <(base64 -w0 carhome.keystore)
```

If you use a password other than `android`, also add it as `KEYSTORE_PASSWORD`.
If `KEYSTORE_BASE64` is missing, the build fails on purpose instead of signing with a new key.
The decoded key is deleted from the runner at the end of each run.

## Credits

Icons: [Tabler Icons](https://tabler.io/icons), MIT license (see `LICENSE-tabler-icons.txt`).
