# Mi5G Dl — modifications to VRKA Android

Upstream: VRKA Android by MVRK (MaverickRox), https://github.com/MaverickRox/VRKA-Android, GPL-3.0
Base: v5.0.2, commit 06524a676c8d1045fcc50e45a9bce8bfe737fa58 (2026-10-05)
Modified by: midroid5G, 2026-10-09. All modifications are released under GPL-3.0.

| Area | Change |
|---|---|
| `app/build.gradle.kts` | applicationId `com.midroid.mi5gdl`; versionCode 1; versionName `1.0.0 (based on VRKA 5.0.2)`; BuildConfig `UPSTREAM_VRKA_VERSION`, `APP_SELF_UPDATE_ENABLED=false` |
| `gradle.properties` / `settings.gradle.kts` | Gradle heap 2 GB; root project name `Mi5G-Dl` |
| `res/values/strings.xml` | `app_name` = `Mi5G Dl` |
| Launcher / notification icons | New vector adaptive icon (`branding/make_icon.py`), monochrome themed icon, legacy PNGs regenerated, splash icon |
| `HomeScreen.kt` | Header title `Mi5G Dl` |
| `SettingsScreen.kt` | About: Mi5G Dl name/version, "Based on VRKA by MVRK (MaverickRox), GPL-3.0" + link, license link; App Updates row replaced by a disabled notice |
| `update/AppUpdateManager.kt` | Self-update gated off: no GitHub release check, download, staged-install restore or APK install; stale state cleared |
| `OutputPublisher.kt`, `VrkaDownloadManager.kt` | Default folder `Download/Mi5G Dl`; fallback file stems `Mi5GDl-…` |
| `DownloadService.kt`, `JobStore.kt`, `FailureClassifier.kt`, `CustomCommandTemplates.kt`, `DiagnosticStore.kt`, `DownloadModels.kt`, `JobsScreen.kt` | User-visible "VRKA" text → "Mi5G Dl" |

Unchanged on purpose: Kotlin package/namespace `com.mvrk.vrka`, internal log tags, WorkManager
names, yt-dlp output markers, the embedded "VRKA Media Detector" WebExtension (internal ID),
upstream copyright/vendored-code headers, LICENSE and THIRD_PARTY_NOTICES.md.
