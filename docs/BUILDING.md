# Building DazeDev

- Install the platform prerequisites for Tauri, Rust and Node.js.
- `npm ci` installs the frontend dependencies.
- `npm run gate` checks types, runs frontend tests and builds the frontend.
- `cargo test --manifest-path src-tauri/Cargo.toml --no-default-features --locked` runs the Rust tests without native voice runtimes.
- `node scripts/prepare-runtime.mjs` downloads the pinned voice runtimes.
- `npm run tauri -- dev` starts the desktop app.

For Windows releases, run `scripts/build-windows.ps1` from an initialized MSVC environment, then `scripts/package-portable.ps1`. The build remaps local source paths. Keep the complete portable folder together; voice models are downloaded in-app.

The icon source is [`public/brand.svg`](../public/brand.svg). Builds regenerate package icons with `scripts/generate-icons.mjs`.
