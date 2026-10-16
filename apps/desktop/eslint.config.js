import js from '@eslint/js';
import svelte from 'eslint-plugin-svelte';
import globals from 'globals';
import ts from 'typescript-eslint';
import svelteConfig from './svelte.config.js';

// Type-aware rules (no-floating-promises) are off: they made a full run take
// about 6 minutes instead of 1 and found no real bugs here.
export default ts.config(
  { ignores: ['dist/', 'node_modules/', 'src-tauri/', 'public/'] },
  js.configs.recommended,
  ...ts.configs.recommended,
  ...svelte.configs.recommended,
  {
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
  },
  {
    files: ['**/*.svelte', '**/*.svelte.ts'],
    languageOptions: {
      parserOptions: { extraFileExtensions: ['.svelte'], parser: ts.parser, svelteConfig },
    },
  },
  {
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      'no-empty': ['error', { allowEmptyCatch: true }],
      // Terminal code matches control characters on purpose.
      'no-control-regex': 'off',
      // Adding keys changes how Svelte reuses DOM nodes; not a lint-only fix.
      'svelte/require-each-key': 'off',
      // Flags private bookkeeping Maps/Sets that are not meant to be reactive.
      'svelte/prefer-svelte-reactivity': 'off',
    },
  },
  {
    files: ['**/*.svelte'],
    rules: {
      // Bare reads inside $effect declare dependencies.
      '@typescript-eslint/no-unused-expressions': 'off',
      // Flags default values of write-only $bindable props.
      'no-useless-assignment': 'off',
      // A string mustache keeps exact whitespace in markup.
      'svelte/no-useless-mustaches': 'off',
    },
  },
  {
    files: ['tests/**'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-this-alias': 'off',
    },
  },
);
