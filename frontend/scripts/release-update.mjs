#!/usr/bin/env node
/**
 * 生成自动更新清单 latest.json + 可选上传（MinIO / GitHub）。
 *
 * 读 front/package.json 的 version、front/CHANGELOG.md 当前版本段的“发版说明”，
 * 对产物安装包计算 sha256，输出 Tauri 自定义清单：
 *   { version, notes, published, platforms: { "windows-x86_64": { url, sha256 } } }
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
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const FRONT = resolve(__dirname, '..')
const ROOT = resolve(FRONT, '..')

const args = process.argv.slice(2)
const exeArg = argVal(args, '--exe')
const outArg = argVal(args, '--out')

// 1) 版本
const pkg = JSON.parse(readFileSync(join(FRONT, 'package.json'), 'utf-8'))
const version = pkg.version

// 2) notes：CHANGELOG 当前版本段（去掉 md 头），如无该段则回退首行描述
const changelog = readFileSync(join(FRONT, 'CHANGELOG.md'), 'utf-8')
const notes = extractNotes(changelog, version)

// 3) 安装包 + sha256
const exe = exeArg
  ? resolve(exeArg)
  : join(ROOT, 'release', `NexusAI_${version}_x64-setup.exe`)
if (!existsSync(exe)) {
  console.error(`[release-update] 未找到安装包: ${exe}（用 --exe 指定）`)
  process.exit(1)
}
const sha256 = createHash('sha256').update(readFileSync(exe)).digest('hex')

// 4) 清单：下载 URL 取自 MINIO_API（未设置则写占位符，发版时用真实地址覆盖）；GH 源在同一相对路径
const bucket = process.env.MINIO_BUCKET || 'nexusai'
const prefix = (process.env.MINIO_PREFIX || 'updater').replace(/^\/|\/$/g, '')
const minioApi = process.env.MINIO_API || 'https://your-update-host.example'
const fileName = `NexusAI_${version}_x64-setup.exe`
const manifest = {
  version,
  notes,
  published: new Date().toISOString(),
  platforms: {
    'windows-x86_64': {
      url: `${minioApi}/${bucket}/${prefix}/${fileName}`,
      sha256,
    },
  },
}

// 5) 落盘
const out = outArg ? resolve(outArg) : join(ROOT, 'release')
mkdirSync(out, { recursive: true })
const outFile = join(out, 'latest.json')
writeFileSync(outFile, JSON.stringify(manifest, null, 2) + '\n')
console.log(`[release-update] version=${version} sha256=${sha256.slice(0, 12)}…`)
console.log(`[release-update] 清单写入: ${outFile}`)

// 6) 可选上传
if (process.env.MINIO_API) {
  const dest = `${process.env.MINIO_API}/${bucket}/${prefix}/${fileName}`
  try {
    execFileSync('mc', ['cp', '--quiet', exe, dest], { stdio: 'inherit' })
    execFileSync('mc', ['cp', '--quiet', outFile, `${dest.replace(/\/[^/]+$/, '')}/latest.json`], { stdio: 'inherit' })
    console.log(`[release-update] MinIO 已上传 → ${dest} 与 latest.json`)
  } catch (e) {
    console.error('[release-update] MinIO 上传失败（需 mc 已登录，或稍后手动上传）:', e.message)
    process.exitCode = 2
  }
}
if (process.env.GH_UPLOAD === '1') {
  const repo = execFileSync('gh', ['repo', 'view', '--json', 'nameWithOwner', '-q', '.nameWithOwner'], { encoding: 'utf8' }).trim()
  try {
    execFileSync('gh', ['release', 'upload', `v${version}`, exe, outFile, '--repo', repo, '--clobber'], { stdio: 'inherit' })
    console.log(`[release-update] GitHub release v${version} 已更新（exe + latest.json）`)
  } catch (e) {
    console.error('[release-update] GitHub 上传失败:', e.message)
    process.exitCode = 2
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
