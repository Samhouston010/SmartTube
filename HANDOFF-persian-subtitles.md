# SmartTube — Persian subtitles fork: status & handoff

_Last updated: 2026-10-24_

## What this fork does
Persian (and other auto-translated) captions are **not** taken from YouTube's own
auto-translate (ASR + YouTube MT stacked, reads badly). Any caption request carrying
YouTube's `&tlang=` marker is redirected by `TranslatingDataSource` to our own server
(`http://147.224.130.108/livesubs/subtitle`, subsync/asrlive.py), which fetches the original
captions, translates them with translate-pa.googleapis.com and returns finished VTT.
English/untranslated captions and all video/audio requests pass straight through.

## Current state
- TV (AT&T box `10.0.0.53:5555`, package `org.smarttube.stable`) runs **32.68 + Persian subs**,
  versionCode 32068, installed over 32.47 with settings kept.
- Signing key: `%USERPROFILE%\.android\debug.keystore` on the Windows PC,
  SHA256 `7D:C0:6A:2A:53:12:DD:76:D0:9E:24:61:17:B2:CB:94:55:E6:48:E1:B1:96:3E:F7:9D:17:38:35:C0:11:79:25`
  — the key the TV's install is signed with. Stored in GitHub secrets
  `SIGNING_KEY` / `KEY_STORE_PASSWORD` / `ALIAS` / `KEY_PASSWORD` (set by `setup-signing.ps1`).
- Releases `32.68.1` (key 90:36…) and the first CI build of 32.68 (key C7:92…) are signed with
  other keys and can **not** be installed over the TV's app. Latest release is `persian-subs-32.68`.
- The repo must stay **public**: the app downloads `update.json` and the APK anonymously.

## Auto-update (`.github/workflows/auto-update.yml`)
Daily 06:00 UTC, or Actions → Run workflow (`force` = rebuild, `fresh` = rebuild branch from upstream):
1. Latest upstream tag (`X.Y`); skip if `persian-subs-<tag>` already released.
2. `patches/apply-patch.py` on the upstream code — anchor-based, idempotent, keeps line endings:
   - Persian-subtitle hook in `ExoMediaSourceFactory.buildDataSourceFactory` + copies
     `patches/TranslatingDataSource.java` (**required**: fails the run, no release, if it can't apply);
   - audio-track restore crash fix in `TrackSelectorManager` (optional: warning if anchor moved).
3. `update_urls.xml` → this fork's `releases/latest/download/update.json`;
   versionCode = MAJOR*1000+MINOR (32.68 → 32068).
4. Build `assembleStstableDebug`, signed with the secret key.
5. Signature must equal the TV's fingerprint above, else no release.
6. Publish release (`make_latest`) with APKs + `update.json`; the app offers the update itself.

## If a run fails at "Apply patch"
Upstream changed `buildDataSourceFactory`. Check out `persian-subs-<tag>`, wrap the returned
`DefaultDataSourceFactory` in `TranslatingDataSource` by hand, push the branch, re-run the
workflow (it reuses an existing branch).

## Manual install
```
adb connect 10.0.0.53:5555
adb -s 10.0.0.53:5555 install -r <apk>
```
