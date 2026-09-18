#!/usr/bin/env bash
# Rebuild SmartTube on the latest upstream release, keeping the Persian-subtitle patch.
#
# The patch is one commit touching two files, so it rides along with a cherry-pick.
# Staying current matters: an outdated build is what made YouTube answer 403 to video
# requests and freeze playback on a spinner (the June 31.90 fork did exactly that).
#
# Every build is signed with the same debug key, so installs land on top of the old one
# and the viewer's settings, subtitle preferences and YouTube login all survive. Losing
# that key (~/.android/debug.keystore, backed up next to this script) would mean an
# uninstall+reinstall, which wipes them -- so keep the backup.
#
#   bash update-smarttube.sh             # check, build if there's something newer
#   bash update-smarttube.sh --install   # ...and install on the TV when it's idle
#   bash update-smarttube.sh --force     # rebuild even if already on the latest
#
set -euo pipefail

REPO="C:/website/SmartTube"          # not D: -- that drive is FAT32 and breaks Android builds
PATCH_COMMIT="16f849e"              # "Persian captions from our own server"
JDK17="C:/Users/iranz/.jdks/jbr-17.0.14"   # Java 26/21 make D8 crash on this toolchain
TV="10.0.0.179:44583"
PKG="org.smarttube.stable"
KEEP="C:/website/SmartTube-builds"
LOG="$KEEP/update.log"

exec > >(tee -a "$LOG") 2>&1
echo "=== $(date '+%Y-%m-%d %H:%M') ==="

want_install=false
force=false
for a in "$@"; do
    [ "$a" = "--install" ] && want_install=true
    [ "$a" = "--force" ] && force=true
done

cd "$REPO"

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/yuliskov/SmartTube.git
TAG=$(git ls-remote --tags --refs upstream \
      | sed 's#.*refs/tags/##' \
      | grep -E '^[0-9]+\.[0-9]+$' \
      | sort -t. -k1,1n -k2,2n \
      | tail -1)
echo "latest upstream release: $TAG"

# What the TV is actually running. If it already has this version, there's nothing to do.
adb connect "$TV" >/dev/null 2>&1 || true
INSTALLED=$(adb -s "$TV" shell dumpsys package "$PKG" 2>/dev/null \
            | sed -n 's/.*versionName=\([0-9.]*\).*/\1/p' | head -1 || true)
echo "installed on the TV: ${INSTALLED:-unknown}"

if [ "$force" = false ] && [ -n "$INSTALLED" ] && [ "$INSTALLED" = "$TAG" ]; then
    echo "already current -- nothing to do"
    exit 0
fi

if git rev-parse --verify --quiet "persian-subs-$TAG" >/dev/null; then
    git checkout "persian-subs-$TAG"
else
    git fetch --depth 1 upstream "refs/tags/$TAG:refs/tags/upstream-$TAG"
    git checkout -b "persian-subs-$TAG" "upstream-$TAG"
    echo "applying the Persian-subtitle patch"
    # If this stops applying cleanly, upstream changed buildDataSourceFactory():
    # re-add the 3-line hook there by hand, the other file is standalone.
    git cherry-pick "$PATCH_COMMIT"
fi

git submodule update --init --recursive

echo "building with JDK 17"
JAVA_HOME="$JDK17" ./gradlew.bat :smarttubetv:assembleStstableDebug

OUT="$REPO/smarttubetv/build/outputs/apk/ststable/debug"
DEST="$KEEP/$(date +%Y-%m-%d)_${TAG}_persian-subs"
mkdir -p "$DEST"
cp "$OUT"/SmartTube_stable_"$TAG"_*.apk "$DEST"/
(cd "$DEST" && sha256sum *.apk > SHA256SUMS)
echo "kept in $DEST"

if [ "$want_install" = true ]; then
    # Never interrupt someone mid-video: an install restarts the app.
    FOCUS=$(adb -s "$TV" shell dumpsys window 2>/dev/null | grep -i mCurrentFocus || true)
    if echo "$FOCUS" | grep -q "PlaybackActivity"; then
        echo "TV is playing something -- APK is ready in $DEST, install later"
        exit 0
    fi
    echo "installing"
    adb -s "$TV" install -r "$OUT/SmartTube_stable_${TAG}_armeabi-v7a.apk"
fi

echo "done: $TAG"
