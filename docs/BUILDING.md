# Building DazeDev

The desktop application is in `apps/desktop`. It shares one Svelte/Tauri codebase across Windows, Linux and macOS. Each platform needs its native prerequisites and validation.

From the repository root:

- `npm --prefix apps/desktop ci` installs the locked frontend dependencies.
- `npm run gate` checks types, runs frontend tests and builds the frontend.
- `npm run test:host` runs the Python host tests; tmux is required for integration coverage.
- `npm run test:rust` runs the Rust tests without native voice runtimes.
- `npm run tauri -- dev` starts the desktop app after runtime setup.

Run `node scripts/prepare-runtime.mjs` from `apps/desktop` to download its pinned voice runtimes. That command downloads files; it is separate from the source checks.

For Windows releases, run `scripts/build-windows.ps1` from an initialized MSVC environment in `apps/desktop`, then `scripts/package-portable.ps1`. The build remaps local source paths. Keep the complete portable folder together; voice models are downloaded in-app. Unix packaging uses `scripts/package-unix.sh` in the same directory.

The icon source is [the DazeDev logo](../apps/desktop/public/brand.svg). Desktop builds regenerate package icons with `scripts/generate-icons.mjs`.
