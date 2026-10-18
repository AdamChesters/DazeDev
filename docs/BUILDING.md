# Building DazeDev

The desktop application is in `apps/desktop`. It shares one Svelte/Tauri codebase across Windows, Linux and macOS. Each platform needs its native prerequisites and validation.

From the repository root:

- `npm --prefix apps/desktop ci` installs the locked frontend dependencies.
- `npm run gate` checks types, runs frontend tests and builds the frontend.
- `npm run test:host` runs the Python host tests; tmux is required for integration coverage.
- `npm run test:rust` runs the Rust tests without native voice runtimes.
- `npm run tauri -- dev` starts the desktop app after runtime setup.

Run `node scripts/prepare-runtime.mjs` from `apps/desktop` to download its pinned voice runtimes. That command downloads files; it is separate from the source checks.

For Windows releases, run `scripts/build-windows.ps1` from an initialized MSVC environment in `apps/desktop`, then `scripts/package-portable.ps1`. The build remaps local source paths. Keep the complete portable folder together; voice models are downloaded in-app. Unix portable archives use `scripts/package-unix.sh` in the same directory.

The icon source is [the DazeDev logo](../apps/desktop/public/brand.svg). Desktop builds regenerate package icons with `scripts/generate-icons.mjs`.

## Linux packages

Build Linux packages on Ubuntu 22.04. From `apps/desktop`, after the checks above, run `node scripts/prepare-runtime.mjs`, `node scripts/collect-licenses.mjs`, the Tauri build below, `bash scripts/rename-deb.sh src-tauri/target/release/bundle/deb/*.deb` and `bash scripts/package-unix.sh`. This produces:

- `dazedev_<version>_amd64.deb`: package `dazedev` (scripts/rename-deb.sh renames Tauri's `daze-dev`). It installs `/usr/bin/DazeDev`, the voice runtimes in `/usr/lib/DazeDev/runtime`, and a desktop menu entry. apt installs WebKitGTK 4.1, GTK 3, BLAS (`libblas3`), libgomp1 and libssl3. python3, tmux and the GStreamer PulseAudio plugins are Recommends.
- `DazeDev_<version>_amd64.AppImage`: self-contained, including WebKitGTK and GStreamer from Ubuntu 22.04. It needs FUSE 2 (`libfuse2`/`libfuse2t64`) to run directly.
- `DazeDev_<version>_linux-x64.tar.gz`: the portable folder, started with `Start DazeDev.sh`.

Ubuntu 22.04 is the build base because its glibc (2.35) is the oldest in the supported range: one build runs on Mint 21.x (Ubuntu 22.04) and Mint 22.x (Ubuntu 24.04). The bundled llama.cpp and ONNX Runtime binaries need at most GLIBC 2.34 and GLIBCXX 3.4.30. Check the packaged binaries with `objdump -T` before publishing.

The .deb and AppImage store settings, models and logs in `~/.local/share/DazeDev` (or `$XDG_DATA_HOME/DazeDev`), because their program folders are read-only. The portable archive keeps `data/` beside the app. `DAZEDEV_DATA_DIR` overrides both. Automatic updates are Windows-only: on Linux, install the new .deb or replace the AppImage, and settings are kept.

After building, install the .deb into clean `linuxmintd/mint22.3-amd64` and `linuxmintd/mint21.3-amd64` containers with `scripts/verify-linux-install.sh ARTIFACT_DIR RESULT_DIR` (run the container with `--privileged`; the WebKit sandbox needs user namespaces). The script runs as root in a disposable system only: it installs packages and creates a test user. Inside, apt resolves the dependencies, every installed binary must resolve its libraries, and `scripts/verify-linux-startup.py` drives the installed app under Xvfb through WebKitWebDriver. The script also runs the installed voice runtime and starts the AppImage. Results, screenshots and logs go to `RESULT_DIR`. The latest results are in [platform support](PLATFORM-SUPPORT.md#linux-desktop-test-packages).

The Linux build needs `libwebkit2gtk-4.1-dev libgtk-3-dev libssl-dev librsvg2-dev libayatana-appindicator3-dev patchelf file` plus the GStreamer base, good and PulseAudio plugins for the AppImage. Build with `npm run tauri -- build --bundles deb,appimage -- --locked`; `src-tauri/tauri.linux.conf.json` holds the Linux package settings.

## Android

Install the desktop's locked npm dependencies first; Android generates its xterm bundle and launcher icons from those sources. Set `JAVA_HOME` to a supported JDK and `ANDROID_HOME` to an installed Android SDK with platform/build tools 36. From `apps/android`, run `./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease`. Release assembly produces an unsigned APK; signing and publication are separate.

For SSH integration coverage, create a disposable Python environment outside the repository with `paramiko==5.0.0`, then run `python3 scripts/check-local.py --fixture-python /path/to/venv/bin/python` from `apps/android`. It starts loopback fixtures and an isolated tmux socket, runs the local checks, and stops its fixtures. Without these endpoints the SSH tests skip and do not establish integration coverage. Physical-device IME, voice, thermal and memory checks remain necessary.
