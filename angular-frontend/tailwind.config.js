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
          50: 'rgb(var(--brand-50) / <alpha-value>)',
          600: 'rgb(var(--brand-600) / <alpha-value>)',
          700: 'rgb(var(--brand-700) / <alpha-value>)'
        },
        // Destructive actions only. Separate from `brand` so a rebrand cannot
        // recolour the warning system -- see the note in styles.css.
        danger: {
          DEFAULT: 'rgb(var(--danger-600) / <alpha-value>)',
          600: 'rgb(var(--danger-600) / <alpha-value>)',
          700: 'rgb(var(--danger-700) / <alpha-value>)'
        }
      },
      // Six steps, and the line heights are set for Georgian rather than
      // inherited from Tailwind's Latin defaults -- the script carries more
      // above and below the x-height, so it needs the extra leading. Before
      // this the app used eight named steps plus twenty one-off bracket
      // values; `design-rules.spec.ts` now allows only what is listed here.
      fontSize: {
        xs: ['0.75rem', { lineHeight: '1.125rem' }],    // 12 / 18  -- the floor
        sm: ['0.875rem', { lineHeight: '1.3125rem' }],  // 14 / 21  -- UI default
        base: ['1rem', { lineHeight: '1.5625rem' }],    // 16 / 25  -- body copy
        lg: ['1.25rem', { lineHeight: '1.75rem' }],     // 20 / 28  -- block title
        xl: ['1.625rem', { lineHeight: '2.125rem' }],   // 26 / 34  -- page title
        '2xl': ['2.125rem', { lineHeight: '2.625rem' }] // 34 / 42  -- hero title
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
