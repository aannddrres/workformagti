// @ts-check
//
// Until 2026-09-05 this project had no TypeScript linter at all: no config, no
// `lint` script, no CI step. Prettier was configured and never run by anything.
// That is 158 source files with no automated opinion on them, in a codebase
// whose Java half is held together by eight coverage tests.
//
// The baseline is deliberately the recommended sets and nothing beyond them.
// A linter introduced with a wish-list of rules produces hundreds of findings
// on day one, gets excluded from CI "for now", and never gates anything. This
// one gates from the first commit. Tighten it by turning rules on one at a
// time, with the fixes in the same commit.

const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = tseslint.config(
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      '.angular/**',
      'coverage/**',
      'playwright-report/**',
      'test-results/**',
      'bom.json',
    ],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...angular.configs.tsRecommended,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'app', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'app', style: 'kebab-case' },
      ],
    },
  },
  {
    // Specs reach for node:fs to read the shared visibility fixture, and
    // Vitest globals are ambient.
    files: ['**/*.spec.ts', 'e2e/**/*.ts', 'scripts/**/*.mjs'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended],
    rules: {},
  },
);
