# CarHome

A small, dependency-free Android launcher for car head units (landscape, big touch targets, dark theme).

- Clock, date and now-playing controls (works with YouTube Music, Spotify, etc.)
- 5 pinned apps + app drawer in square tiles, with simple line icons or the original ones
- Drive panel: GPS speed and heading, and 3 one-tap Google Maps destinations with custom icons
- System info: screen, CPU, RAM, storage, internet, temperature, and the real Android version (from the API level)
- Screen-edge correction for panels whose borders are hidden under the bezel

Min Android 5.0 (API 21). About 90 KB.

## Build

No Android Studio or Gradle needed. On Ubuntu/Debian (or WSL):

```bash
sudo apt install openjdk-17-jdk aapt apksigner android-sdk-platform-23 dalvik-exchange zipalign
./build.sh
```

This produces `CarHome.apk`.

**Signing key:** `build.sh` signs with `carhome.keystore`. It isn't in this repo on purpose.
Put your existing keystore in the project folder before building, so the new APK installs as an update.
Without it, the script creates a new key, and the APK will only install after uninstalling the old version.

## Install

Copy `CarHome.apk` to the head unit (USB stick or download), open it, then press Home and choose CarHome → Always.
For now-playing controls: ⚙ → Enable now-playing controls.

## Credits

Icons: [Tabler Icons](https://tabler.io/icons), MIT license (see `LICENSE-tabler-icons.txt`).
