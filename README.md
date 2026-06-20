# ACGlass - a Wayland viewer and GUI app manager for Android Containers

ACGlass lets Linux GUI applications from Android-hosted containers appear as
Android application windows. It is based on the Anland Wayland display path, but
adds an Android launcher, Droidspaces integration, root-assisted container app
scanning, and single-app window startup.

Currently support Droidspaces Container.

## Status

ACGlass is currently an early Android-container GUI bridge. The first supported
backend is Droidspaces running Debian or Ubuntu-like root filesystems.

The current design is:

- The ACGlass launcher opens a GUI app manager.
- The launcher checks root through `su -c` before scanning or launching apps.
- Running Droidspaces containers are discovered through
  `/data/local/Droidspaces/bin/droidspaces show --format`.
- Debian/Ubuntu `.desktop` entries are scanned inside each running container.
- Selecting an app opens a separate Android display task and starts the Linux
  command through Droidspaces.
- The Linux side starts a shared Weston session with the Anland backend on
  demand, launches the requested GUI app inside that session, reuses the same
  session for later apps, and stops Weston after the last tracked GUI app exits.
- Weston forwards top-level window open, close, minimize, and restore events to
  the Android display task through the ACGlass compositor window-event channel.

## Repository Layout

- `android_consumer/` - Android application for the launcher and display window.
- `daemon/` - Android-side display daemon.
- `libdisplay_consumer/` - consumer-side display buffer reader.
- `libdisplay_producer/` - producer-side display buffer writer.
- `weston/` - patched Weston tree with the Anland backend.
- `magisk_module/` - display daemon module template.

## Requirements

Android device:

- Root access through Magisk, KernelSU, APatch, or another compatible `su`.
- Droidspaces CLI installed at `/data/local/Droidspaces/bin/droidspaces`.
- A running Droidspaces container.
- Android SDK/NDK for building the Android app.

Container:

- Debian or Ubuntu-like userspace.
- `bash`, `python3`, `dbus`, and Wayland GUI dependencies.
- Build dependencies listed in `build_and_install.sh`.

## Build Android App

From the repository root:

```sh
cd android_consumer
./gradlew assembleDebug
```

Install the generated APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android app id is:

```text
com.acglass.app
```

## Build Display Daemon Module

Build the Android daemon:

```sh
./build_daemon_android.sh
```

Package the Magisk-style module:

```sh
./build_magisk_module.sh
```

The default daemon path used by ACGlass is:

```text
/data/adb/modules/acglass-daemon/display_daemon
```

## Install Container Backend

Package the Linux-side source:

```sh
./pack_src.sh
```

Push it into a Droidspaces rootfs or another location visible inside the target
container, then run the installer inside the container:

```sh
adb push acglass-src.tar.gz /data/local/tmp/
adb shell /data/local/Droidspaces/bin/droidspaces --name=<container> run bash -lc \
  'cd / && tar xzf /data/local/tmp/acglass-src.tar.gz && bash build_and_install.sh'
```

The installer writes ACGlass into:

```text
/opt/acglass
```

Main installed commands:

```text
/opt/acglass/acglass-run
/opt/acglass/acglass-wrap
/opt/acglass/acglass-sync-apps
```

Compatibility symlinks named `anland-run`, `anland-wrap`, and
`anland-sync-apps` are also created where possible.

## Droidspaces Backend Configuration

ACGlass expects the Droidspaces command format used by Droidspaces v6.3.0:

```sh
/data/local/Droidspaces/bin/droidspaces show --format
/data/local/Droidspaces/bin/droidspaces --name=<container> run bash -lc '<command>'
```

The Android settings page lets you configure:

- Android display socket path.
- Container display socket path.
- Droidspaces CLI path.

Defaults:

```text
Android display socket: /data/local/tmp/display_daemon.sock
Container display socket: /run/display.sock
Droidspaces CLI: /data/local/Droidspaces/bin/droidspaces
```

The Android socket is created by the ACGlass Magisk module daemon. The
container socket is the same Unix domain socket as seen from inside the Linux
container. For Droidspaces, bind-mount the Android socket into the container,
for example:

```text
/data/local/tmp/display_daemon.sock -> /run/display.sock
```

If a container is not listed by `droidspaces show --format`, ACGlass will not
scan it automatically.

## Launching GUI Apps

From the Android launcher:

1. Open ACGlass.
2. Grant root access when requested.
3. Tap `Scan Linux Apps`.
4. Select an app under a running Droidspaces container.

From inside the Linux container:

```sh
acglass-run <gui-command> [args...]
```

With an explicit socket:

```sh
acglass-run /run/display.sock -- <gui-command> [args...]
```

Create wrappers for common GUI commands:

```sh
acglass-wrap firefox gedit
```

After that, running `firefox` or `gedit` from the container shell routes through
ACGlass automatically.

## Environment Variables

Common overrides:

```sh
ACGLASS_SOCKET=/run/display.sock
ACGLASS_ANDROID_SOCKET=/data/local/tmp/display_daemon.sock
ACGLASS_DAEMON_BIN=/data/adb/modules/acglass-daemon/display_daemon
ACGLASS_AM_CMD=am
ACGLASS_ACTIVITY=com.acglass.app/.DisplayActivity
ACGLASS_START_DAEMON=0
ACGLASS_START_ANDROID=0
ACGLASS_SESSION_DIR=/tmp/acglass-runtime-1000/acglass-session
ACGLASS_WAYLAND_DISPLAY=wayland-acglass
ACGLASS_IDLE_GRACE_SECONDS=2
```

`ACGLASS_START_DAEMON` and `ACGLASS_START_ANDROID` default to `0` because the
normal Droidspaces path starts the Android DisplayActivity from the ACGlass app,
not from inside the Linux container. Set them to `1` only for manual debugging
from an Android shell where the daemon binary and `am` command are available.

The lower-level Weston display backend is still named `anland` internally.

`acglass-run` manages one shared Weston session per `ACGLASS_SESSION_DIR`.
The first launched GUI app starts the session, later apps reuse the same
`WAYLAND_DISPLAY`/`DISPLAY` environment, and the session is stopped after the
last tracked app exits. Weston logs are written to
`$ACGLASS_SESSION_DIR/weston.log`.
When launched from Android, ACGlass automatically selects the first normal
container user with UID 1000-59999 and falls back to root only if no such user
exists.
Android-launched container commands enter through `/opt/acglass/shell_command.sh`,
which avoids Droidspaces argument loss around `bash -lc` for non-root users.
In the Android display task, pressing Back twice closes the current Linux app
and removes that display task from Android recents. Pressing Home leaves the
task in recents and keeps the Linux app running. When a Linux GUI window asks
Weston to minimize, ACGlass backgrounds the matching Android display task; when
the task is opened again from Android recents, ACGlass asks Weston to restore
that window. When the Linux GUI window closes, ACGlass removes the matching
Android display task.

## Current Limitations

- Automatic app scanning currently targets Debian/Ubuntu `.desktop` locations.
- Only running Droidspaces containers are auto-discovered.
- Weston session idle shutdown still uses tracked launch processes. Applications
  that daemonize into a different process group may require tuning
  `ACGLASS_IDLE_GRACE_SECONDS`.
- ACGlass does not yet provide an appindicator/system-tray host in Weston. This
  is planned for a later appindicator bridge; for now, Android task lifetime is
  driven by compositor window close/minimize events rather than tray presence.

## License

ACGlass original code and modifications are provided under the BSD 3-Clause
License. See `LICENSE`.

Some source files are derived from or include code from upstream projects. See
`NOTICE` for attribution and upstream provenance.

## Acknowledgements

- anland - android wayland implement:
  https://github.com/superturtlee/anland
- Droidspaces OSS - android container implement:
  https://github.com/ravindu644/Droidspaces-OSS
