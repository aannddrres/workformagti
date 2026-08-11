// Quill 1.3.7 ships no bundled TypeScript declarations (pinned to match the
// version base-layout.html loads from CDN). Ambient module so `import Quill
// from 'quill'` type-checks; call sites get `any` for Quill's own API,
// which is fine -- this port only touches a handful of well-known methods.
declare module 'quill';
