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
          accent: 'rgb(var(--brand-accent) / <alpha-value>)',
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
      // Three elevations, named for the job rather than a t-shirt size, because
      // the old set had five named tiers plus five one-off bracket values and
      // `shadow-xl` and `shadow-2xl` were used about equally -- so modals did
      // not share a height. e1 rests, e2 floats over the page, e3 sits over a
      // backdrop. Dark mode leans on surface lightness and borders instead;
      // a shadow on a near-black canvas is not visible either way.
      // Three durations and two curves, so motion is a decision rather than
      // whatever Tailwind's 150ms default happened to be. `standard` is the
      // ease everything entering or settling uses; `exit` is faster out than
      // in, which is how dismissal should feel.
      // DEFAULT is set too, so a bare `transition` -- which is most of them --
      // lands on the scale instead of Tailwind's unchosen 150ms and its own
      // easing. 120ms suits the hover feedback that bare `transition` is
      // nearly always used for; anything that moves asks for base or slow.
      transitionDuration: { DEFAULT: '120ms', fast: '120ms', base: '180ms', slow: '240ms' },
      transitionTimingFunction: {
        DEFAULT: 'cubic-bezier(0.2, 0, 0, 1)',
        standard: 'cubic-bezier(0.2, 0, 0, 1)',
        exit: 'cubic-bezier(0.4, 0, 1, 1)'
      },
      keyframes: {
        'overlay-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'panel-in': {
          from: { opacity: '0', transform: 'translateY(0.5rem) scale(0.985)' },
          to: { opacity: '1', transform: 'none' }
        }
      },
      animation: {
        'overlay-in': 'overlay-in 120ms cubic-bezier(0.2, 0, 0, 1)',
        'panel-in': 'panel-in 180ms cubic-bezier(0.2, 0, 0, 1)'
      },
      boxShadow: {
        e1: '0 1px 2px rgb(15 23 42 / 0.05)',
        e2: '0 4px 14px -3px rgb(15 23 42 / 0.12)',
        e3: '0 18px 44px -10px rgb(15 23 42 / 0.28)'
      },
      // Three radii. There were ten, including a `rounded-[10px]` that only the
      // three core control classes could reach, so a button had a corner no
      // other element could match.
      borderRadius: {
        sm: '0.375rem',   //  6px -- chips, small controls
        DEFAULT: '0.375rem',
        md: '0.625rem',   // 10px -- buttons, inputs, rows
        lg: '0.875rem'    // 14px -- cards, panels, dialogs
      },
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
