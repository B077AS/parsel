#!/bin/sh
# Installs the launcher entry and icons for the current user, so a Wayland compositor can show Parsel's icon while it
# runs from an IDE or `mvn exec:exec`. The window's app_id is com.parsel.app (Jolt sets it from the app id), and the
# compositor matches it to com.parsel.app.desktop. Run once, then restart Parsel. Undo with: install-dev.sh --remove
set -e
here=$(cd "$(dirname "$0")" && pwd)
data=${XDG_DATA_HOME:-$HOME/.local/share}
if [ "$1" = "--remove" ]; then
    rm -f "$data/applications/com.parsel.app.desktop"
    for f in "$here"/icons/*.png; do
        size=$(basename "$f" .png)
        rm -f "$data/icons/hicolor/${size}x${size}/apps/com.parsel.app.png"
    done
else
    mkdir -p "$data/applications"
    cp "$here/com.parsel.app.desktop" "$data/applications/"
    for f in "$here"/icons/*.png; do
        size=$(basename "$f" .png)
        mkdir -p "$data/icons/hicolor/${size}x${size}/apps"
        cp "$f" "$data/icons/hicolor/${size}x${size}/apps/com.parsel.app.png"
    done
fi
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$data/applications" || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t "$data/icons/hicolor" || true
