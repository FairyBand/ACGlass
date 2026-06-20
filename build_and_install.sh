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
if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    DEFAULT_RUNTIME_DIR="/run/user/$(id -u)"
    if mkdir -p "$DEFAULT_RUNTIME_DIR" 2>/dev/null; then
        XDG_RUNTIME_DIR="$DEFAULT_RUNTIME_DIR"
    else
        XDG_RUNTIME_DIR="/tmp/acglass-runtime-$(id -u)"
    fi
fi
if ! mkdir -p "$XDG_RUNTIME_DIR" 2>/dev/null; then
    XDG_RUNTIME_DIR="/tmp/acglass-runtime-$(id -u)"
    mkdir -p "$XDG_RUNTIME_DIR"
fi
chmod 0700 "$XDG_RUNTIME_DIR"
export XDG_RUNTIME_DIR
export WESTON_MODULE_MAP="anland-backend.so=@LIBDIR@/libweston-16/anland-backend.so;gl-renderer.so=@LIBDIR@/libweston-16/gl-renderer.so;vulkan-renderer.so=@LIBDIR@/libweston-16/vulkan-renderer.so;xwayland.so=@LIBDIR@/libweston-16/xwayland.so;desktop-shell.so=@LIBDIR@/weston/desktop-shell.so;kiosk-shell.so=@LIBDIR@/weston/kiosk-shell.so"
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
APP_PGID=
APP_DONE=0
APP_MARKER=
APP_ID="${ACGLASS_APP_ID:-}"
APP_ID_FILE=
APP_LOG=
LOCK_HELD=0
SESSION_DIR="${ACGLASS_SESSION_DIR:-$XDG_RUNTIME_DIR/acglass-session}"
LOCK_DIR="$SESSION_DIR/lock"
APPS_DIR="$SESSION_DIR/apps"
APP_IDS_DIR="$APPS_DIR/by-id"
PID_FILE="$SESSION_DIR/weston.pid"
ENV_FILE="$SESSION_DIR/env"
LOG_FILE="$SESSION_DIR/weston.log"
WESTON_SOCKET="${ACGLASS_WAYLAND_DISPLAY:-wayland-acglass}"
WESTON_ARGS="${ACGLASS_WESTON_ARGS:---xwayland --no-config}"
IDLE_GRACE_SECONDS="${ACGLASS_IDLE_GRACE_SECONDS:-2}"

lock_session() {
    mkdir -p "$SESSION_DIR"
    while ! mkdir "$LOCK_DIR" 2>/dev/null; do
        lock_pid="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
        if [ -z "$lock_pid" ]; then
            rmdir "$LOCK_DIR" 2>/dev/null || true
            continue
        fi
        if [ -n "$lock_pid" ] && ! kill -0 "$lock_pid" 2>/dev/null; then
            rm -f "$LOCK_DIR/pid" 2>/dev/null || true
            rmdir "$LOCK_DIR" 2>/dev/null || true
            continue
        fi
        sleep 0.05
    done
    echo "$$" > "$LOCK_DIR/pid"
    LOCK_HELD=1
}

unlock_session() {
    LOCK_HELD=0
    rm -f "$LOCK_DIR/pid" 2>/dev/null || true
    rmdir "$LOCK_DIR" 2>/dev/null || true
}

weston_is_running() {
    [ -f "$PID_FILE" ] || return 1
    WESTON_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    [ -n "$WESTON_PID" ] && kill -0 "$WESTON_PID" 2>/dev/null
}

prune_app_markers() {
    mkdir -p "$APPS_DIR"
    for marker in "$APPS_DIR"/*; do
        [ -e "$marker" ] || continue
        [ -f "$marker" ] || continue
        case "$marker" in
            *.log) continue ;;
        esac
        pid="$(basename "$marker")"
        case "$pid" in
            *[!0-9]*|'') rm -f "$marker"; continue ;;
        esac
        pgid="$(sed -n 's/^pgid=//p' "$marker" 2>/dev/null | head -n 1)"
        if kill -0 "$pid" 2>/dev/null; then
            continue
        fi
        if [ -n "$pgid" ] && kill -0 -- "-$pgid" 2>/dev/null; then
            continue
        fi
        rm -f "$marker"
    done
}

has_active_apps() {
    prune_app_markers
    for marker in "$APPS_DIR"/*; do
        [ -e "$marker" ] && return 0
    done
    return 1
}

write_session_env() {
    {
        printf 'export XDG_RUNTIME_DIR=%q\n' "$XDG_RUNTIME_DIR"
        printf 'export WAYLAND_DISPLAY=%q\n' "$WAYLAND_DISPLAY"
        [ -n "${DISPLAY:-}" ] && printf 'export DISPLAY=%q\n' "$DISPLAY"
        printf 'export LD_LIBRARY_PATH=%q\n' "$LD_LIBRARY_PATH"
        printf 'export WESTON_MODULE_MAP=%q\n' "$WESTON_MODULE_MAP"
        printf 'export MESA_LOADER_DRIVER_OVERRIDE=%q\n' "$MESA_LOADER_DRIVER_OVERRIDE"
        printf 'export GALLIUM_DRIVER=%q\n' "$GALLIUM_DRIVER"
        printf 'export FD_FORCE_KGSL=%q\n' "$FD_FORCE_KGSL"
        printf 'export MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE=%q\n' "$MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE"
        printf 'export MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE_DRI3=%q\n' "$MESA_VK_DEVICE_SELECT_FORCE_DEFAULT_DEVICE_DRI3"
        printf 'export QT_QPA_PLATFORM=%q\n' "${QT_QPA_PLATFORM:-wayland}"
        printf 'export SDL_VIDEODRIVER=%q\n' "${SDL_VIDEODRIVER:-wayland}"
        printf 'export GDK_BACKEND=%q\n' "${GDK_BACKEND:-wayland,x11}"
    } > "$ENV_FILE"
}

load_session_env() {
    if [ -f "$ENV_FILE" ]; then
        # shellcheck disable=SC1090
        . "$ENV_FILE"
    fi
}

detect_x_display() {
    [ -d /tmp/.X11-unix ] || return 1
    before_file="$SESSION_DIR/x-sockets.before"
    for i in $(seq 1 80); do
        for socket in /tmp/.X11-unix/X*; do
            [ -S "$socket" ] || continue
            grep -Fxq "$socket" "$before_file" 2>/dev/null && continue
            num="${socket##*/X}"
            [ -n "$num" ] || continue
            DISPLAY=":$num"
            export DISPLAY
            return 0
        done
        sleep 0.1
    done
    return 1
}

start_weston_session() {
    mkdir -p "$APPS_DIR"
    prune_app_markers
    if weston_is_running && [ -S "$XDG_RUNTIME_DIR/$WESTON_SOCKET" ]; then
        load_session_env
        return 0
    fi

    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    [ -n "$old_pid" ] && kill "$old_pid" 2>/dev/null || true
    rm -f "$PID_FILE" "$ENV_FILE" "$XDG_RUNTIME_DIR/$WESTON_SOCKET" "$XDG_RUNTIME_DIR/$WESTON_SOCKET.lock" 2>/dev/null
    mkdir -p "$(dirname "$LOG_FILE")"

    if [ -d /tmp/.X11-unix ]; then
        find /tmp/.X11-unix -maxdepth 1 -type s -name 'X*' 2>/dev/null | sort > "$SESSION_DIR/x-sockets.before"
    else
        : > "$SESSION_DIR/x-sockets.before"
    fi

    if command -v setsid >/dev/null 2>&1; then
        setsid @PREFIX@/bin/weston -Banland-backend.so --disp-sock="$SOCK" --socket="$WESTON_SOCKET" $WESTON_ARGS >"$LOG_FILE" 2>&1 &
    else
        @PREFIX@/bin/weston -Banland-backend.so --disp-sock="$SOCK" --socket="$WESTON_SOCKET" $WESTON_ARGS >"$LOG_FILE" 2>&1 &
    fi
    WESTON_PID=$!
    echo "$WESTON_PID" > "$PID_FILE"

    for i in $(seq 1 160); do
        sleep 0.25
        if [ -S "${XDG_RUNTIME_DIR}/${WESTON_SOCKET}" ]; then
            export WAYLAND_DISPLAY="$WESTON_SOCKET"
            detect_x_display || true
            write_session_env
            return 0
        fi
        if ! kill -0 "$WESTON_PID" 2>/dev/null; then
            echo "ERROR: weston exited while starting; see $LOG_FILE" >&2
            rm -f "$PID_FILE"
            return 1
        fi
    done

    echo "ERROR: weston wayland socket not found; see $LOG_FILE" >&2
    kill "$WESTON_PID" 2>/dev/null || true
    sleep 0.3
    kill -9 "$WESTON_PID" 2>/dev/null || true
    rm -f "$PID_FILE"
    return 1
}

stop_weston_session_if_idle() {
    if [ "$LOCK_HELD" != "1" ]; then
        lock_session
        locked_here=1
    else
        locked_here=0
    fi
    sleep "$IDLE_GRACE_SECONDS"
    if ! has_active_apps; then
        if weston_is_running; then
            kill "$WESTON_PID" 2>/dev/null || true
            for i in $(seq 1 20); do
                kill -0 "$WESTON_PID" 2>/dev/null || break
                sleep 0.1
            done
            kill -9 "$WESTON_PID" 2>/dev/null || true
        fi
        rm -f "$PID_FILE" "$ENV_FILE" "$XDG_RUNTIME_DIR/$WESTON_SOCKET" "$XDG_RUNTIME_DIR/$WESTON_SOCKET.lock" 2>/dev/null
    fi
    [ "$locked_here" = "1" ] && unlock_session
}

kill_app() {
    if [ -n "$APP_PGID" ]; then
        kill -- "-$APP_PGID" 2>/dev/null || true
        sleep 0.2
        kill -9 -- "-$APP_PGID" 2>/dev/null || true
        return
    fi
    [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
}

wait_for_app_group() {
    [ -n "$APP_PGID" ] || return 0
    command -v pgrep >/dev/null 2>&1 || return 0
    while pgrep -g "$APP_PGID" >/dev/null 2>&1; do
        sleep 0.5
    done
}

cleanup() {
    if [ "$APP_DONE" != "1" ] && [ -n "$APP_PID" ]; then
        kill_app
        wait "$APP_PID" 2>/dev/null || true
    fi
    if [ -n "$APP_MARKER" ]; then
        rm -f "$APP_MARKER"
        [ -n "$APP_ID_FILE" ] && rm -f "$APP_ID_FILE"
        stop_weston_session_if_idle
    fi
    [ "$LOCK_HELD" = "1" ] && unlock_session
}
trap cleanup EXIT INT TERM

lock_session
if ! start_weston_session; then
    unlock_session
    exit 1
fi
load_session_env
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-wayland}"
export SDL_VIDEODRIVER="${SDL_VIDEODRIVER:-wayland}"
export GDK_BACKEND="${GDK_BACKEND:-wayland,x11}"

APP_LOG="$APPS_DIR/launch-$(date +%Y%m%d-%H%M%S)-$$.log"
case "$APP_ID" in
    *[!A-Za-z0-9_.:-]*)
        APP_ID=
        ;;
esac
if command -v setsid >/dev/null 2>&1; then
    setsid "$@" >"$APP_LOG" 2>&1 &
    APP_PGID=$!
else
    "$@" >"$APP_LOG" 2>&1 &
fi
APP_PID=$!
APP_MARKER="$APPS_DIR/$APP_PID"
{
    printf 'pgid=%s\n' "${APP_PGID:-$APP_PID}"
    printf 'command=%s\n' "$*"
    printf 'log=%s\n' "$APP_LOG"
} > "$APP_MARKER"
if [ -n "$APP_ID" ]; then
    mkdir -p "$APP_IDS_DIR"
    APP_ID_FILE="$APP_IDS_DIR/$APP_ID"
    printf '%s\n' "$APP_MARKER" > "$APP_ID_FILE"
fi
unlock_session

wait "$APP_PID"
APP_STATUS=$?
wait_for_app_group
{
    printf '\n[acglass-run] command exited with status %s at %s\n' "$APP_STATUS" "$(date)"
    printf '[acglass-run] command: %s\n' "$*"
} >> "$APP_LOG"
APP_DONE=1
rm -f "$APP_MARKER"
APP_MARKER=
[ -n "$APP_ID_FILE" ] && rm -f "$APP_ID_FILE"
APP_ID_FILE=
stop_weston_session_if_idle
exit "$APP_STATUS"
EOF
sed -i "s|@LIBDIR@|$LIBDIR|g; s|@PREFIX@|$PREFIX|g" "$PREFIX/start_app.sh"
chmod +x "$PREFIX/start_app.sh"
ln -sf "$PREFIX/start_app.sh" "$PREFIX/acglass-run"
ln -sf "$PREFIX/start_app.sh" "$PREFIX/anland-run"
cat > "$PREFIX/shell_command.sh" << 'EOF'
#!/bin/sh
set -eu

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <base64-command>" >&2
    exit 2
fi

encoded="$1"
if command -v base64 >/dev/null 2>&1; then
    command_text="$(printf '%s' "$encoded" | base64 -d)"
elif command -v python3 >/dev/null 2>&1; then
    command_text="$(python3 -c 'import base64,sys; print(base64.b64decode(sys.argv[1]).decode(), end="")' "$encoded")"
else
    echo "ERROR: base64 decoder not found" >&2
    exit 127
fi

exec /bin/bash -lc "$command_text"
EOF
chmod +x "$PREFIX/shell_command.sh"
cat > "$PREFIX/stop_app.sh" << 'EOF'
#!/bin/bash
set -u

APP_ID="${1:-${ACGLASS_APP_ID:-}}"
if [ -z "$APP_ID" ]; then
    echo "Usage: $0 <app-id>" >&2
    exit 2
fi

case "$APP_ID" in
    *[!A-Za-z0-9_.:-]*)
        echo "ERROR: invalid app id" >&2
        exit 2
        ;;
esac

if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    if [ -d "/run/user/$(id -u)" ]; then
        XDG_RUNTIME_DIR="/run/user/$(id -u)"
    else
        XDG_RUNTIME_DIR="/tmp/acglass-runtime-$(id -u)"
    fi
fi

SESSION_DIR="${ACGLASS_SESSION_DIR:-$XDG_RUNTIME_DIR/acglass-session}"
ID_FILE="$SESSION_DIR/apps/by-id/$APP_ID"
[ -f "$ID_FILE" ] || exit 0

MARKER="$(cat "$ID_FILE" 2>/dev/null || true)"
[ -f "$MARKER" ] || exit 0

pgid="$(sed -n 's/^pgid=//p' "$MARKER" 2>/dev/null | head -n 1)"
pid="$(basename "$MARKER")"

case "$pgid" in
    ''|*[!0-9]*)
        pgid=
        ;;
esac
case "$pid" in
    ''|*[!0-9]*)
        pid=
        ;;
esac

if [ -n "$pgid" ]; then
    kill -- "-$pgid" 2>/dev/null || true
    sleep 0.5
    kill -9 -- "-$pgid" 2>/dev/null || true
elif [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    sleep 0.5
    kill -9 "$pid" 2>/dev/null || true
fi
EOF
chmod +x "$PREFIX/stop_app.sh"
ln -sf "$PREFIX/stop_app.sh" "$PREFIX/acglass-stop"
if [ -d /usr/local/bin ] && [ -w /usr/local/bin ]; then
    ln -sf "$PREFIX/start_app.sh" /usr/local/bin/acglass-run
    ln -sf "$PREFIX/start_app.sh" /usr/local/bin/anland-run
    ln -sf "$PREFIX/stop_app.sh" /usr/local/bin/acglass-stop
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
