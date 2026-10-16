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
    // The package's "main" file is not published; Vite builds use "module".
    alias: { '@xterm/addon-ligatures': '@xterm/addon-ligatures/lib/addon-ligatures.mjs' },
  },
});
