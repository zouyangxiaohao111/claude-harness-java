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
 *   node scripts/release-publish.mjs [--exe <path>] [--skip-minio] [--skip-gh] [--scene] [--internal]
 *
 * `--scene`：场景版发布。强制前缀 `updater-scene` 且**跳过 GitHub**（场景产物只上 MinIO），
 *            与通用版的 `updater/latest.json` 完全隔离。
 *
 * `--internal`：内网版发布。允许安装包带出厂默认秘钥（来源 = gitignored 的
 *            `backend/src/main/resources/factory/factory-preset.local.json`），但**强制跳过 GitHub**
 *            —— 只上 MinIO。不带该参数且检测到秘钥文件时直接中止（见下方「秘钥门禁」）。
 *
 * 前置：先跑过 `npm run tauri build`，产物在
 *   front/src-tauri/target/release/bundle/nsis/NexusAI_{version}_x64-setup.exe
 * 本脚本会自动把它复制到 <repo>/release/ 并调 release-update.mjs 刷新 latest.json。
 *
 * 多平台（2026-11-13 实测背景：清单只写 windows 一个键 ⇒ mac 客户端拿到 Windows 安装包）：
 *   macOS 产物（bundle/dmg、bundle/macos 下**本版本**的 .dmg / .app.tar.gz）会一并
 *   复制到 <repo>/release/，由 release-update.mjs 按文件名反推平台键生成多平台条目，
 *   两源上传都带上全部本版本平台产物。平台键契约与 Rust 侧 platform_key_for 一致。
 *
 * 依赖：mc（MinIO 客户端，路径见凭据文件的 mcPath）、gh（已登录，需对目标仓有 release 权限）
 */
import { execFileSync } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, basename, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
// 平台识别函数与 Rust 侧 platform_key_for 共用同一套键名契约（2026-11-13 实测：
// 清单只写 windows 一个键 + 客户端写死取它 ⇒ mac 客户端拿到 Windows 安装包）。
// ⚠️ release-update.mjs 有顶层副作用隔离（isDirectRun），import 它**不会**触发脚本主体。
import { platformKeyOf } from './release-update.mjs'

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
const internal = args.includes('--internal')
// 场景/内网产物只上 MinIO（铁律）：--scene / --internal 一律跳过 GitHub
const skipGh = args.includes('--skip-gh') || scene || internal

// ── 秘钥门禁 ──────────────────────────────────────────────────────────
// 内网安装包的出厂默认秘钥来自 gitignored 的 factory-preset.local.json（源 + 编译产物各查一处）。
// GitHub 版本必须没有它 ⇒ 检测到就拒绝推 GitHub，避免秘钥随发布外流。
const seedKeyFiles = [
  join(ROOT, 'backend/src/main/resources/factory/factory-preset.local.json'),
  join(ROOT, 'backend/target/classes/factory/factory-preset.local.json'),
].filter(existsSync)

// 已打包的后端 jar 里也可能残留旧的内网秘钥资源（源文件删了、jar 没重打）——
// 用 unzip -l / jar tf 查条目名，查不到就告警不阻断（工具缺失时无法判断）。
function jarContainsSeedKey(jarPath) {
  for (const [cmd, args] of [['unzip', ['-l', jarPath]], ['jar', ['tf', jarPath]]]) {
    try {
      const out = execFileSync(cmd, args, { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'ignore'] })
      return out.split(/\r?\n/).some((line) => line.includes('factory/factory-preset.local.json'))
    } catch { /* 尝试下一种工具 */ }
  }
  return null
}
const bundledJarDir = join(FRONT, 'src-tauri/backend')
const seedKeyJars = []
if (existsSync(bundledJarDir)) {
  for (const name of readdirSync(bundledJarDir).filter((n) => n.endsWith('.jar'))) {
    const p = join(bundledJarDir, name)
    const hit = jarContainsSeedKey(p)
    if (hit === true) seedKeyJars.push(p)
    else if (hit === null) console.warn(`[publish] ⚠️ 无法检查 jar 是否含默认秘钥（缺 unzip/jar 工具）: ${p}`)
  }
}

const seedKeyPresent = seedKeyFiles.length > 0 || seedKeyJars.length > 0
if (seedKeyPresent) {
  if (!skipGh) {
    console.error('[publish] ⛔ 检测到内网出厂秘钥（源文件或已打包 jar），拒绝推 GitHub：')
    for (const f of seedKeyFiles) console.error('  - ' + f)
    for (const f of seedKeyJars) console.error('  - ' + f + '（jar 内含 factory/factory-preset.local.json）')
    console.error('  内网版本请加 --internal（只上 MinIO）；GitHub 版本请删除该文件、清理')
    console.error('  backend/target/classes 与 front/src-tauri/backend 后重新打 jar，确保安装包内没有默认秘钥。')
    process.exit(1)
  }
  console.log(`[publish] ℹ️ 检测到内网出厂秘钥（源/编译产物 ${seedKeyFiles.length} 处、jar ${seedKeyJars.length} 个），本次跳过 GitHub（仅 MinIO）`)
}

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

// ── 1) 定位安装包（默认**每次**从本次 bundle 复制，绝不复用 release/ 里的旧口径产物）──
const releaseDir = resolve(ROOT, 'release')
mkdirSync(releaseDir, { recursive: true })
const explicitExe = argVal('--exe')
let exe
if (explicitExe) {
  exe = resolve(explicitExe)
  if (!existsSync(exe)) {
    console.error(`[publish] ⛔ --exe 指定的安装包不存在：${exe}`)
    process.exit(1)
  }
} else {
  const built = join(FRONT, 'src-tauri/target/release/bundle/nsis', fileName)
  if (!existsSync(built)) {
    console.error(`[publish] ⛔ 未找到安装包：${built}\n  请先跑：npm run tauri build`)
    process.exit(1)
  }
  exe = join(releaseDir, fileName)
  // ⛔ 必须每次都复制：release/ 可能残留上一个口径（内网带 key）的同名 exe。
  //    旧实现只在目标不存在时复制 ⇒ 曾把内网 keyed 包当成 GitHub 包上传（2026-10-26 事故）。
  copyFileSync(built, exe)
  console.log(`[publish] 已从 bundle 复制 → ${exe}`)
}

// ── 1a) 收集 macOS 产物（本版本 + 平台可识别）到 release/ ────────────
// 之前只搬 Windows 的 nsis exe ⇒ latest.json 里永远只有 windows 一个键，
// mac 客户端只能下到 Windows 安装包（2026-11-13 用户实测）。
// 现在把 mac bundle（dmg / macos）里**本版本**的产物一并搬过来，
// 由 release-update.mjs 按文件名反推平台、统一生成多平台条目。
const macBundleDirs = [
  join(FRONT, 'src-tauri/target/release/bundle/dmg'),
  join(FRONT, 'src-tauri/target/release/bundle/macos'),
]
for (const dir of macBundleDirs) {
  if (!existsSync(dir)) continue
  for (const name of readdirSync(dir)) {
    // ⛔ 必须按版本过滤：bundle 目录里会残留上一版本的 dmg / app.tar.gz。
    if (!name.includes(`_${version}_`)) continue
    const src = join(dir, name)
    try {
      // bundle/macos 下还有 *.app 目录（Tauri 的 macOS 应用包），不是可上传文件 ⇒ 只收文件。
      if (!statSync(src).isFile()) continue
    } catch {
      continue
    }
    // ⚠️ 认不出平台的产物必须出声：静默漏掉 ⇒ 客户端永远收不到该平台更新，
    //    而日志里看不出任何异常（与「清单只有 windows 一个键」同款温床）。
    if (!platformKeyOf(name)) {
      console.warn(
        `[publish] ⚠️ mac bundle 产物**认不出平台**，不会进清单也不会被上传：${name}\n` +
          `  若它是 mac 安装包，请补 platformKeyOf() 的识别规则（键名契约见 Rust 侧 platform_key_for）。`,
      )
      continue
    }
    const dest = join(releaseDir, name)
    copyFileSync(src, dest)
    console.log(`[publish] 已从 mac bundle 复制 → ${dest}`)
  }
}

// ── 1b) 启动错误上报只写 key 门禁 ─────────────────────────────────────
// 该 key 仅允许 PutObject 到 nexusai/error-reports/*，由内网构建通过
// NEXUSAI_ERROR_REPORT_CONFIG 编译期注入。GitHub 包必须没有它；内部包应当有。
const errorReportCfgFile = join(FRONT, 'src-tauri/error-report.local.json')
let errorReportSecret = ''
if (existsSync(errorReportCfgFile)) {
  try {
    errorReportSecret = JSON.parse(readFileSync(errorReportCfgFile, 'utf-8')).secretKey || ''
  } catch { /* 配置损坏时由运行时兜底为「未配置上报」 */ }
}
// NSIS 安装包对 app 做了压缩，直接在 installer 里搜 key 是**假阴性**；
// 必须扫未压缩的 target/release/nexusai-client.exe。
const appExe = join(FRONT, 'src-tauri/target/release/nexusai-client.exe')
const fileContains = (file, needle) => {
  if (!needle) return false
  try {
    return readFileSync(file).includes(Buffer.from(needle))
  } catch {
    return false
  }
}
if (errorReportSecret && !skipGh && fileContains(appExe, errorReportSecret)) {
  console.error('[publish] ⛔ GitHub 安装包内含错误上报只写 key（app exe 里检测到），拒绝推 GitHub。')
  process.exit(1)
}
if (errorReportSecret && internal && !fileContains(appExe, errorReportSecret)) {
  console.warn('[publish] ⚠️ 内部包未嵌入错误上报配置（构建前是否忘了创建 .error-report-internal / 设置 NEXUSAI_ERROR_REPORT_CONFIG？）')
}

// ── 2) 刷新 latest.json（复用 release-update.mjs，带上 MinIO 地址）────
const env = { ...process.env }
// --skip-minio 时不能把 MINIO_API 传给 release-update.mjs：它内部会自行 `mc cp`，
// 否则会把本次（可能是 keyless）产物覆盖到 MinIO 的内网 updater。
if (!skipMinio && endpoint) { env.MINIO_API = endpoint; env.MINIO_BUCKET = bucket; env.MINIO_PREFIX = prefix }
// 仅上 GitHub 时，让 latest.json 指向 GitHub Release 资产（与本次 keyless 包同源）。
if (skipMinio && !skipGh) {
  env.UPDATE_URL_OVERRIDE = `https://github.com/${GH_REPO}/releases/download/v${version}/${fileName}`
}
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

// ── 2b) 待上传产物清单（本版本 + 平台可识别的全部）───────────────────
// ⛔ 不许把旧版本残留也传上去（release/ 是长期目录，残留必然存在）。
//    一个平台可能有多个文件（如 windows 的 exe + msi）⇒ 全部带上。
const uploadArtifacts = []
for (const name of readdirSync(releaseDir)) {
  if (!name.includes(`_${version}_`)) continue
  if (!platformKeyOf(name)) continue
  const p = join(releaseDir, name)
  try {
    if (!statSync(p).isFile()) continue
  } catch {
    continue
  }
  uploadArtifacts.push({ name, path: p })
}
// --exe 指向 release/ 之外时上面扫不到 ⇒ 显式补上（它就是本次要发的那个包）。
if (!uploadArtifacts.some((a) => a.path === exe)) {
  uploadArtifacts.unshift({ name: basename(exe), path: exe })
}
console.log(`[publish] 待上传产物（version=${version}）：${uploadArtifacts.map((a) => a.name).join(', ') || '(空)'}`)

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
      // ⛔ 每一个本版本平台产物都要传（原来只传一个 exe；mac 产物会漏掉）
      for (const a of uploadArtifacts) {
        execFileSync(mcBin, ['cp', '--quiet', a.path, dest], { stdio: 'inherit' })
      }
      execFileSync(mcBin, ['cp', '--quiet', outFile, dest], { stdio: 'inherit' })
      console.log(`[publish] ✅ MinIO 已上传 → ${endpoint}/${bucket}/${prefix}/{${uploadArtifacts.map((a) => a.name).join(', ')},latest.json}`)
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
  // ⛔ 每一个本版本平台产物都要传（原来只传一个 exe；mac 产物会漏掉）
  execFileSync('gh', ['release', 'upload', tag, ...uploadArtifacts.map((a) => a.path), outFile, '--repo', GH_REPO, '--clobber'], { stdio: 'inherit' })
  console.log(`[publish] ✅ GitHub Release 已上传 → https://github.com/${GH_REPO}/releases/tag/${tag}`)
}

console.log(`[publish] 完成：version=${version}`)
