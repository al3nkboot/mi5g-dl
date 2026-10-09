<div align="center">
  <img src="branding/icon-squircle-512.png" width="96" height="96" alt="Mi5G Dl icon" />
  <h1>Mi5G Dl</h1>
  <p><strong>Personal Android media downloader build for <a href="https://x.com/midroid5G">@midroid5G</a>.</strong></p>
  <p><strong>Based on <a href="https://github.com/MaverickRox/VRKA-Android">VRKA by MVRK (MaverickRox)</a>, GPL-3.0.</strong></p>
</div>

---

## Attribution

> **Based on VRKA by MVRK (MaverickRox), GPL-3.0** — https://github.com/MaverickRox/VRKA-Android
>
> Mi5G Dl is a rebranded fork of VRKA Android 5.0.2 (upstream commit
> `06524a676c8d1045fcc50e45a9bce8bfe737fa58`). All credit for the downloader engine,
> browser fallback, UI and architecture goes to the upstream author. The original upstream
> README is preserved unchanged in [README-VRKA-UPSTREAM.md](README-VRKA-UPSTREAM.md).
> Desktop VRKA: https://github.com/MaverickRox/VRKA

## Modification notice (GPL-3.0 §5a)

This is a **modified version** of VRKA Android, changed by midroid5G on 2026-10-09.
See [MODIFICATIONS.md](MODIFICATIONS.md) for the full list. In short:

- App name `Mi5G Dl`, applicationId `com.midroid.mi5gdl` (installs alongside VRKA; Kotlin package `com.mvrk.vrka` left unchanged).
- New launcher/notification icon ("5G" + download arrow, dark purple). Purple accent + AMOLED black theme kept.
- Default download folder `Download/Mi5G Dl`.
- In-app *App Updates* (VRKA GitHub self-updater) **disabled** so upstream VRKA APKs are never installed over this app. yt-dlp and uBlock Origin component updates work as upstream.
- versionName `1.0.0 (based on VRKA 5.0.2)`, versionCode `1`.

## What it does

Native Kotlin/Jetpack Compose downloader: yt-dlp (+ FFmpeg, arm64-v8a) for direct extraction,
with a GeckoView browser fallback (uBlock Origin + media-detector WebExtension) for pages
yt-dlp cannot read directly. Persistent queue, history, format/resolution/audio selection,
subtitles, custom yt-dlp argument templates, optional H.264 conversion. Does not bypass DRM
or access controls. arm64-v8a only, Android 8.0+.

## Building

Requirements: JDK 17, Android SDK platform `android-37.1` (compileSdk 37.1, targetSdk 36),
build-tools 36.1+/37.0.0. No NDK needed (native libs are prebuilt AARs). ~2 GB Gradle heap.

```bash
./gradlew --no-daemon assembleDebug          # app/build/outputs/apk/debug (id com.midroid.mi5gdl.debug)
VRKA_SIGNING_PROPERTIES=/path/to/signing.properties ./gradlew --no-daemon assembleRelease
```

The release signing keystore is kept **outside** this repository and must never be committed.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE). Mi5G Dl remains GPL-3.0 like upstream VRKA.
Third-party components keep their own licenses — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
(GeckoView MPL-2.0, uBlock Origin GPL-3.0, yt-dlp Unlicense, youtubedl-android Apache-2.0,
FFmpeg LGPL-2.1+, Space Mono OFL-1.1, Kyant0/backdrop Apache-2.0 vendored sources).
