/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    "./*.html",
    "./static/**/*.js",
    // main.py renders standalone HTML pages (e.g. the SSO mock-login) with
    // Tailwind classes; include it so a local build covers those too.
    "./main.py",
    // diffing.py builds <ins>/<del> diff markup from literal class strings
    // (INS/DEL templates) — scan it so dark-mode/strikethrough utilities
    // used only there aren't purged from the production build.
    "./diffing.py"
  ],
  // Dynamic classes assembled at runtime from DB values (status badges built in
  // app-renderers.js statusStylesMap, pastel category colors, dynamic card
  // themes). Safelisted so the production build never purges them even when no
  // static literal is present in the scanned source.
  safelist: [
    {
      pattern: /^(bg|text|border)-(emerald|green|gray|slate|blue|amber|yellow|red|purple|pink|indigo)-(50|100|200|400|500|600|700)$/,
    },
    { pattern: /^bg-(emerald|gray|slate|blue|amber|red|green|purple)-(400|500)$/ },  // status dots
  ],
  theme: {
    extend: {
      colors: {
        magti: "#B91C1C",
      },
      fontFamily: {
        teko: ['Teko', 'sans-serif'],
      }
    },
  },
  plugins: [],
}
