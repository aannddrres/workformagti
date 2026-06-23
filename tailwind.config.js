/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    "./*.html",
    "./static/**/*.js"
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
