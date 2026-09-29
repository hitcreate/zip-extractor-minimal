# Clean Zip (Minimal, No Bloat)

A tiny Android app to open and extract archives. No ads, no tracking, no unnecessary
permissions, no bloat. Everything runs on-device — nothing is uploaded.

Built with Kotlin + Jetpack Compose. Files are chosen through the Storage Access
Framework (SAF), so the app needs **no storage permissions**.

## Formats

| Reads | Engine |
|---|---|
| ZIP (plain, ZipCrypto, WinZip AES, split) | zip4j |
| 7z | Apache Commons Compress |
| RAR / RAR5 (incl. password) | junrar 8.x |
| TAR, tar.gz/.tgz, tar.bz2/.tbz2, tar.xz/.txz, tar.z/.taz | Commons Compress (+ xz) |
| gz, bz2, xz, lzma, z, brotli (single file) | Commons Compress (+ xz, brotli) |
| CPIO, AR (incl. `.deb`) | Commons Compress |

Format is detected from magic bytes, not just the extension. An optional password field
covers encrypted ZIP / 7z / RAR.

Not yet supported: multi-volume (`.part1.rar`), zstd (`.zst` / `.tar.zst`), and any
format that needs a native 7-Zip binding.

## Features
- Pick any supported archive (system file picker)
- Preview the file/folder list before extracting
- Extract to any folder you choose (Downloads, Documents, SD card, …)
- Progress + status, with a zip-slip guard on entry paths
- Small APK (1.79 MB signed release, v1.2)

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:testReleaseUnitTest --rerun   # 15 JVM format tests — --rerun matters, a cached UP-TO-DATE run is not a pass
./gradlew assembleRelease                    # unsigned release APK
```

Sign the unsigned output with a keystore (zipalign then apksigner):

```bash
BT=$ANDROID_HOME/build-tools/35.0.0
$BT/zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk out-aligned.apk
$BT/apksigner sign --ks keystore/release.jks --out CleanZip-1.1.apk out-aligned.apk
```

Release lint is disabled in `app/build.gradle.kts` (offline builds).

## Distribution

The signed release is published on the tailnet for sideloading:

- **Page:** `https://cmdu-omarchy.taild956f1.ts.net:9356/`
- **APK:** `https://cmdu-omarchy.taild956f1.ts.net:9356/CleanZip-1.2.apk` (1.1 kept alongside)

Source directory: `~/public-tailnet/zip-extractor` (served via `tailscale serve` :9356).
Update: drop the new APK there and re-run `~/bin/verify-delivery` on the APK URL.

## Verification status (as at v1.2)

- 15/15 format tests execute and pass — asserted by parsing the JUnit XML for
  `failures=0 errors=0 skipped=0`, not by trusting Gradle's exit code alone. A
  `:app:testReleaseUnitTest` task reported `UP-TO-DATE` on a stale cache; `--rerun` is required.
- `dist/CleanZip-1.2.apk` payload is CRC+size identical to a build of current source
  (only the `META-INF/CLEANZIP.*` signature artefacts differ), so the shipped APK is
  not a stale artefact.
- Served APK over tailnet HTTPS re-verified: `apksigner verify` v2+v3, 1 signer,
  `versionName='1.2'`, `versionCode=3`, and no storage or network permissions.

## Permissions
- None at install time. Everything goes through the modern file picker (SAF).

## License
Public domain / MIT — do whatever you want with it.
