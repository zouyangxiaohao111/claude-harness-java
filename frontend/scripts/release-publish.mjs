#!/usr/bin/env node
/**
 * 发版「双源」上传：把已打好的 Tauri 安装包 + latest.json 同时推到
 *   ① MinIO（`{endpoint}/{bucket}/{prefix}/`，自更新源）
 *   ② GitHub Release（claude-harness-java 仓的 `v{version}`）
 *
 * 凭据来源（⛔ 不进 git）：
 *   - 优先读同目录 `.minio-credentials.json`（已在 front/.gitignore 中忽略）
 *   - 缺失时回落到环境变量 MINIO_API / MINIO_BUCKET / MINIO_PREFIX（需 mc 已登录别名）
 *
 * 用法（在 front/ 下执行）：
 *   node scripts/release-publish.mjs [--exe <path>] [--skip-minio] [--skip-gh] [--scene]
 *
 * `--scene`：场景版发布。强制前缀 `updater-scene` 且**跳过 GitHub**（场景产物只上 MinIO），
 *            与通用版的 `updater/latest.json` 完全隔离。
 *
 * 前置：先跑过 `npm run tauri build`，产物在
 *   front/src-tauri/target/release/bundle/nsis/NexusAI_{version}_x64-setup.exe
 * 本脚本会自动把它复制到 <repo>/release/ 并调 release-update.mjs 刷新 latest.json。
 *
 * 依赖：mc（MinIO 客户端，路径见凭据文件的 mcPath）、gh（已登录，需对目标仓有 release 权限）
 */
import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const FRONT = resolve(__dirname, '..')
const ROOT = resolve(FRONT, '..')

const GH_REPO = 'zouyangxiaohao111/claude-harness-java'

const args = process.argv.slice(2)
const argVal = (name) => {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : undefined
}
const skipMinio = args.includes('--skip-minio')
const scene = args.includes('--scene')
// 场景产物只上 MinIO（铁律）：--scene 一律跳过 GitHub
const skipGh = args.includes('--skip-gh') || scene

const version = JSON.parse(readFileSync(join(FRONT, 'package.json'), 'utf-8')).version
const fileName = `NexusAI_${version}_x64-setup.exe`

// ── 凭据 ──────────────────────────────────────────────────────────────
const credFile = join(__dirname, '.minio-credentials.json')
const cred = existsSync(credFile) ? JSON.parse(readFileSync(credFile, 'utf-8')) : {}
const mcBin = cred.mcPath || 'mc'
const alias = cred.alias || 'nexusai'
const endpoint = process.env.MINIO_API || cred.endpoint
const bucket = process.env.MINIO_BUCKET || cred.bucket || 'nexusai'
const prefix = (scene ? 'updater-scene'
  : (process.env.MINIO_PREFIX || cred.prefix || 'updater')).replace(/^\/|\/$/g, '')
if (scene) {
  console.log(`[publish] scene 模式：prefix=${prefix}，跳过 GitHub（场景产物只上 MinIO）`)
}

// ── 1) 定位安装包（找不到就尝试从 bundle 目录复制）────────────────────
const releaseDir = resolve(ROOT, 'release')
mkdirSync(releaseDir, { recursive: true })
let exe = argVal('--exe') ? resolve(argVal('--exe')) : join(releaseDir, fileName)
if (!existsSync(exe)) {
  const built = join(FRONT, 'src-tauri/target/release/bundle/nsis', fileName)
  if (existsSync(built)) {
    copyFileSync(built, exe)
    console.log(`[publish] 已从 bundle 复制 → ${exe}`)
  } else {
    console.error(`[publish] ⛔ 未找到安装包：${exe}\n  也没有 ${built}\n  请先跑：npm run tauri build`)
    process.exit(1)
  }
}

// ── 2) 刷新 latest.json（复用 release-update.mjs，带上 MinIO 地址）────
const env = { ...process.env }
if (endpoint) { env.MINIO_API = endpoint; env.MINIO_BUCKET = bucket; env.MINIO_PREFIX = prefix }
// ⚠️ release-update.mjs 在「mc 上传失败」时会以 EXIT=2 退出（它的 mc 用的是裸 URL，mc 不认）——
//   那是**预期内**的：本脚本第 3 步会用【别名路径】重新上传。故此处必须容忍其非 0 退出，
//   ⛔ 否则 wrapper 会在生成 latest.json 之后就挂掉，永远走不到真正的上传。
try {
  execFileSync(process.execPath, [join(__dirname, 'release-update.mjs')], { stdio: 'inherit', env })
} catch (e) {
  if (!existsSync(join(releaseDir, 'latest.json'))) {
    console.error('[publish] ⛔ release-update.mjs 失败且未产出 latest.json，中止:', e.message)
    process.exit(1)
  }
  console.log('[publish] ℹ️ release-update.mjs 非 0 退出（预期内：其内置 mc 走裸 URL 必失败），latest.json 已生成 ⇒ 继续')
}
const outFile = join(releaseDir, 'latest.json')

// ── 3) 推 MinIO ───────────────────────────────────────────────────────
if (!skipMinio) {
  if (!endpoint) {
    console.error('[publish] ⏭ 跳过 MinIO：既无 .minio-credentials.json 的 endpoint，也无 MINIO_API')
  } else {
    // ⚠️ mc cp 的目标必须是【别名路径】（mc 不认裸 http(s) URL）
    const dest = `${alias}/${bucket}/${prefix}/`
    try {
      if (cred.accessKey && cred.secretKey) {
        execFileSync(mcBin, ['alias', 'set', alias, endpoint, cred.accessKey, cred.secretKey], { stdio: 'inherit' })
      }
      execFileSync(mcBin, ['cp', '--quiet', exe, dest], { stdio: 'inherit' })
      execFileSync(mcBin, ['cp', '--quiet', outFile, dest], { stdio: 'inherit' })
      console.log(`[publish] ✅ MinIO 已上传 → ${endpoint}/${bucket}/${prefix}/{${fileName},latest.json}`)
    } catch (e) {
      console.error('[publish] ⛔ MinIO 上传失败:', e.message)
      process.exitCode = 1
    }
  }
}

// ── 4) 推 GitHub Release（claude-harness-java）────────────────────────
if (!skipGh) {
  const tag = `v${version}`
  try {
    execFileSync('gh', ['release', 'view', tag, '--repo', GH_REPO], { stdio: 'ignore' })
    console.log(`[publish] release ${tag} 已存在，直接覆盖附件`)
  } catch {
    execFileSync('gh', ['release', 'create', tag, '--repo', GH_REPO,
      '--title', tag, '--notes', `见 nexusai CHANGELOG ${version}`], { stdio: 'inherit' })
  }
  execFileSync('gh', ['release', 'upload', tag, exe, outFile, '--repo', GH_REPO, '--clobber'], { stdio: 'inherit' })
  console.log(`[publish] ✅ GitHub Release 已上传 → https://github.com/${GH_REPO}/releases/tag/${tag}`)
}

console.log(`[publish] 完成：version=${version}`)
