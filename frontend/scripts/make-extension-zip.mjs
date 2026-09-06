#!/usr/bin/env node
/**
 * 把 Chrome 扩展（front/extension）打成 zip，供 Tauri 桌面端随安装包分发。
 *
 * 职责链路：
 *   front/extension/*（manifest.json / background.js / content.js / popup.html / popup.js）
 *     → front/src-tauri/extension-pack/nexusai-extension.zip
 *
 * 分发目标：tauri.conf.json bundle.resources 已含 "extension-pack"，
 *   安装后扩展 zip 落在 <安装目录>/extension-pack/nexusai-extension.zip
 *   （与 extension 目录同级，供备份 / 手动安装 / 分享）。
 *
 * zip 内顶层直接是 5 个文件（不带 extension 外套目录），解压后即可
 * chrome://extensions →「加载已解压的扩展程序」选中该目录。
 *
 * 平台分流（node child_process）：
 *   win        : powershell Compress-Archive -Path '<ext>/*' -DestinationPath '<zip>' -Force
 *   darwin/linux: 先删旧 zip，再 cd <ext> && zip -r <abs zip path> .
 *
 * 幂等：可重复执行（覆盖旧 zip）。
 */
import { execSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// ESM 下定位本文件目录（不能用 CommonJS 的 __dirname）
const __dirname = dirname(fileURLToPath(import.meta.url))

const EXT = resolve(__dirname, '../extension') // front/extension
const ZIP_DIR = resolve(__dirname, '../src-tauri/extension-pack') // front/src-tauri/extension-pack
const ZIP_PATH = join(ZIP_DIR, 'nexusai-extension.zip')

/** Windows 命令行用反斜杠路径（node resolve 出来是正斜杠；powershell 对反斜杠最稳） */
function winPath(p) {
  return p.replace(/\//g, '\\')
}

function main() {
  // 1) 扩展目录自检（缺 popup.html 等会使 Chrome default_popup 报错，提前暴露）
  if (!existsSync(EXT)) {
    console.error(`[make-extension-zip] 扩展目录不存在：${EXT}`)
    process.exit(1)
  }
  const files = readdirSync(EXT).filter((n) => n !== '.DS_Store')
  console.log(`[make-extension-zip] [1/2] 待打包文件（${files.length} 个）：${files.join(', ')}`)
  const required = ['manifest.json', 'background.js', 'content.js', 'popup.html', 'popup.js']
  const missing = required.filter((n) => !files.includes(n))
  if (missing.length > 0) {
    console.error(`[make-extension-zip] 扩展缺少必需文件（Chrome 会加载失败）：${missing.join(', ')}`)
    process.exit(1)
  }

  // 2) 目标目录就绪（tauri bundle.resources 校验 extension-pack 路径需常驻）
  mkdirSync(ZIP_DIR, { recursive: true })
  console.log(`[make-extension-zip] [2/2] 生成 zip → ${ZIP_PATH}`)

  try {
    if (process.platform === 'win32') {
      // Compress-Archive -Path '<ext>/*'：children 以 basename 进 zip 根，无外套目录
      const cmd =
        `powershell -NoProfile -Command "Compress-Archive -Path '${winPath(EXT)}\\*' -DestinationPath '${winPath(ZIP_PATH)}' -Force"`
      execSync(cmd, { stdio: 'inherit' })
    } else {
      // darwin / linux：清旧 zip 后 zip -r（entry 为解压后根下文件）
      if (existsSync(ZIP_PATH)) rmSync(ZIP_PATH, { force: true })
      execSync(`cd "${EXT}" && zip -r "${ZIP_PATH}" .`, { stdio: 'inherit' })
    }
  } catch (e) {
    console.error(`[make-extension-zip] zip 打包失败：${e && e.message ? e.message : e}`)
    process.exit(1)
  }

  if (!existsSync(ZIP_PATH)) {
    console.error(`[make-extension-zip] zip 生成失败：未找到 ${ZIP_PATH}`)
    process.exit(1)
  }
  console.log(`[make-extension-zip] 完成 → ${ZIP_PATH}（zip 内顶层为 ${files.length} 个文件，含 manifest.json）`)
}

main()
