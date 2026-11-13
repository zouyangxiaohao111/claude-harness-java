#!/usr/bin/env node
/**
 * 生成自动更新清单 latest.json + 可选上传（MinIO / GitHub）。
 *
 * 读 front/package.json 的 version、front/CHANGELOG.md 当前版本段的“发版说明”，
 * 对**本版本各平台**产物计算 sha256，输出 Tauri 自定义清单：
 *   { version, notes, published, platforms: { "windows-x86_64": { url, sha256 }, ... } }
 *
 * ⛔ platforms 必须按**产物实际平台**逐条生成（键名契约见 Rust 侧 platform_key_for）：
 *    之前这里只写死 "windows-x86_64" 一个键，客户端又硬编码取它 ⇒ mac 客户端
 *    拉到 Windows 安装包（2026-11-13 用户实测）。
 *
 * 用法（在 front/ 下执行，或传 front 根）：
 *   node scripts/release-update.mjs [--exe <path>] [--out <dir>]
 * 上传（可选，通过 env）：
 *   MINIO_API=https://your-update-host.example  MINIO_BUCKET=nexusai  MINIO_PREFIX=updater
 *     （配合已登录的 mc：脚本执行 `mc cp --quiet` 到 ${MINIO_API}/${BUCKET}/${PREFIX}/）
 *   GH_UPLOAD=1 （配合 gh 已登录：上传到当前 repo 的 v${version} release）
 * 不传任何上传 env → 仅本地生成到 --out（默认 ../release）。
 */
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const FRONT = resolve(__dirname, '..')
const ROOT = resolve(FRONT, '..')

/**
 * 平台键契约 —— 必须与 Rust 侧 `platform_key_for` 完全一致（两侧唯一真相）。
 * 键名沿用 Tauri v2 的 `{target}-{arch}` 风格；macOS 的 target 叫 `darwin`。
 */
export const PLATFORM_WINDOWS = 'windows-x86_64'

/**
 * 按文件名反推平台键；无法识别返回 null。
 * @param {string} name 文件名（不含目录）
 * @returns {string|null} 平台键
 */
export function platformKeyOf(name) {
  if (/_x64-setup\.exe$/i.test(name)) return PLATFORM_WINDOWS
  if (/_x64_en-US\.msi$/i.test(name)) return PLATFORM_WINDOWS
  if (/_aarch64\.dmg$/i.test(name)) return 'darwin-aarch64'
  if (/_x64\.dmg$/i.test(name)) return 'darwin-x86_64'
  if (/_aarch64\.app\.tar\.gz$/i.test(name)) return 'darwin-aarch64'
  if (/_x64\.app\.tar\.gz$/i.test(name)) return 'darwin-x86_64'
  return null
}

/**
 * 从文件列表挑出**本版本**的多平台产物 → { 平台键: 文件名 }。
 * @param {string[]} fileNames 候选文件名（不含目录）
 * @param {string} version 本版本号（用于过滤旧版本残留）
 * @returns {Record<string,string>}
 */
export function detectPlatforms(fileNames, version) {
  /** @type {Record<string,string>} */
  const out = {}
  for (const name of fileNames) {
    if (!name.includes(`_${version}_`)) continue
    const key = platformKeyOf(name)
    if (key && !out[key]) out[key] = name
  }
  return out
}

/**
 * 收集候选产物：`--exe` 指定文件（若给了）；否则扫描 <ROOT>/release/ 下所有文件。
 * ⛔ 版本过滤不在这里做 —— 由 detectPlatforms 统一按版本号过滤，
 *    免得「旧版本残留」混进清单（release/ 是长期目录，残留必然存在）。
 * @param {string} releaseDir <ROOT>/release
 * @param {string} version 本版本号
 * @param {string|undefined} exeArg --exe 参数
 * @returns {{name:string,path:string}[]}
 */
function collectArtifacts(releaseDir, version, exeArg) {
  /** @type {{name:string,path:string}[]} */
  const out = []
  if (exeArg) {
    const p = resolve(exeArg)
    if (!existsSync(p)) {
      console.error(`[release-update] 未找到安装包: ${p}（用 --exe 指定）`)
      process.exit(1)
    }
    out.push({ name: basename(p), path: p })
    return out
  }
  if (!existsSync(releaseDir)) {
    console.error(`[release-update] 未找到产物目录: ${releaseDir}`)
    process.exit(1)
  }
  for (const name of readdirSync(releaseDir)) {
    const p = join(releaseDir, name)
    try {
      if (!statSync(p).isFile()) continue
    } catch {
      continue
    }
    out.push({ name, path: p })
  }
  // ⛔ 按文件名排序再交给 detectPlatforms：readdirSync 的顺序不保证稳定，
  //    而同一平台可能有多个文件（windows 的 nsis exe + msi、mac 的 .app.tar.gz + .dmg）。
  //    升序 ⇒ windows 优先选 nsis exe（Rust 侧静默安装只认它）、mac 优先选 .app.tar.gz
  //    （Tauri updater 的 mac 产物）⇒ 清单指向的永远是「客户端真能跑」的那一个。
  out.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
  return out
}

function sha256Of(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

function main() {
  const args = process.argv.slice(2)
  const exeArg = argVal(args, '--exe')
  const outArg = argVal(args, '--out')

  // 1) 版本
  const pkg = JSON.parse(readFileSync(join(FRONT, 'package.json'), 'utf-8'))
  const version = pkg.version

  // 2) notes：CHANGELOG 当前版本段（去掉 md 头），如无该段则回退首行描述
  const changelog = readFileSync(join(FRONT, 'CHANGELOG.md'), 'utf-8')
  const notes = extractNotes(changelog, version)

  // 3) 产物：按**实际平台**识别（只收本版本）
  const releaseDir = join(ROOT, 'release')
  const candidates = collectArtifacts(releaseDir, version, exeArg)
  const detected = detectPlatforms(
    candidates.map((c) => c.name),
    version,
  )
  const pathOf = new Map(candidates.map((c) => [c.name, c.path]))
  // 上传清单 = 本版本且平台可识别的**全部**产物（一个平台可能有 exe + msi 两个文件）
  const artifacts = candidates.filter(
    (c) => c.name.includes(`_${version}_`) && platformKeyOf(c.name),
  )
  // ⚠️ 本版本产物里**认不出平台**的必须出声，不能静默漏掉：
  //    将来 mac 产物若换了命名（如 `NexusAI_0.1.74.dmg` 无架构标记），
  //    静默漏掉 = 客户端永远收不到该平台更新，且**没有任何报错**。
  //    （与「清单只写 windows 一个键」是同款温床：缺的那一档不会自己喊疼。）
  const unrecognized = candidates
    .filter((c) => c.name.includes(`_${version}_`) && !platformKeyOf(c.name))
    .map((c) => c.name)
  if (unrecognized.length > 0) {
    console.warn(
      `[release-update] ⚠️ 下列本版本产物**认不出平台**，不会进清单也不会被上传：${unrecognized.join(', ')}\n` +
        `  若其中含某平台的安装包，请补 platformKeyOf() 的识别规则（键名契约见 Rust 侧 platform_key_for）。`,
    )
  }

  // ⛔ 门禁：至少要有一个**已识别平台**的本版本产物（原口径是"必须有 exe"，
  //    口径变了但"没有产物就中止"的行为保留 —— 否则会生成一份没有下载入口的清单）。
  if (Object.keys(detected).length === 0) {
    console.error(`[release-update] 未找到本版本（${version}）的已识别平台产物`)
    console.error(`  扫描目录: ${releaseDir}`)
    console.error(`  候选文件: ${candidates.map((c) => c.name).join(', ') || '(空)'}`)
    process.exit(1)
  }

  // 4) 清单：按平台逐条生成
  const bucket = process.env.MINIO_BUCKET || 'nexusai'
  const prefix = (process.env.MINIO_PREFIX || 'updater').replace(/^\/|\/$/g, '')
  const minioApi = process.env.MINIO_API || 'https://your-update-host.example'
  /** @type {Record<string,{url:string,sha256:string}>} */
  const platforms = {}
  for (const [key, file] of Object.entries(detected)) {
    // ⛔ UPDATE_URL_OVERRIDE 只作用于 Windows（保持既有语义：仅上 GitHub 时让
    //    清单指向 GitHub Release 资产）。其它平台用 MinIO URL。
    const override = key === PLATFORM_WINDOWS ? process.env.UPDATE_URL_OVERRIDE : undefined
    platforms[key] = {
      url: override || `${minioApi}/${bucket}/${prefix}/${file}`,
      sha256: sha256Of(pathOf.get(file)),
    }
  }
  const manifest = {
    version,
    notes,
    published: new Date().toISOString(),
    platforms,
  }

  // 5) 落盘
  const out = outArg ? resolve(outArg) : join(ROOT, 'release')
  mkdirSync(out, { recursive: true })
  const outFile = join(out, 'latest.json')
  writeFileSync(outFile, JSON.stringify(manifest, null, 2) + '\n')
  console.log(`[release-update] version=${version} 平台=${Object.keys(platforms).join(',')}`)
  for (const [key, p] of Object.entries(platforms)) {
    console.log(`[release-update]   ${key}: ${p.url} sha256=${p.sha256.slice(0, 12)}…`)
  }
  console.log(`[release-update] 清单写入: ${outFile}`)

  // 6) 可选上传
  if (process.env.MINIO_API) {
    const dir = `${process.env.MINIO_API}/${bucket}/${prefix}`
    try {
      // ⛔ 每一个识别到的本版本产物都要传（原来只传一个 exe）
      for (const a of artifacts) {
        execFileSync('mc', ['cp', '--quiet', a.path, `${dir}/${a.name}`], { stdio: 'inherit' })
      }
      execFileSync('mc', ['cp', '--quiet', outFile, `${dir}/latest.json`], { stdio: 'inherit' })
      console.log(
        `[release-update] MinIO 已上传 → ${dir}/{${artifacts.map((a) => a.name).join(', ')},latest.json}`,
      )
    } catch (e) {
      console.error('[release-update] MinIO 上传失败（需 mc 已登录，或稍后手动上传）:', e.message)
      process.exitCode = 2
    }
  }
  if (process.env.GH_UPLOAD === '1') {
    const repo = execFileSync('gh', ['repo', 'view', '--json', 'nameWithOwner', '-q', '.nameWithOwner'], { encoding: 'utf8' }).trim()
    try {
      execFileSync(
        'gh',
        ['release', 'upload', `v${version}`, ...artifacts.map((a) => a.path), outFile, '--repo', repo, '--clobber'],
        { stdio: 'inherit' },
      )
      console.log(`[release-update] GitHub release v${version} 已更新（${artifacts.length} 个产物 + latest.json）`)
    } catch (e) {
      console.error('[release-update] GitHub 上传失败:', e.message)
      process.exitCode = 2
    }
  }
}

function argVal(args, name) {
  const i = args.indexOf(name)
  return i >= 0 ? args[i + 1] : undefined
}

function extractNotes(md, version) {
  const m = md.match(new RegExp(`## \\[${version.replace(/\./g, '\\.')}\\]\\s*-?[^\\n]*\\n([\\s\\S]*?)(?=\\n## |$)`))
  if (!m) return ''
  return m[1]
    .replace(/\r/g, '')
    .replace(/^###[^\n]*\n/gm, '')
    .replace(/^\s*-\s+/gm, '• ')
    .replace(/\*\*/g, '')
    .split('\n').map((s) => s.trim()).filter(Boolean).join('\n')
    .slice(0, 2000)
}

// ⛔ 仅当本文件被直接执行时才跑（被 import 时只导出纯函数供 vitest 测试）。
//    本文件是 ESM ⇒ 不可用 require.main，只能比 argv[1] 与自身路径。
//    release-publish.mjs 用 execFileSync(process.execPath, [本脚本]) 调用 ⇒
//    argv[1] 就是本脚本路径 ⇒ isDirectRun 仍为 true ⇒ 脚本照常执行。
const isDirectRun =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)

if (isDirectRun) {
  main()
}
