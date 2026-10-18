import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { svelteTesting } from '@testing-library/svelte/vite';
export default defineConfig({
  // svelteTesting only acts under Vitest: browser Svelte build and DOM cleanup.
  plugins: [svelte(), svelteTesting()],
  server: { port: 1420, strictPort: true },
  clearScreen: false,
  build: { target: 'es2022' },
  test: {
    // On a shared build machine, component tests that pass in under a second
    // can exceed the 5 s default when the machine is busy.
    testTimeout: process.env.CI ? 30_000 : 5_000,
    // The package's "main" file is not published; Vite builds use "module".
    alias: { '@xterm/addon-ligatures': '@xterm/addon-ligatures/lib/addon-ligatures.mjs' },
  },
});
