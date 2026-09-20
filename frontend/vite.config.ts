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
    // 后端单一来源：dev 下前端走相对路径（src/api/base.ts 的 API_BASE / API_V1_BASE / WS_BASE /
    //   SOCKJS_BASE），由本 proxy 转发到本机后端 3458。⛔ ws/sockjs 两条必须 ws:true（升级转发），
    //   否则 STOMP 连不上 → socket.ts 的原生 WS 会一直失败并降级 SockJS，SockJS 也失败则整条会话流断。
    //   打包版不走本 proxy（WebView 用绝对地址，见 base.ts 分环境说明）。
    proxy: {
      '/api': { target: 'http://localhost:3458', changeOrigin: true },
      '/ws': { target: 'http://localhost:3458', changeOrigin: true, ws: true },
      '/ws-sockjs': { target: 'http://localhost:3458', changeOrigin: true, ws: true },
    },
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
