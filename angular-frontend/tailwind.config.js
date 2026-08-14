/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  content: [
    './src/**/*.{html,ts}'
  ],
  theme: {
    extend: {
      colors: {
        // Driven by the CSS custom properties in src/styles.css, which is the
        // single place the brand colour is defined. The rgb(<channels> /
        // <alpha-value>) form is what lets Tailwind's opacity modifiers keep
        // working through a variable — `bg-brand/10` and `dark:bg-brand/20`
        // both resolve correctly.
        //
        // The old `magti: '#B91C1C'` entry is deliberately gone: it gave a
        // stock Tailwind red the name of the company, so a framework default
        // read as a brand decision in every file that used it.
        brand: {
          DEFAULT: 'rgb(var(--brand-600) / <alpha-value>)',
          600: 'rgb(var(--brand-600) / <alpha-value>)',
          700: 'rgb(var(--brand-700) / <alpha-value>)'
        }
      },
      fontFamily: {
        // Georgian-first stack, defined once in styles.css.
        //
        // The previous `teko: ['Teko', 'sans-serif']` entry is removed: no
        // stylesheet ever loaded Teko and `font-teko` appeared zero times in
        // the source, so it was dead configuration that implied a typographic
        // decision the app never actually made.
        sans: ['var(--font-sans)']
      }
    }
  },
  plugins: []
};
