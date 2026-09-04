#!/usr/bin/env node
/**
 * 一键加载 NexusAI in Chrome 扩展到 Chrome（用用户默认登录配置）。
 *
 * 用法：
 *   npm run extension:load        # 用默认 profile 启动 Chrome 并加载扩展
 *
 * 关键点：
 *   - **不用独立 --user-data-dir**：直接用用户默认 Chrome 配置，保留已登录网站
 *     的 Cookie/登录态（浏览器自动化要能访问用户已登录的页面）。
 *   - `--load-extension` 只在 Chrome **冷启动**时生效。若 Chrome 已在运行，
 *     本脚本检测到进程后会提示先关闭 Chrome 再运行（或手动加载）。
 *
 * 日常 Chrome 的登录态完全保留；扩展加载后 popup 在工具栏固定。
 */
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import os from 'node:os'
import { execSync } from 'node:child_process'

const EXTENSION_DIR = resolve(__dirname, '..', 'extension')

/** Chrome 可执行文件候选路径（按优先级） */
const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  joinWin(process.env.LOCALAPPDATA, 'Google', 'Chrome', 'Application', 'chrome.exe'),
  joinWin(process.env.PROGRAMFILES, 'Google', 'Chrome', 'Application', 'chrome.exe'),
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].filter(Boolean)

function joinWin(...parts) {
  return parts.filter(Boolean).join('\\')
}

/** 检测 Chrome 是否已在运行（Windows 用 tasklist，Unix 用 pgrep） */
function isChromeRunning() {
  try {
    if (process.platform === 'win32') {
      const out = execSync('tasklist /FI "IMAGENAME eq chrome.exe"', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
      return out.includes('chrome.exe')
    }
    const out = execSync('pgrep -x chrome || pgrep -x chromium || pgrep -f "Google Chrome"', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    return out.trim().length > 0
  } catch {
    return false // pgrep 无匹配会非零退出 → 视为未运行
  }
}

function main() {
  if (!existsSync(EXTENSION_DIR)) {
    console.error(`[chrome:load] 扩展目录不存在: ${EXTENSION_DIR}`)
    console.error('请确认 extension/ 目录存在（含 manifest.json）')
    process.exit(1)
  }

  const chrome = CHROME_CANDIDATES.find((p) => p && existsSync(p))
  if (!chrome) {
    console.error('[chrome:load] 未找到 Chrome。请设置环境变量 CHROME_PATH 指向 chrome.exe')
    console.error('示例：CHROME_PATH="C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe" npm run extension:load')
    process.exit(1)
  }

  if (isChromeRunning()) {
    console.warn('[chrome:load] ⚠️ 检测到 Chrome 已在运行。')
    console.warn('   --load-extension 只在 Chrome 冷启动时生效。')
    console.warn('   请先关闭所有 Chrome 窗口再运行本脚本，或改用开发者模式手动加载：')
    console.warn('   chrome://extensions → 开发者模式 → 加载已解压 → 选 extension/ 目录')
    console.warn('')
    console.warn('   继续将启动一个新的 Chrome 进程（默认 profile 保留登录态），扩展可能不加载。')
  }

  console.log(`[chrome:load] Chrome: ${chrome}`)
  console.log(`[chrome:load] 扩展:   ${EXTENSION_DIR}`)
  console.log('[chrome:load] 用默认登录配置启动（保留已登录网站的 Cookie/登录态）…')

  const args = [
    `--load-extension=${EXTENSION_DIR}`,
    '--new-window',
    'chrome://extensions',
  ]
  const child = spawn(chrome, args, { stdio: 'ignore', detached: true, windowsHide: false })
  child.unref() // 脱离父进程，脚本立即返回

  console.log('[chrome:load] 已启动。步骤：')
  console.log('  1. chrome://extensions 页面确认 "NexusAI in Chrome" 已加载')
  console.log('  2. 若提示错误，点 "重新加载" 按钮')
  console.log('  3. 打开扩展 popup 点 "连接" 一次（全局连接，无需 sessionId）')
  console.log('  4. 扩展会用你的默认登录配置访问页面（已登录网站无需重新登录）')
}

main()
