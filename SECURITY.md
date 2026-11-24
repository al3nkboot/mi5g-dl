# Security Policy

## Supported Versions

| Version | Supported | Notes |
| :--- | :--- | :--- |
| 1.3.x (based on VRKA 5.1.5) | :white_check_mark: | Current Mi5G Dl line |
| 1.2.x and older | :x: | Upgrade to 1.3.0; queue/history are imported into Room on first launch |

## Reporting a Vulnerability

Report vulnerabilities through the [GitHub security page](https://github.com/al3nkboot/mi5g-dl/security) for this fork. For issues that also exist in upstream VRKA, you may also report them at [VRKA-Android security](https://github.com/MaverickRox/VRKA-Android/security). Include reproduction steps and sanitized logs; do not attach cookies, authorization values, signed media URLs, or personal device identifiers.

## Release Integrity

Official Mi5G Dl release APKs are production-signed with the Mi5G Dl signing key (APK Signature Scheme v2). Verify the SHA-256 published with the GitHub release before installation. In-app updates come only from `al3nkboot/mi5g-dl` and are refused if the signing certificate does not match the installed app. Upstream VRKA release provenance is documented in [docs/RELEASE_PROVENANCE.md](docs/RELEASE_PROVENANCE.md) and does not apply to Mi5G Dl APKs.

- **v5.1.4 Release APK:** `VRKA-Android-v5.1.4.apk`
- **v5.1.4 SHA-256:** `5535291ff60c89852f1b2b960379a3b2637576e833ab3951759522110bba5d97`
- **v5.1.4 updater manifest SHA-256:** `3b8cbf09cd3e836fe655714ebde7486d727f4988cb98734ce045a7d8ceb5d18e`
- **v5.1.3 Release APK (historical):** `VRKA-Android-v5.1.3.apk`
- **v5.1.2 SHA-256:** `3a28262db691858757afd9edbded1bedfcbbcef5d1eeeb56743a16d08eeafe9a`
- **v5.1.1 Release APK (historical):** `VRKA-Android-v5.1.1.apk`
- **v5.1.1 SHA-256:** `781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8`
- **Android Signing Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`

The `release` build type requires explicit signing properties; it has no debug-key fallback. Never publish signing properties, keystores, passwords, or private signing keys in this repository or artifact package. Key custody, backup, compromise response, and rotation are documented in [docs/KEY_MANAGEMENT.md](docs/KEY_MANAGEMENT.md); native component provenance is in [docs/NATIVE_BINARY_INVENTORY.md](docs/NATIVE_BINARY_INVENTORY.md); dependency controls are in [docs/DEPENDENCY_MANAGEMENT.md](docs/DEPENDENCY_MANAGEMENT.md).

## OpenPGP Release Signing and Trust Anchor

All official application updates are cryptographically authenticated using detached OpenPGP signatures over versioned release manifests (`VRKA-Android-vX.Y.Z.manifest.json`).

The application binary embeds and pins the official VRKA OpenPGP release signing public key:
- **User ID:** `VRKA Official Release Signer (VRKA Persistent Release Signing Key) <security@vrka.org>`
- **Key ID:** `0xD519380BEBA1B9F7`
- **Key Fingerprint:** `71165A658B3A8612AC2568C6D519380BEBA1B9F7`
- **Key Algorithm:** RSA 4096-bit
- **Resource Location:** `app/src/main/res/raw/vrka_release_pubkey.asc`

### Cryptographic Policy and Downgrade Defense
- **Algorithm Enforcement:** Release signatures must use exactly SHA-256, SHA-384, or SHA-512. SHA-224, SHA-1, MD5, RIPEMD-160, and every other unspecified digest are rejected. Signature keys must be RSA; DSA and other key algorithms are rejected.
- **Issuer Fingerprint Verification:** Detached signatures must contain an issuer fingerprint subpacket in the **hashed** subpacket section that exactly matches the pinned trust anchor. Fingerprints that are missing, present only in the unhashed section, malformed, or mismatched are rejected.
- **Anti-Rollback Protection:** Monotonically increasing `metadata_revision` numbers prevent replay attacks and downgrade of release metadata.
- **Anti-Downgrade Protection:** Updates with `versionCode` less than the currently installed version are rejected before download.
- **Anti-Freeze Protection:** Release manifests enforce an expiration timestamp (`expires_at`), preventing freeze attacks where an attacker serves stale but validly signed metadata.
- **Certificate Pinning:** The manifest binds the target APK to the production signing certificate SHA-256 (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`).

## Privacy and network paths

VRKA contains no application analytics, advertising, or telemetry SDK. It still performs the
documented automatic update checks and user-requested media/browser network traffic; the exact
destinations and data exposure are recorded in [docs/PRIVACY_NETWORK.md](docs/PRIVACY_NETWORK.md).
Do not summarize this as "zero network traffic" or "zero tracking"; the narrower verified claim is
that there is no application analytics/telemetry upload.

## Security Boundaries

- Custom yt-dlp templates are argument arrays passed to yt-dlp, not shell commands. Validation blocks shell hooks, external downloaders, config files, cookie files, and output-path overrides. VRKA owns staging and publication paths.
- Browser Fallback always hands the selected candidate to the standard yt-dlp request path. It keeps the original public page URL as the job source and clears custom template arguments and user-defined headers before transfer construction. Captured browser headers are validated and origin-bound by `HeaderValidation`.
- User URLs are validated HTTP(S) inputs. They remain individual argv values; no shell is used.
- Failed-download diagnostics may retain the original submitted source URL for reproducibility, but credential-like query values, embedded signed CDN URLs, and source-userinfo are redacted. Successful downloads do not add source URLs. Diagnostic export shows a warning and requires an explicit opt-in to include the full original source URL. Avoid sharing diagnostic exports containing site or account identifiers.
- GeckoView navigates in a task-scoped private session. The Settings clear action clears GeckoView cookies, DOM storage, auth sessions, site data, caches and the in-memory candidate bridge. It does not cancel an active task.
- yt-dlp is the normal and Browser Fallback transfer engine. FFmpeg is used for local processing, probing and validation. The active browser extensions are the VRKA media detector and uBlock Origin. Dormant Media3 and gated N_m3u8DL-RE code are not advertised or routed as active download capabilities.

## Out of Scope

VRKA does not bypass DRM, authentication, payment, CAPTCHA, consent, or other site access controls. A compromised operating system or device can defeat application sandbox protections. A site's behavior and third-party component availability can change after release.
