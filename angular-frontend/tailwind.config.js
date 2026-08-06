/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    './src/**/*.{html,ts}'
  ],
  theme: {
    extend: {
      colors: {
        magti: '#B91C1C'
      },
      fontFamily: {
        teko: ['Teko', 'sans-serif']
      }
    }
  },
  plugins: []
};
