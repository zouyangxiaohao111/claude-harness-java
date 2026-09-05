import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import path from 'path'

const host = process.env.TAURI_DEV_HOST

export default defineConfig({
  plugins: [react()],
  // sockjs-client 等 Node 风格包引用 `global`，浏览器无该全局 → polyfill 为 globalThis
  define: {
    global: 'globalThis',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  clearScreen: false,
  server: {
    port: 3000,
    strictPort: true,
    host: host || false,
    hmr: host
      ? {
          protocol: 'ws',
          host,
          port: 1421,
        }
      : undefined,
    watch: {
      ignored: ['**/src-tauri/**'],
    },
  },
  envPrefix: ['VITE_', 'TAURI_'],
  build: {
    // P7: Vite 8 + esbuild 0.24 在 safari14 target 上不支持解构变换，临时改 esnext
    target: process.env.TAURI_PLATFORM === 'windows' ? 'chrome105' : 'esnext',
    minify: !process.env.TAURI_DEBUG ? 'esbuild' : false,
    sourcemap: !!process.env.TAURI_DEBUG,
    outDir: 'dist',
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
})
