# SmartTube — Persian subtitle quality: investigation handoff

## Goal
The user finds Persian subtitles in SmartTube (an open-source, ad-free YouTube client
for Android TV) low quality, and wants them fixed the way we already fixed live
Persian subtitles in a different app (Persiana) tonight: by controlling the
translation step ourselves instead of trusting whatever the platform gives us.

## Repos
- **User's fork (start here):** https://github.com/Samhouston010/SmartTube
  - Forked from upstream, **never modified** — confirmed by the user. Currently
    marked "archived" on GitHub (that's a repo setting, not a code state; un-archive
    it or just push to a new branch/fork).
- **Upstream:** https://github.com/yuliskov/SmartTube
- Two required git submodules (declared in `settings.gradle`, not yet initialized
  in the clone below):
  - `SharedModules` → https://github.com/yuliskov/SharedModules
  - `MediaServiceCore` → https://github.com/yuliskov/MediaServiceCore
  - **This second one almost certainly contains the actual fix target** (see below).

## Local clone
`D:\website\SmartTube` — shallow clone (`--depth 1`) of the user's fork, submodules
**not yet initialized**. To get a full working tree:
```
cd D:\website\SmartTube
git submodule update --init --recursive
```
(Do this in a stable environment — MediaServiceCore/SharedModules are sizeable.)

## What was confirmed by reading the code (not guessed)

Persian subtitles in SmartTube are **YouTube's own auto-translated caption
track** — the same "Auto-translate → Persian" option available on youtube.com.
That means every subtitle line has gone through two lossy machine steps stacked
on top of each other: YouTube's speech-to-text (for videos without human
captions), then YouTube's own translation to Persian. This is almost certainly
why quality feels poor — it's the same text a viewer would get by picking
auto-translate on the website itself, not something SmartTube generates or can
tune via its own settings.

Evidence, with exact locations:

- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/selector/track/SubtitleTrack.java`
  — imports `com.liskovsoft.youtubeapi.videoinfo.models.TranslatedCaptionTrack`
  and checks `language.endsWith(TranslatedCaptionTrack.TRANSLATE_MARKER)` to
  detect a track that YouTube itself translated (lines ~4, 105–120).
- `TranslatedCaptionTrack` and however the app requests `tlang=fa` from YouTube
  live in the **`youtubeapi` Gradle module**, which is supplied by the
  `MediaServiceCore` submodule (see `common/build.gradle:64`,
  `settings.gradle`'s `mediaServiceCoreRoot` block). It is **not present** in
  this shallow, submodule-less clone — a `grep` for `timedtext` or `tlang`
  across the whole checked-out tree returns nothing, confirming it lives only
  in that submodule.

What SmartTube **does** already give you, and does not need touching:
- `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/other/SubtitleManager.java`
  — subtitle text size (`getSubtitleScale()`), text/background colour
  (`SubtitleStyle`, applied in `applyStyle()`), and vertical position
  (`getSubtitlePosition()` → `setBottomPaddingFraction`). All already
  user-configurable from Settings → this is rendering, and it is fine.
- The complaint is about **translation quality**, not size/colour/position.

## The fix, by analogy with tonight's Persiana work

Tonight, for Persiana's live TV, we stopped trusting a free, rate-limited
translation path and instead: fetched the *original* text (English), sent it
ourselves to `translate-pa.googleapis.com` (Google Translate's own web engine,
called with its public site key — same quality as translate.google.com, far
better than the `clients5.google.com/translate_a/t` "chrome-ex" endpoint or
YouTube's own auto-translate), and rendered that.

The same shape of fix likely applies here:
1. In `MediaServiceCore`, find where the app requests a caption track with
   `tlang=fa` (or wherever `TranslatedCaptionTrack` is constructed) — that is
   the call handing translation off to YouTube.
2. Instead, request the **original-language track** (or English auto-captions
   if the video has no native captions), download its cues, and translate
   them app-side (or via a small proxy, to control rate limits) using a better
   engine — e.g. the same `translate-pa.googleapis.com` call used in
   Persiana's `subsync/asrlive.py` tonight (`translateHtml` endpoint, public
   site key, HTML-escaped batched lines).
3. Feed the translated cues into the existing `SubtitleManager`/`SubtitleView`
   pipeline — no rendering code needs to change.

## What is NOT yet done
- Submodules are not initialized, so the actual `tlang=fa` / caption-fetch call
  has not been located or read yet — only inferred from `TranslatedCaptionTrack`'s
  name and usage in the main repo.
- No code has been written or changed. This is investigation only, meant to be
  continued in a separate environment as the user requested.
