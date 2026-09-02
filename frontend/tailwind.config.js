/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        canvas: 'var(--canvas)',
        surface: {
          1: 'var(--surface-1)',
          2: 'var(--surface-2)',
          3: 'var(--surface-3)',
          selected: 'var(--surface-selected)',
        },
        hairline: 'var(--hairline)',
        'hairline-strong': 'var(--hairline-strong)',
        ink: {
          DEFAULT: 'var(--ink)',
          strong: 'var(--ink-strong)',
          muted: 'var(--ink-muted)',
          subtle: 'var(--ink-subtle)',
          tertiary: 'var(--ink-tertiary)',
          faint: 'var(--ink-faint)',
        },
        accent: {
          DEFAULT: 'var(--accent)',
          hover: 'var(--accent-hover)',
          soft: 'var(--accent-soft)',
          'soft-strong': 'var(--accent-soft-strong)',
        },
        reasoning: {
          bg: 'var(--reasoning-bg)',
          border: 'var(--reasoning-border)',
          ink: 'var(--reasoning-ink)',
        },
        success: {
          DEFAULT: 'var(--success)',
          soft: 'var(--success-soft)',
          dark: 'var(--success-dark)',
        },
        running: 'var(--running)',
        warning: 'var(--warning)',
        error: {
          DEFAULT: 'var(--error)',
          soft: 'var(--error-soft)',
          dark: 'var(--error-dark)',
        },
        diff: {
          'add-bg': 'var(--diff-add-bg)',
          'add-fg': 'var(--diff-add-fg)',
          'del-bg': 'var(--diff-del-bg)',
          'del-fg': 'var(--diff-del-fg)',
          ctx: 'var(--diff-ctx)',
        },
        model: {
          deepseek: 'var(--model-deepseek)',
          claude: 'var(--model-claude)',
          gpt: 'var(--model-gpt)',
          qwen: 'var(--model-qwen)',
        },
      },
      borderRadius: {
        xs: 'var(--r-xs)',
        sm: 'var(--r-sm)',
        md: 'var(--r-md)',
        lg: 'var(--r-lg)',
        xl: 'var(--r-xl)',
        pill: 'var(--r-pill)',
      },
      fontFamily: {
        sans: 'var(--font-sans)',
        mono: 'var(--font-mono)',
        serif: 'var(--font-serif)',
      },
      animation: {
        spring: 'spring 360ms cubic-bezier(0.34, 1.56, 0.64, 1)',
        pulse: 'pulse 1.5s cubic-bezier(0.16, 1, 0.3, 1) infinite',
        blink: 'blink 1s steps(2) infinite',
      },
      keyframes: {
        pulse: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.4' },
        },
        blink: {
          '50%': { opacity: '0' },
        },
        spring: {
          from: {
            opacity: '0',
            transform: 'scale(0.96) translateY(8px)',
          },
          to: {
            opacity: '1',
            transform: 'scale(1) translateY(0)',
          },
        },
      },
    },
  },
  plugins: [],
}
