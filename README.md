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
- The Linux side starts a minimal Weston session with the Anland backend and
  `kiosk-shell`, then runs only the requested GUI app.

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

- Wayland display socket path.
- Droidspaces CLI path.

Defaults:

```text
Wayland socket: /data/local/tmp/display_daemon.sock
Droidspaces CLI: /data/local/Droidspaces/bin/droidspaces
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
acglass-run /data/local/tmp/display_daemon.sock -- <gui-command> [args...]
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
ACGLASS_SOCKET=/data/local/tmp/display_daemon.sock
ACGLASS_DAEMON_BIN=/data/adb/modules/acglass-daemon/display_daemon
ACGLASS_AM_CMD=am
ACGLASS_ACTIVITY=com.acglass.app/.DisplayActivity
ACGLASS_START_DAEMON=1
ACGLASS_START_ANDROID=1
```

The lower-level Weston display backend is still named `anland` internally.

## Current Limitations

- Automatic app scanning currently targets Debian/Ubuntu `.desktop` locations.
- Only running Droidspaces containers are auto-discovered.
- Multiple independent GUI windows are planned, but the native Android consumer
  still needs per-window state isolation for robust multi-window operation.

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
