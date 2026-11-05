#!/usr/bin/env bash
# Record Player: build the Android APK and the Linux desktop .deb, check them, and file them
# under project-builds/ with a summary log. Follows project-guidelines/linux_packaging.md.
#
# Usage:
#   scripts/build.sh [dev|release] [--android] [--desktop]
#
#   dev | release   Build type. If left out, the script asks.
#   --android       Build only the Android APK.
#   --desktop       Build only the Linux desktop .deb.
#                   (No platform flag = both.)
#
# Output (old builds are never overwritten or deleted):
#   project-builds/<dev|release>-builds/RecordPlayer-<platform>-<version>_<YYYY-MM-DD_HHMM>/
#   project-builds/logs/RecordPlayer-<platform>-<version>_<YYYY-MM-DD_HHMM>.md
#
# Release builds sign the APK with your own keystore, read from the gitignored .env
# (see .env_example). Values are never printed or logged.

set -euo pipefail

RELEASE_NAME="RecordPlayer"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

STATUS_FILE="project-notes/mockup-port-status.md"
DEB_DEPENDS_REQUIRED=("libgstreamer1.0-0" "gstreamer1.0-plugins-base" "gstreamer1.0-plugins-good")
APP_ID="dev.recorddisc.player"

# ---------- helpers ----------
bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die() { printf '\n\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Each platform's checks are collected as markdown table rows; any failure fails that platform.
declare -a CHECKS=()
PLATFORM_OK=1
check() { # check <pass:0|1> <name> <detail>
  local mark="✓"; if [[ "$1" != 0 ]]; then mark="✗"; PLATFORM_OK=0; fi
  CHECKS+=("| $mark | $2 | $3 |")
  printf '  [%s] %s: %s\n' "$mark" "$2" "$3"
}

usage() { sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# ---------- arguments ----------
BUILD_TYPE=""
WANT_ANDROID=0
WANT_DESKTOP=0
for arg in "$@"; do
  case "$arg" in
    dev|release) BUILD_TYPE="$arg" ;;
    --android) WANT_ANDROID=1 ;;
    --desktop) WANT_DESKTOP=1 ;;
    -h|--help) usage 0 ;;
    *) printf 'Unknown option: %s\n\n' "$arg" >&2; usage 1 ;;
  esac
done
if [[ $WANT_ANDROID == 0 && $WANT_DESKTOP == 0 ]]; then WANT_ANDROID=1; WANT_DESKTOP=1; fi

# The packaging guideline says to always ask which type the build is.
if [[ -z "$BUILD_TYPE" ]]; then
  read -r -p "Build type? [dev/release]: " BUILD_TYPE || die "No build type given. Run: scripts/build.sh dev  (or release)"
  BUILD_TYPE="$(printf '%s' "$BUILD_TYPE" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
fi
[[ "$BUILD_TYPE" == dev || "$BUILD_TYPE" == release ]] || die "Build type must be 'dev' or 'release'."

bold "Record Player build: $BUILD_TYPE ($( ((WANT_ANDROID)) && printf 'android ')$( ((WANT_DESKTOP)) && printf 'desktop'))"

# ---------- 1. preflight ----------
bold "1. Preflight"
[[ -x ./gradlew ]] || die "gradlew not found. Run this from the Music_Player repo."
command -v java >/dev/null || die "java (a JDK) is not on PATH."
JAVA_VERSION="$(java -version 2>&1 | sed -n 1p)"
info "JDK: $JAVA_VERSION"

ANDROID_VERSION="$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' androidApp/build.gradle.kts | sed -n 1p)"
DESKTOP_VERSION="$(sed -n 's/^ *packageVersion = "\(.*\)".*/\1/p' desktopApp/build.gradle.kts | sed -n 1p)"
DEB_PACKAGE="$(sed -n 's/^ *packageName = "\(.*\)".*/\1/p' desktopApp/build.gradle.kts | sed -n 1p)"
[[ -n "$ANDROID_VERSION" && -n "$DESKTOP_VERSION" ]] || die "Couldn't read the app versions from the Gradle files."
[[ "$ANDROID_VERSION" == "$DESKTOP_VERSION" ]] \
  || die "Version mismatch: Android versionName is $ANDROID_VERSION but desktop packageVersion is $DESKTOP_VERSION. Make them match first."
VERSION="$ANDROID_VERSION"
info "App version: $VERSION"

if ((WANT_ANDROID)); then
  SDK_DIR="${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)}"
  [[ -n "$SDK_DIR" && -d "$SDK_DIR" ]] || die "Android SDK not found (set sdk.dir in local.properties or ANDROID_HOME)."
  BUILD_TOOLS="$(ls -d "$SDK_DIR"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  [[ -x "$BUILD_TOOLS/apksigner" && -x "$BUILD_TOOLS/aapt2" ]] || die "Android build-tools (apksigner, aapt2) not found under $SDK_DIR/build-tools."
  info "Android build-tools: $(basename "$BUILD_TOOLS")"
fi
if ((WANT_DESKTOP)); then
  command -v dpkg-deb >/dev/null || die "dpkg-deb is missing (needed to build and check the .deb)."
  command -v fakeroot >/dev/null || die "fakeroot is missing (needed to build the .deb). Install it with: sudo apt install fakeroot"
fi

# Release signing: read the four values from .env WITHOUT running it as a script.
if ((WANT_ANDROID)) && [[ "$BUILD_TYPE" == release ]]; then
  [[ -f .env ]] || die "Release builds need .env with your keystore settings. Copy .env_example to .env and fill it in."
  for key in RECORDPLAYER_KEYSTORE_PATH RECORDPLAYER_KEYSTORE_PASSWORD RECORDPLAYER_KEY_ALIAS RECORDPLAYER_KEY_PASSWORD; do
    value="$(grep -E "^${key}=" .env | tail -1 | cut -d= -f2- || true)"
    value="${value%\"}"; value="${value#\"}"; value="${value%\'}"; value="${value#\'}"
    [[ -n "$value" ]] || die "$key is empty in .env (see .env_example)."
    export "$key=$value"
  done
  [[ -f "$RECORDPLAYER_KEYSTORE_PATH" ]] || die "The keystore named by RECORDPLAYER_KEYSTORE_PATH doesn't exist."
  case "$(realpath "$RECORDPLAYER_KEYSTORE_PATH")" in
    "$REPO_ROOT"/*) die "Your keystore is inside the repo folder. Move it outside the repo (this repo is public)." ;;
  esac
  info "Release signing: keystore settings found in .env"
fi

# ---------- 2. outstanding mockup changes ----------
bold "2. Mockup changes not yet in the app"
if [[ -f "$STATUS_FILE" ]]; then
  mapfile -t PENDING < <(grep -E '^\| *pending *\|' "$STATUS_FILE" | awk -F'|' '{gsub(/^ +| +$/,"",$3); gsub(/^ +| +$/,"",$4); gsub(/^ +| +$/,"",$5); printf "#%s %s [%s]\n", $3, $4, $5}')
  if ((${#PENDING[@]})); then
    printf '  %s\n' "${PENDING[@]}"
    info "(${#PENDING[@]} pending; from $STATUS_FILE)"
  else
    info "None: every mockup change in $STATUS_FILE is done."
  fi
else
  PENDING=("(unknown: $STATUS_FILE is missing; project-notes is gitignored, so it only exists on the main machine)")
  info "${PENDING[0]}"
fi
read -r -p "Build anyway? [y/N]: " answer || answer=""
[[ "$answer" =~ ^[Yy]([Ee][Ss])?$ ]] || die "Build cancelled."

# ---------- shared output setup ----------
STAMP="$(date +%Y-%m-%d_%H%M)"
BUILD_ROOT="project-builds/${BUILD_TYPE}-builds"
LOG_DIR="project-builds/logs"
mkdir -p "$BUILD_ROOT" "$LOG_DIR"
GRADLE_VERSION="$(./gradlew --version 2>/dev/null | sed -n 's/^Gradle //p' | sed -n 1p)"
toml() { sed -n "s/^$1 *= *\"\(.*\)\"/\1/p" gradle/libs.versions.toml | sed -n 1p; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT  # only this run's scratch space; builds are never deleted
OVERALL_OK=1

write_log() { # write_log <platform> <folder-or-empty> <artifact lines...>
  local platform="$1" folder="$2"; shift 2
  local name="${RELEASE_NAME}-${platform}-${VERSION}_${STAMP}"
  local log="$LOG_DIR/${name}.md"
  {
    printf '# Build summary: %s\n\n' "$name"
    printf '| | |\n|---|---|\n'
    printf '| Date | %s |\n' "$(date '+%Y-%m-%d %H:%M %Z')"
    printf '| Build type | %s |\n' "$BUILD_TYPE"
    printf '| Platform | %s |\n' "$platform"
    printf '| App version | %s |\n' "$VERSION"
    printf '| Result | **%s** |\n' "$( ((PLATFORM_OK)) && echo SUCCESS || echo FAILED)"
    printf '| Output folder | %s |\n' "${folder:-none (failed builds are not filed)}"
    printf '| Git commit | %s%s |\n' "$(git rev-parse --short HEAD 2>/dev/null || echo unknown)" "$(git diff --quiet HEAD 2>/dev/null || echo ' (with uncommitted changes)')"
    printf '\n## Tools\n\n'
    printf -- '- Gradle %s\n- JDK: %s\n- Kotlin %s\n- Compose Multiplatform %s\n- Android Gradle Plugin %s\n' \
      "$GRADLE_VERSION" "$JAVA_VERSION" "$(toml kotlin)" "$(toml composeMultiplatform)" "$(toml agp)"
    printf '\n## Checks\n\n| | Check | Detail |\n|---|---|---|\n'
    printf '%s\n' "${CHECKS[@]}"
    if (($#)); then printf '\n## Files\n\n'; printf -- '- %s\n' "$@"; fi
    printf '\n## Mockup changes not yet in the app (confirmed before building)\n\n'
    if ((${#PENDING[@]})); then printf -- '- %s\n' "${PENDING[@]}"; else printf -- '- None\n'; fi
  } > "$log"
  info "Summary log: $log"
}

new_folder() { # new_folder <platform>  -> prints the folder path; never reuses one
  local dir="$BUILD_ROOT/${RELEASE_NAME}-$1-${VERSION}_${STAMP}"
  [[ ! -e "$dir" ]] || die "$dir already exists (two builds in the same minute?). Not overwriting it; try again in a minute."
  mkdir -p "$dir"
  printf '%s' "$dir"
}

file_info() { printf '%s (%s, SHA-256 %s)' "$(basename "$1")" "$(du -h "$1" | cut -f1)" "$(sha256sum "$1" | cut -d' ' -f1)"; }

# ---------- 3. Android ----------
if ((WANT_ANDROID)); then
  bold "3. Android APK"
  CHECKS=(); PLATFORM_OK=1
  if [[ "$BUILD_TYPE" == dev ]]; then TASK=":androidApp:assembleDebug"; APK="androidApp/build/outputs/apk/debug/androidApp-debug.apk"
  else TASK=":androidApp:assembleRelease"; APK="androidApp/build/outputs/apk/release/androidApp-release.apk"; fi
  rm -f "$APK"  # a stale APK from an earlier Gradle run must not pass for this one
  if ./gradlew "$TASK" --console=plain > "$WORK/android-gradle.log" 2>&1; then
    check 0 "Gradle $TASK" "succeeded"
  else
    check 1 "Gradle $TASK" "failed; last lines: $(tail -3 "$WORK/android-gradle.log" | tr '\n' ' ' | cut -c1-200)"
  fi

  if [[ -f "$APK" ]]; then
    check 0 "APK produced" "$(basename "$APK")"
    BADGING="$("$BUILD_TOOLS/aapt2" dump badging "$APK" 2>/dev/null | sed -n 1p)"
    [[ "$BADGING" == *"name='$APP_ID'"* ]] && check 0 "Package name" "$APP_ID" || check 1 "Package name" "expected $APP_ID; got: $BADGING"
    [[ "$BADGING" == *"versionName='$VERSION'"* ]] && check 0 "Version" "$VERSION" || check 1 "Version" "expected $VERSION; got: $BADGING"
    APK_FILES="$(unzip -Z1 "$APK")"  # list first: piping into grep -q can cut unzip off and fail the pipe
    grep -qx 'classes.dex' <<<"$APK_FILES" && check 0 "App code" "classes.dex present" || check 1 "App code" "classes.dex missing"
    if CERTS="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>&1)"; then
      check 0 "Signature" "apksigner verify passed"
      if [[ "$BUILD_TYPE" == release ]]; then
        if grep -q 'CN=Android Debug' <<<"$CERTS"; then check 1 "Release key" "signed with the DEBUG key"
        else check 0 "Release key" "signed with your release key (SHA-256 $(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$CERTS" | sed -n 1p))"; fi
      fi
    else
      check 1 "Signature" "apksigner verify failed"
    fi
  else
    check 1 "APK produced" "no APK at $APK"
  fi

  FOLDER=""; FILES=()
  if ((PLATFORM_OK)); then
    FOLDER="$(new_folder android)"
    OUT="$FOLDER/${RELEASE_NAME}-android-${VERSION}-${BUILD_TYPE}.apk"
    cp "$APK" "$OUT"
    FILES=("$(file_info "$OUT")")
    info "Filed: $OUT"
  else
    OVERALL_OK=0
  fi
  write_log android "$FOLDER" "${FILES[@]}"
fi

# ---------- 4. Desktop ----------
if ((WANT_DESKTOP)); then
  bold "4. Linux desktop .deb"
  CHECKS=(); PLATFORM_OK=1
  DEB_DIR="desktopApp/build/compose/binaries/main/deb"
  DEB="$DEB_DIR/${DEB_PACKAGE}_${VERSION}_amd64.deb"
  rm -f "$DEB_DIR"/*.deb 2>/dev/null || true  # Gradle output only, so a stale package can't pass for this one
  if ./gradlew :desktopApp:packageDeb --console=plain > "$WORK/desktop-gradle.log" 2>&1; then
    check 0 "Gradle :desktopApp:packageDeb" "succeeded"
  else
    check 1 "Gradle :desktopApp:packageDeb" "failed; last lines: $(tail -3 "$WORK/desktop-gradle.log" | tr '\n' ' ' | cut -c1-200)"
  fi

  if [[ -f "$DEB" ]]; then
    # Naming: the file name must keep the form dpkg expects, or it won't install as intended.
    check 0 "Package file name" "$(basename "$DEB") (name_version_arch.deb)"
    PKG_FIELD="$(dpkg-deb -f "$DEB" Package)"; VER_FIELD="$(dpkg-deb -f "$DEB" Version)"; DEPENDS="$(dpkg-deb -f "$DEB" Depends)"
    [[ "$PKG_FIELD" == "$DEB_PACKAGE" && "$VER_FIELD" == "$VERSION" ]] \
      && check 0 "Package fields" "Package $PKG_FIELD, Version $VER_FIELD" \
      || check 1 "Package fields" "expected $DEB_PACKAGE $VERSION; got $PKG_FIELD $VER_FIELD"

    # Dependencies: playback needs the system GStreamer, which isn't bundled.
    missing=()
    for dep in "${DEB_DEPENDS_REQUIRED[@]}"; do grep -qE "(^|[ ,])${dep}([ ,(]|$)" <<<"$DEPENDS" || missing+=("$dep"); done
    ((${#missing[@]} == 0)) && check 0 "Declares GStreamer" "${DEB_DEPENDS_REQUIRED[*]}" || check 1 "Declares GStreamer" "Depends is missing: ${missing[*]}"

    dpkg-deb -x "$DEB" "$WORK/deb"

    # Menu entry (linux_packaging.md): Exec and Icon point at shipped files, it passes
    # desktop-file-validate, and it lists the audio types so file managers offer "Open with".
    DESKTOP="$(find "$WORK/deb" -name '*.desktop' -path '*/lib/*' -print -quit)"
    if [[ -f "$DESKTOP" ]]; then
      EXEC_BIN="$(sed -n 's/^Exec=\([^ ]*\).*/\1/p' "$DESKTOP")"; ICON_FILE="$(sed -n 's/^Icon=//p' "$DESKTOP")"
      [[ -x "$WORK/deb$EXEC_BIN" && -f "$WORK/deb$ICON_FILE" ]] \
        && check 0 "Menu entry paths" "Exec $EXEC_BIN, Icon $ICON_FILE (both shipped)" \
        || check 1 "Menu entry paths" "Exec '$EXEC_BIN' or Icon '$ICON_FILE' isn't in the package"
      if grep -q '^MimeType=.*audio/mpeg;.*audio/flac;' "$DESKTOP" && grep -q '^Exec=.* %F$' "$DESKTOP"; then
        check 0 "Open with" "lists audio types; Exec passes the chosen file (%F)"
      else
        check 1 "Open with" "MimeType or Exec %F missing: $(grep -E '^(Exec|MimeType)=' "$DESKTOP" | tr '\n' ' ' | cut -c1-160)"
      fi
      if command -v desktop-file-validate >/dev/null; then
        desktop-file-validate "$DESKTOP" > "$WORK/desktop-validate.log" 2>&1 \
          && check 0 "desktop-file-validate" "no errors" || check 1 "desktop-file-validate" "$(grep -m1 error "$WORK/desktop-validate.log" | cut -c1-200)"
      else
        info "desktop-file-validate isn't installed (package desktop-file-utils), so the menu entry wasn't validated."
      fi
    else
      check 1 "Menu entry" "no .desktop file in the package"
    fi
    APP_DIR="$(dirname "$(find "$WORK/deb" -path '*/lib/app/*.cfg' -print -quit 2>/dev/null)")"
    if [[ -d "$APP_DIR" ]]; then
      # jpackage leaves out the runtime's bin/java; the app's launcher loads libjvm.so directly.
      RUNTIME_DIR="$(dirname "$APP_DIR")/runtime"
      RUNTIME_VER="$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$RUNTIME_DIR/release" 2>/dev/null || true)"
      [[ -f "$RUNTIME_DIR/lib/server/libjvm.so" && -f "$RUNTIME_DIR/lib/modules" && -n "$RUNTIME_VER" ]] \
        && check 0 "Bundled Java runtime" "Java $RUNTIME_VER (libjvm.so, modules)" || check 1 "Bundled Java runtime" "libjvm.so, modules or release file missing"

      # Start the real packaged launcher with no screen. Reaching the UI (a HeadlessException when it
      # opens its window) proves the runtime, modules and classpath all work. A throwaway HOME keeps
      # it away from any real user data.
      LAUNCHER="$(dirname "$(dirname "$APP_DIR")")/bin/$DEB_PACKAGE"
      chmod -R u+rwX "$WORK/deb"
      mkdir -p "$WORK/home"
      # XDG_RUNTIME_DIR too: the app keeps its one-window socket there, which must not touch a real session.
      mkdir -p "$WORK/runtime" && chmod 700 "$WORK/runtime"
      env -u DISPLAY -u WAYLAND_DISPLAY HOME="$WORK/home" XDG_RUNTIME_DIR="$WORK/runtime" JAVA_TOOL_OPTIONS=-Djava.awt.headless=true \
        timeout 40 "$LAUNCHER" > "$WORK/launch.log" 2>&1 && launch_rc=0 || launch_rc=$?
      if grep -qE 'NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|FindException|ResolutionException|module .* not found' "$WORK/launch.log"; then
        check 1 "Packaged app starts" "$(grep -m1 -E 'NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|FindException|ResolutionException|module .* not found' "$WORK/launch.log" | cut -c1-200)"
      elif grep -q 'HeadlessException' "$WORK/launch.log" || [[ $launch_rc == 124 ]]; then
        check 0 "Packaged app starts" "ran from its bundled runtime up to opening its window (no screen here)"
      else
        check 1 "Packaged app starts" "exit $launch_rc: $(grep -m1 -E 'Exception|Error' "$WORK/launch.log" | cut -c1-200)"
      fi

      # Known error (linux_packaging.md): kotlin-stdlib missing -> NoClassDefFoundError on start.
      STDLIB="$(ls "$APP_DIR" | grep -E '^kotlin-stdlib-[0-9].*\.jar$' | sed -n 1p || true)"
      COROUTINES="$(ls "$APP_DIR" | grep -E '^kotlinx-coroutines-core-jvm-.*\.jar$' | sed -n 1p || true)"
      [[ -n "$STDLIB" ]] && check 0 "Known error: kotlin-stdlib bundled" "$STDLIB" || check 1 "Known error: kotlin-stdlib bundled" "kotlin-stdlib jar MISSING"
      [[ -n "$COROUTINES" ]] && check 0 "kotlinx-coroutines bundled" "$COROUTINES" || check 1 "kotlinx-coroutines bundled" "jar MISSING"

      # Every jar the launcher puts on the classpath must actually be in the package.
      CFG="$(ls "$APP_DIR"/*.cfg | sed -n 1p)"
      mapfile -t CP_JARS < <(sed -n 's/^app\.classpath=\$APPDIR\/\{0,1\}//p' "$CFG")
      absent=()
      for jar in "${CP_JARS[@]}"; do [[ -f "$APP_DIR/$jar" ]] || absent+=("$jar"); done
      ((${#CP_JARS[@]} > 0 && ${#absent[@]} == 0)) \
        && check 0 "Launcher classpath complete" "${#CP_JARS[@]} jars, all present" \
        || check 1 "Launcher classpath complete" "${#CP_JARS[@]} jars listed; missing: ${absent[*]:-none listed}"

      # Load the classes that failed in the known error, from the packaged jars only.
      CP="$(printf "$APP_DIR/%s:" "${CP_JARS[@]}")"
      cat > "$WORK/LoadCheck.java" <<'JAVA'
public class LoadCheck {
    public static void main(String[] args) throws Exception {
        for (String name : args) Class.forName(name, false, LoadCheck.class.getClassLoader());
    }
}
JAVA
      if java -cp "$CP" "$WORK/LoadCheck.java" 'kotlin.coroutines.CoroutineContext$Element' kotlinx.coroutines.SupervisorKt dev.recorddisc.player.MainKt > "$WORK/loadcheck.log" 2>&1; then
        check 0 "Classes load from the package" "CoroutineContext\$Element, SupervisorKt, MainKt"
      else
        check 1 "Classes load from the package" "$(grep -m1 -E 'Exception|Error' "$WORK/loadcheck.log" | cut -c1-200)"
      fi
    else
      check 1 "Package contents" "couldn't find the app folder (lib/app/*.cfg) inside the .deb"
    fi
  else
    check 1 "Package produced" "no .deb at $DEB"
  fi

  FOLDER=""; FILES=()
  if ((PLATFORM_OK)); then
    FOLDER="$(new_folder linux)"
    cp "$DEB" "$FOLDER/"
    FILES=("$(file_info "$FOLDER/$(basename "$DEB")")")
    info "Filed: $FOLDER/$(basename "$DEB")"
  else
    OVERALL_OK=0
  fi
  write_log linux "$FOLDER" "${FILES[@]}"
fi

echo
if ((OVERALL_OK)); then bold "Build finished: all checks passed."
else die "Build finished with failures. See the summary log(s) in $LOG_DIR."; fi
