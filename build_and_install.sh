#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PREFIX="/opt/acglass"
BUILD_DIR="$SCRIPT_DIR/weston/builddir"

echo "=== Installing build dependencies ==="
apt-get update -qq
apt-get install -y -qq \
    build-essential meson ninja-build pkg-config \
    libwayland-dev libpixman-1-dev libxkbcommon-dev \
    libinput-dev libevdev-dev libdrm-dev libgbm-dev \
    libudev-dev libseat-dev libcairo2-dev \
    libjpeg-dev libwebp-dev libpam0g-dev \
    libgles-dev libegl-dev libvulkan-dev glslang-tools \
    libxcb-composite0-dev libxcb-shape0-dev libxcb-xfixes0-dev \
    libxcursor-dev libxcb1-dev \
    libpango1.0-dev libglib2.0-dev \
    libwayland-cursor0 wayland-protocols libwayland-bin \
    libpng-dev libfontconfig-dev libfreetype-dev \
    hwdata

echo "=== Patching wayland-protocols ==="
cp -v "$SCRIPT_DIR/wayland-protocols-override/staging/color-representation/color-representation-v1.xml" \
    /usr/share/wayland-protocols/staging/color-representation/color-representation-v1.xml

echo "=== Fixing subproject wraps ==="
rm -f "$SCRIPT_DIR/weston/subprojects/edid-decode.wrap"

echo "=== Configuring weston ==="
MESON_OPTS=(
    --prefix="$PREFIX"
    -Dbackend-drm=false
    -Dbackend-headless=false
    -Dbackend-pipewire=false
    -Dbackend-rdp=false
    -Dbackend-vnc=false
    -Dbackend-wayland=false
    -Dbackend-x11=false
    -Dbackend-anland=true
    -Dbackend-default=auto

    -Drenderer-gl=true
    -Drenderer-vulkan=true
    -Dxwayland=true
    -Dcolor-management-lcms=false
    -Dimage-jpeg=true
    -Dimage-webp=true
    -Dshell-desktop=true
    -Dshell-kiosk=true
    -Dshell-ivi=false
    -Dshell-lua=false
    -Ddemo-clients=false
    -Dsimple-clients=[]
    -Dtools=terminal
    -Dsystemd=false
    -Dtests=false
    -Ddoc=false
    -Dperfetto=false
    -Ddeprecated-remoting=false
    -Ddeprecated-pipewire=false
)

if [ -d "$BUILD_DIR" ]; then
    meson setup --reconfigure "$BUILD_DIR" "$SCRIPT_DIR/weston" "${MESON_OPTS[@]}"
else
    meson setup "$BUILD_DIR" "$SCRIPT_DIR/weston" "${MESON_OPTS[@]}"
fi

echo "=== Building weston ==="
ninja -C "$BUILD_DIR" -j$(nproc)

echo "=== Installing to $PREFIX ==="
ninja -C "$BUILD_DIR" install

ldconfig "$PREFIX/lib/aarch64-linux-gnu"

LIBDIR="$PREFIX/lib/aarch64-linux-gnu"
cat > "$PREFIX/start.sh" << EOF
#!/bin/bash
SOCK="\${1:-/run/display.sock}"

export LD_LIBRARY_PATH="$LIBDIR:$LIBDIR/libweston-16:$LIBDIR/weston:\$LD_LIBRARY_PATH"
export XDG_RUNTIME_DIR="\${XDG_RUNTIME_DIR:-/tmp}"
export WESTON_MODULE_MAP="anland-backend.so=$LIBDIR/libweston-16/anland-backend.so;gl-renderer.so=$LIBDIR/libweston-16/gl-renderer.so;vulkan-renderer.so=$LIBDIR/libweston-16/vulkan-renderer.so;desktop-shell.so=$LIBDIR/weston/desktop-shell.so;xwayland.so=$LIBDIR/libweston-16/xwayland.so"

# Native freedreno GL on the kgsl GPU node (loader name "kgsl"). GALLIUM_DRIVER
# is set so EGL does NOT silently fall back to zink if freedreno init fails
# (we require freedreno end-to-end).
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export GALLIUM_DRIVER=kgsl
# Force the freedreno DRM layer to open /dev/kgsl-3d0 directly. EGL/GBM clients
# (Xwayland glamor, GL apps) are otherwise handed the sde-kms display node
# (renderD128), which drmGetVersion reports as "msm" with no GPU behind it ->
# half-initialised screen -> crash. The Adreno GPU is kgsl-only on this device.
export FD_FORCE_KGSL=1
export MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE=1
export MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE_DRI3=1

shift 2>/dev/null || true
exec $PREFIX/bin/weston -Banland-backend.so --disp-sock="\$SOCK" --xwayland "\$@"
EOF
chmod +x "$PREFIX/start.sh"

cat > "$PREFIX/start_app.sh" << 'EOF'
#!/bin/bash
SOCK="${ACGLASS_SOCKET:-/run/display.sock}"
ANDROID_SOCK="${ACGLASS_ANDROID_SOCKET:-/data/local/tmp/display_daemon.sock}"
DAEMON_BIN="${ACGLASS_DAEMON_BIN:-/data/adb/modules/acglass-daemon/display_daemon}"
ACTIVITY="${ACGLASS_ACTIVITY:-com.acglass.app/.DisplayActivity}"
AM_CMD="${ACGLASS_AM_CMD:-am}"
START_ANDROID="${ACGLASS_START_ANDROID:-0}"
START_DAEMON="${ACGLASS_START_DAEMON:-0}"

if [ "$#" -gt 0 ] && [ "${1:-}" != "--" ] && [ "${1#/}" != "$1" ]; then
    SOCK="$1"
    shift
fi

if [ "$#" -eq 0 ]; then
    echo "Usage: $0 [socket-path] [--] <wayland-app> [args...]"
    exit 2
fi
if [ "${1:-}" = "--" ]; then
    shift
fi
if [ "$#" -eq 0 ]; then
    echo "ERROR: missing application command"
    exit 2
fi

ensure_daemon() {
    [ "$START_DAEMON" = "1" ] || return 0
    [ -S "$SOCK" ] && return 0
    if [ ! -x "$DAEMON_BIN" ]; then
        echo "WARN: display daemon socket not found and daemon binary is not executable: $DAEMON_BIN" >&2
        return 0
    fi
    mkdir -p "$(dirname "$SOCK")"
    "$DAEMON_BIN" "$SOCK" >/tmp/acglass-display-daemon.log 2>&1 &
    for i in $(seq 1 80); do
        [ -S "$SOCK" ] && return 0
        sleep 0.1
    done
    echo "WARN: display daemon did not create socket: $SOCK" >&2
}

start_android_consumer() {
    [ "$START_ANDROID" = "1" ] || return 0
    command -v ${AM_CMD%% *} >/dev/null 2>&1 || {
        echo "WARN: Android activity launcher not found: $AM_CMD" >&2
        echo "      Set ACGLASS_AM_CMD or start $ACTIVITY manually." >&2
        return 0
    }
    $AM_CMD start -n "$ACTIVITY" --es com.acglass.app.SOCKET "$ANDROID_SOCK" >/dev/null 2>&1 || {
        echo "WARN: failed to start Android consumer Activity: $ACTIVITY" >&2
        return 0
    }
}

ensure_consumer_ready() {
    for i in $(seq 1 120); do
        [ -S "$SOCK" ] && return 0
        sleep 0.1
    done
}

ensure_daemon
start_android_consumer
ensure_consumer_ready

if [ ! -S "$SOCK" ]; then
    echo "ERROR: display daemon socket not found at container path: $SOCK" >&2
    echo "       Bind-mount /data/local/tmp/display_daemon.sock into the container, or set ACGLASS_SOCKET." >&2
    exit 1
fi

export LD_LIBRARY_PATH="@LIBDIR@:@LIBDIR@/libweston-16:@LIBDIR@/weston:$LD_LIBRARY_PATH"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 0700 "$XDG_RUNTIME_DIR"
export WESTON_MODULE_MAP="anland-backend.so=@LIBDIR@/libweston-16/anland-backend.so;gl-renderer.so=@LIBDIR@/libweston-16/gl-renderer.so;vulkan-renderer.so=@LIBDIR@/libweston-16/vulkan-renderer.so;xwayland.so=@LIBDIR@/libweston-16/xwayland.so;kiosk-shell.so=@LIBDIR@/weston/kiosk-shell.so"
unset DISPLAY

# Native freedreno GL on the kgsl GPU node. The gallium driver is registered
# as "kgsl" in the patched Mesa used by this container stack.
export MESA_LOADER_DRIVER_OVERRIDE="${MESA_LOADER_DRIVER_OVERRIDE:-kgsl}"
export GALLIUM_DRIVER="${GALLIUM_DRIVER:-kgsl}"
export FD_FORCE_KGSL="${FD_FORCE_KGSL:-1}"
export MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE="${MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE:-1}"
export MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE_DRI3="${MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE_DRI3:-1}"

WESTON_PID=
APP_PID=

cleanup() {
    [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null
    [ -n "$WESTON_PID" ] && kill "$WESTON_PID" 2>/dev/null
    sleep 0.3
    [ -n "$APP_PID" ] && kill -9 "$APP_PID" 2>/dev/null
    [ -n "$WESTON_PID" ] && kill -9 "$WESTON_PID" 2>/dev/null
    wait 2>/dev/null
}
trap cleanup EXIT INT TERM

rm -f "${XDG_RUNTIME_DIR}"/wayland-acglass-app "${XDG_RUNTIME_DIR}"/wayland-acglass-app.lock 2>/dev/null

@PREFIX@/bin/weston -Banland-backend.so --disp-sock="$SOCK" --shell=kiosk-shell.so --socket=wayland-acglass-app --no-config &
WESTON_PID=$!

WESTON_SOCKET="wayland-acglass-app"
for i in $(seq 1 160); do
    sleep 0.25
    [ -S "${XDG_RUNTIME_DIR}/${WESTON_SOCKET}" ] && break
done

if [ ! -S "${XDG_RUNTIME_DIR}/${WESTON_SOCKET}" ]; then
    echo "ERROR: weston wayland socket not found"
    wait "$WESTON_PID"
    exit 1
fi

export WAYLAND_DISPLAY="$WESTON_SOCKET"
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-wayland}"
export SDL_VIDEODRIVER="${SDL_VIDEODRIVER:-wayland}"
export GDK_BACKEND="${GDK_BACKEND:-wayland,x11}"

"$@" &
APP_PID=$!

wait "$APP_PID"
APP_STATUS=$?
exit "$APP_STATUS"
EOF
sed -i "s|@LIBDIR@|$LIBDIR|g; s|@PREFIX@|$PREFIX|g" "$PREFIX/start_app.sh"
chmod +x "$PREFIX/start_app.sh"
ln -sf "$PREFIX/start_app.sh" "$PREFIX/acglass-run"
ln -sf "$PREFIX/start_app.sh" "$PREFIX/anland-run"
if [ -d /usr/local/bin ] && [ -w /usr/local/bin ]; then
    ln -sf "$PREFIX/start_app.sh" /usr/local/bin/acglass-run
    ln -sf "$PREFIX/start_app.sh" /usr/local/bin/anland-run
fi
cat > "$PREFIX/acglass-wrap" << 'EOF'
#!/bin/bash
set -e

WRAP_DIR="${ACGLASS_WRAP_DIR:-/usr/local/bin}"
RUNNER="${ACGLASS_RUNNER:-/opt/acglass/acglass-run}"

if [ "$#" -eq 0 ]; then
    echo "Usage: $0 <gui-command> [gui-command...]"
    echo "Creates wrappers so running those commands opens them through acglass-run."
    exit 2
fi

mkdir -p "$WRAP_DIR"

for app in "$@"; do
    search_path=
    old_ifs="$IFS"
    IFS=:
    for dir in $PATH; do
        [ "$dir" = "$WRAP_DIR" ] && continue
        if [ -z "$search_path" ]; then
            search_path="$dir"
        else
            search_path="$search_path:$dir"
        fi
    done
    IFS="$old_ifs"
    target="$(PATH="$search_path" command -v "$app" || true)"
    if [ -z "$target" ]; then
        echo "WARN: command not found, skipping: $app" >&2
        continue
    fi

    wrapper="$WRAP_DIR/$app"
    cat > "$wrapper" << WRAPEOF
#!/bin/bash
exec "$RUNNER" -- "$target" "\$@"
WRAPEOF
    chmod +x "$wrapper"
    echo "wrapped: $app -> $target"
done
EOF
chmod +x "$PREFIX/acglass-wrap"
ln -sf "$PREFIX/acglass-wrap" "$PREFIX/anland-wrap"
if [ -d /usr/local/bin ] && [ -w /usr/local/bin ]; then
    ln -sf "$PREFIX/acglass-wrap" /usr/local/bin/acglass-wrap
    ln -sf "$PREFIX/acglass-wrap" /usr/local/bin/anland-wrap
fi
cat > "$PREFIX/acglass-sync-apps" << 'EOF'
#!/bin/bash
set -e

AM_CMD="${ACGLASS_AM_CMD:-am}"
ACTION="${ACGLASS_SYNC_ACTION:-com.acglass.app.SYNC_APPS}"
RECEIVER="${ACGLASS_SYNC_RECEIVER:-com.acglass.app/.AppsReceiver}"

escape_json() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/\\t/g'
}

desktop_value() {
    key="$1"
    file="$2"
    grep -m1 "^${key}=" "$file" 2>/dev/null | sed "s/^${key}=//"
}

clean_exec() {
    printf '%s' "$1" |
        sed 's/ %[fFuUdDnNickvm]//g; s/%%/%/g; s/[[:space:]]*$//'
}

apps_json="["
first=1

for dir in \
    /usr/share/applications \
    /usr/local/share/applications \
    "$HOME/.local/share/applications"; do
    [ -d "$dir" ] || continue
    while IFS= read -r desktop; do
        type="$(desktop_value Type "$desktop")"
        no_display="$(desktop_value NoDisplay "$desktop")"
        hidden="$(desktop_value Hidden "$desktop")"
        terminal="$(desktop_value Terminal "$desktop")"
        name="$(desktop_value Name "$desktop")"
        exec_line="$(desktop_value Exec "$desktop")"

        [ "$type" = "Application" ] || continue
        [ "$no_display" = "true" ] && continue
        [ "$hidden" = "true" ] && continue
        [ "$terminal" = "true" ] && continue
        [ -n "$name" ] || continue
        [ -n "$exec_line" ] || continue

        command="$(clean_exec "$exec_line")"
        [ -n "$command" ] || continue

        if [ "$first" -eq 0 ]; then
            apps_json="$apps_json,"
        fi
        first=0
        apps_json="$apps_json{\"name\":\"$(escape_json "$name")\",\"command\":\"$(escape_json "$command")\"}"
    done < <(find "$dir" -maxdepth 1 -type f -name '*.desktop' | sort)
done

apps_json="$apps_json]"

if command -v ${AM_CMD%% *} >/dev/null 2>&1; then
    $AM_CMD broadcast -n "$RECEIVER" -a "$ACTION" --es com.acglass.app.APPS_JSON "$apps_json" >/dev/null
else
    echo "$apps_json"
fi
EOF
chmod +x "$PREFIX/acglass-sync-apps"
ln -sf "$PREFIX/acglass-sync-apps" "$PREFIX/anland-sync-apps"
if [ -d /usr/local/bin ] && [ -w /usr/local/bin ]; then
    ln -sf "$PREFIX/acglass-sync-apps" /usr/local/bin/acglass-sync-apps
    ln -sf "$PREFIX/acglass-sync-apps" /usr/local/bin/anland-sync-apps
fi

echo ""
echo "=== Done ==="
echo "  Installed to: $PREFIX"
echo "  Start:        $PREFIX/start.sh [socket-path]"
echo "  Start app:    $PREFIX/start_app.sh [socket-path] -- <wayland-app> [args...]"
echo "  Default sock: /run/display.sock"
