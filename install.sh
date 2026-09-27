#!/usr/bin/env bash
# Put Jungey TV on this computer, for your account: the jungey-tv command (which Jungey
# uses to drive the TV), and Jungey TV in the app menu. No root needed.
#
#   apps/tv/install.sh              build and install
#   apps/tv/install.sh --uninstall  take it away again (the TV's permission is kept)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
data="${XDG_DATA_HOME:-$HOME/.local/share}"
bin="$HOME/.local/bin"

say() { printf '\033[1m%s\033[0m\n' "$*"; }

case "${1:-}" in
  --uninstall)
    rm -f "$bin/jungey-tv" "$data/applications/jungey-tv.desktop" "$data/jungey-tv/jungey-tv.jar"
    for size in 16 32 48 128 256; do rm -f "$data/icons/hicolor/${size}x${size}/apps/jungey-tv.png"; done
    say "Removed. The TV's permission is still in ~/.config/jungey-tv; delete it to forget the TV."
    exit 0 ;;
  "") ;;
  -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown option: $1" >&2; exit 1 ;;
esac

# The build targets 21, which is often not the system default JDK.
if [ -z "${JAVA_HOME:-}" ] && ! javac -version 2>&1 | grep -q ' 21\.'; then
  for jdk in /usr/lib/jvm/*21*; do
    if [ -x "$jdk/bin/javac" ]; then export JAVA_HOME="$jdk"; break; fi
  done
fi

say "Building Jungey TV"
mvn -q -B -f "$here/pom.xml" package
jar="$(ls -t "$here"/target/jungey-tv-*.jar | grep -v original- | head -n 1)"

say "Installing the jungey-tv command, menu entry and icon"
install -Dm644 "$jar" "$data/jungey-tv/jungey-tv.jar"
install -Dm755 "$here/jungey-tv" "$bin/jungey-tv"
for size in 16 32 48 128 256; do
  install -Dm644 "$here/src/main/resources/icons/jungey-tv-$size.png" \
    "$data/icons/hicolor/${size}x${size}/apps/jungey-tv.png"
done
mkdir -p "$data/applications"
sed "s|^Exec=jungey-tv|Exec=$bin/jungey-tv|" "$here/jungey-tv.desktop" > "$data/applications/jungey-tv.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database -q "$data/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$data/icons/hicolor" || true

say "Done. Open Jungey TV from the menu, or try: $bin/jungey-tv status"
case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "Log out and back in once, so $bin is on your PATH." ;;
esac
