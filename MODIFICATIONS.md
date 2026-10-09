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

## 1.1.0 (versionCode 2), 2026-10-09

All new code is in `app/src/main/java/com/mvrk/vrka/mi5g/` (GPL-3.0).

| Area | Change |
|---|---|
| `mi5g/share/` | `ShareActivity` (translucent bottom sheet for SEND text/plain and VIEW on common media hosts). `ShareUrlExtractor` pulls the link out of shared text. |
| `mi5g/trim/TrimSpec.kt`, `mi5g/ui/TrimEditor.kt` | Time parsing and validation, yt-dlp `--download-sections` plus `--force-keyframes-at-cuts`, FFmpeg post-trim fallback args, and the trim UI (fields + range slider) |
| `HomeScreen.kt` | New "Trim" card with the metadata length probe; trim validation moved to `TrimSpec` |
| `DownloadRequestFactory.kt`, `VrkaDownloadManager.kt` | Trim args from `TrimSpec`. FFmpeg trim fallback after yt-dlp/browser outputs. `probeMedia`. History tombstones and synced-history import |
| `Presentation.kt`, `ConfigurationSummary.kt`, `JobsScreen.kt` | Trim label in the readout, queue and history. "Synced from <device>" marker |
| `mi5g/update/` | Self-updater for `al3nkboot/mi5g-dl` only: token in Android Keystore (AES-GCM), API asset download, size/SHA-256/package/versionCode/signing-cert checks, optional daily check with notification. `REQUEST_INSTALL_PACKAGES` re-added. Upstream `AppUpdateManager` stays disabled. |
| `mi5g/sync/` | SAF JSON-file sync: per-key LWW settings, history with UUIDs, updatedAt and tombstones, URL+time dedupe. Never syncs secrets or device paths. WorkManager periodic and on-open sync |
| `JobStore.kt` | Record schema 2: additive migration (`request.trimPrecise`, `syncedFrom`) |
| `SettingsRepository.kt`, `SettingsScreen.kt` | New settings (skip share sheet, precise trim default, daily update check), sync export/apply, and new Settings sections |
| `AndroidManifest.xml` | `ShareActivity` added. The SEND filter moved off `MainActivity`. `REQUEST_INSTALL_PACKAGES` added |

## 1.1.1 (versionCode 3), 2026-10-09

| Area | Change |
|---|---|
| `mi5g/update/UpdateRules.kt` | Validates the public `browser_download_url` (must point to this repo, this tag and this asset). Chooses the download URL based on whether a token is set. Detects rate limits (403 with `x-ratelimit-remaining: 0`/`retry-after`, or 429) and works out when to retry. Error messages no longer assume the repo is private. |
| `mi5g/update/GitHubClient.kt`, `Mi5gUpdateManager.kt` | Checks and downloads no longer need a token. Rate-limit state is persisted, so no requests are made until the reset time, and one background retry runs after the reset. The daily check runs without a token. |
| `mi5g/ui/Mi5gSettingsSections.kt` | The token moved to an optional "Advanced" section with an explanation. The Check button is disabled while rate-limited. |

## 1.1.2 (versionCode 4), 2026-10-09

**Root cause** of "Direct extraction failed (COOKIES) → blank browser → Couldn't find a downloadable format":
1. yt-dlp's YouTube bot-check error ("Sign in to confirm you're not a bot. Use --cookies-from-browser or --cookies…") contains the word "cookies", so `FailureClassifier` labelled it `COOKIES`.
2. `COOKIES` on a YouTube URL was allowed to open the GeckoView browser fallback.
3. The YouTube web player fetches media with SABR/UMP POST requests (`application/vnd.yt-ump`), not plain media URLs, so the media detector could never capture a downloadable stream.
4. The blank grey page itself could not be reproduced here (no emulator/KVM). The fallback runs a private session with strict tracking protection and uBlock, but nothing in the bundled extensions blocks YouTube. Because YouTube no longer reaches the fallback, the user no longer sees it. For other sites, page-load progress and errors are now shown.

| Area | Change |
|---|---|
| `mi5g/youtube/YouTubeRecovery.kt` (new) | Detects the bot check (straight or curly apostrophe) and separates retryable from permanent errors. Defines the client plans, which were checked against the bundled yt-dlp 2026.08.19 `INNERTUBE_CLIENTS`. Without cookies it tries `visionos,web_embedded`, then `android_vr,android` (may be limited to 360p). With cookies it tries `web_embedded,web_safari`, then `visionos,android_vr`. `tv*` is never paired with cookies. Also builds the arguments, the retry driver and the final message. |
| `VrkaDownloadManager.kt` | After the default attempt fails on a YouTube page URL, it retries at most twice with 2 s/5 s back-off and shows the status in the Queue. The final error is "YouTube bot check: …", with Retry / sign-in / Nightly advice. |
| `DownloadRequestFactory.kt` | `YouTubeContext`: `--cookies` and `--extractor-args` are added only for YouTube page URLs, never for resolved media or other sites. |
| `engine/FailureClassifier.kt` | The bot check is classified as `AUTH_REQUIRED`, not `COOKIES`. YouTube is never eligible for the browser fallback. |
| `Presentation.kt` | Queue/History title "YouTube bot check — try again later", with an explanation. |
| `mi5g/youtube/NetscapeCookies.kt`, `YouTubeCookieStore.kt` (new) | Writes a Netscape cookies.txt with youtube.com/google.com cookies only, after filtering expired cookies and rejecting tab/newline injection. The file is written atomically with owner-only permissions to `noBackupFilesDir`; on sign-out it is overwritten, then deleted. |
| `mi5g/youtube/YouTubeAccountManager.kt`, `YouTubeSignInActivity.kt`, `assets/extensions/mi5g-cookies` (new) | Sign-in runs in a private GeckoView session. A built-in WebExtension (cookies + native messaging) exports the cookies after the yt-dlp-recommended `youtube.com/robots.txt` step. Sign out clears the file and the Gecko site data for youtube.com/google.com. |
| Settings | New "YouTube account (optional)" section. The yt-dlp update channel now defaults to Nightly for new installs. |
| `engine/FallbackEngine.kt`, `VrkaRoot.kt` | The fallback header shows page-load progress and load errors. |

Limitations: not tested on a physical device. Google may refuse sign-in in embedded browsers. Cookies and alternative clients don't always get past the bot check, which depends mostly on IP/network reputation. The Android clients may be limited to 360p. yt-dlp's EJS challenge solver is downloaded at runtime (`--remote-components ejs:github`).

Unchanged on purpose: Kotlin package/namespace `com.mvrk.vrka`, internal log tags, WorkManager
names, yt-dlp output markers, the embedded "VRKA Media Detector" WebExtension (internal ID),
upstream copyright/vendored-code headers, LICENSE and THIRD_PARTY_NOTICES.md.
