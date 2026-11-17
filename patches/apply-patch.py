"""Applies this fork's changes on top of any upstream SmartTube release (used by auto-update.yml).

Instead of cherry-picking a commit (which breaks whenever upstream edits the lines around the
hook), each change finds its own anchor and is idempotent -- safe to run twice.

  1. Persian subtitles (REQUIRED -- exits 1 if it can't be applied, so no release ships without it):
     copies TranslatingDataSource.java next to this script into place and wraps the
     DefaultDataSourceFactory returned by ExoMediaSourceFactory.buildDataSourceFactory().
  2. Audio-track restore crash fix (optional -- skipped with a warning if its anchor moved).
  3. Hold BACK -> back to Persiana (optional, same rule).

Run from the repo root:  python3 patches/apply-patch.py
"""
import pathlib
import re
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
PKG = pathlib.Path("common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer")
FACTORY = PKG / "ExoMediaSourceFactory.java"
TRANSLATING = PKG / "other/TranslatingDataSource.java"
TRACK_SELECTOR = PKG / "selector/TrackSelectorManager.java"
WRAPPER = "com.liskovsoft.smartyoutubetv2.common.exoplayer.other.TranslatingDataSource"


def read(path):
    # bytes, not read_text(): keep the file's own CRLF/LF line endings untouched
    return path.read_bytes().decode("utf-8")


def write(path, text):
    path.write_bytes(text.encode("utf-8"))


def warn(msg):
    print(f"::warning::{msg}")


def fail(msg):
    print(f"::error::{msg}")
    sys.exit(1)


def apply_persian_subtitles():
    shutil.copyfile(HERE / "TranslatingDataSource.java", TRANSLATING)
    if not FACTORY.exists():
        fail(f"{FACTORY} not found -- upstream restructured the player; re-add the hook by hand")
    src = read(FACTORY)
    if WRAPPER in src:
        return print("Persian-subtitle hook already present")
    nl = "\r\n" if "\r\n" in src else "\n"
    # every "return new DefaultDataSourceFactory(...);" -- upstream has one, in buildDataSourceFactory()
    pattern = re.compile(r"^([ \t]*)return (new DefaultDataSourceFactory\(.*\));[ \t]*\r?$", re.M)
    matches = list(pattern.finditer(src))
    if not matches:
        fail("No 'return new DefaultDataSourceFactory(...)' in ExoMediaSourceFactory -- re-add the hook by hand")

    def wrap(m):
        ind = m.group(1)
        return (f"{ind}DataSource.Factory realFactory = {m.group(2)};{nl}"
                f"{ind}// Own-translated Persian (and other) captions instead of YouTube's own auto-translate.{nl}"
                f"{ind}return () -> new {WRAPPER}(realFactory);")

    write(FACTORY, pattern.sub(wrap, src))
    print(f"applied Persian-subtitle hook ({len(matches)} place(s))")


def fix_track_restore_crash():
    """A restored audio-track selection's groupIndex can exceed the new video's track groups and
    crash ExoPlayerImplInternal (ArrayIndexOutOfBounds). Skip the restore instead."""
    if not TRACK_SELECTOR.exists():
        return warn("TrackSelectorManager.java not found; skipped track-restore fix")
    src = read(TRACK_SELECTOR)
    if "is out of bounds (length" in src:
        return print("track-restore fix already present")
    anchor = "MediaTrack matchedTrack = findBestMatch(selectedTrack);"
    if src.count(anchor) != 1 or "groups.get(matchedTrack.groupIndex)" not in src:
        return warn("track-restore fix anchor moved upstream; skipped")
    nl = "\r\n" if "\r\n" in src else "\n"
    start = src.index(anchor)
    indent = src[src.rfind("\n", 0, start) + 1:start]
    guard = nl.join([
        "",
        "",
        f"{indent}// A restored selection's groupIndex can be stale and not fit the current video's",
        f"{indent}// track groups -- crashed ExoPlayerImplInternal instead of just skipping the restore.",
        f"{indent}if (matchedTrack.groupIndex >= groups.length) {{",
        f"{indent}    Log.e(TAG, \"Can't create selection. Group index %s is out of bounds (length %s).\", matchedTrack.groupIndex, groups.length);",
        f"{indent}    return null;",
        f"{indent}}}",
    ])
    end = start + len(anchor)
    write(TRACK_SELECTOR, src[:end] + guard + src[end:])
    print("applied track-restore fix")


MOTHER = pathlib.Path("common/src/main/java/com/liskovsoft/smartyoutubetv2/common/misc/MotherActivity.java")


def hold_back_to_persiana():
    """Owner 2026-11-17: holding BACK (~0.8 s) on any SmartTube screen, player included, jumps
    straight back to Persiana (opened from its «اسمارت تیوب» rail row) instead of many BACK presses.
    A normal short BACK is untouched."""
    if not MOTHER.exists():
        return warn("MotherActivity.java not found; skipped hold-BACK-to-Persiana")
    src = read(MOTHER)
    if "mPersianaJumped" in src:
        return print("hold-BACK-to-Persiana already present")
    anchor = "if (event == null) { // handled"
    if src.count(anchor) != 1 or "import android.content.Intent;" not in src:
        return warn("hold-BACK-to-Persiana anchor moved upstream; skipped")
    nl = "\r\n" if "\r\n" in src else "\n"
    start = src.index(anchor)
    indent = src[src.rfind("\n", 0, start) + 1:start]
    block_end = src.index(f"{nl}{indent}}}", start) + len(f"{nl}{indent}}}")
    i = indent
    hook = nl.join([
        "",
        "",
        f"{i}// Fork: hold BACK ~0.8 s anywhere -> back to Persiana. The rest of the hold (and its UP) is",
        f"{i}// swallowed so it isn't also a normal BACK.",
        f"{i}if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {{",
        f"{i}    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {{",
        f"{i}        mPersianaJumped = false;",
        f"{i}    }} else if (event.getAction() == KeyEvent.ACTION_DOWN && !mPersianaJumped && event.getEventTime() - event.getDownTime() >= 800) {{",
        f"{i}        mPersianaJumped = true;",
        f"{i}        for (String pkg : new String[] {{\"com.samhoustonradio.persiana.raw.v2\", \"com.samhoustonradio.persiana.player.v2\"}}) {{",
        f"{i}            try {{",
        f"{i}                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)",
        f"{i}                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));",
        f"{i}                break;",
        f"{i}            }} catch (Exception e) {{",
        f"{i}                // not installed -- try the next one",
        f"{i}            }}",
        f"{i}        }}",
        f"{i}    }}",
        f"{i}    if (mPersianaJumped) {{",
        f"{i}        return true;",
        f"{i}    }}",
        f"{i}}}",
    ])
    src = src[:block_end] + hook + src[block_end:]
    field = "private long mLastKeyDownTime;"
    if src.count(field) != 1:
        return warn("hold-BACK-to-Persiana field anchor moved upstream; skipped")
    src = src.replace(field, field + f"{nl}    private boolean mPersianaJumped;", 1)
    write(MOTHER, src)
    print("applied hold-BACK-to-Persiana")


if __name__ == "__main__":
    apply_persian_subtitles()
    fix_track_restore_crash()
    hold_back_to_persiana()
