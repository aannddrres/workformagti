/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    "./*.html",
    "./static/**/*.js",
    // main.py renders standalone HTML pages (e.g. the SSO mock-login) with
    // Tailwind classes; include it so a local build covers those too.
    "./main.py"
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
