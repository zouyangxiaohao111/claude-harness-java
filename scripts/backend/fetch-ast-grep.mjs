#!/usr/bin/env node
/**
 * 拉取 ast-grep CLI 二进制 → gzip → 落进 classpath 资源目录（AstGrep/AstEdit 工具的数据来源）。
 *
 * 为什么要这个脚本：ast-grep 没有 Java 绑定（Maven 的 tree-sitter-* 里没有 ast-grep 核心），
 * 结构化搜索/改写靠随包的 ast-grep CLI 子进程完成（与 GrepTool 随包 rg 二进制同一取舍）。
 * 裸二进制单平台 55MB、六平台 333MB，直接进 SVN 会拖垮工作副本 ⇒ 一律 gzip（≈6.5×）后入库，
 * 运行时由 {@code com.nexusai.application.agent.ast.AstGrepBinary} 解压到 tmp 缓存再执行。
 *
 * 产物路径（固定，Java 侧 AstGrepBinary 按同一约定读取）：
 *   backend/src/main/resources/ast-grep/<平台>/ast-grep[.exe].gz
 *   平台 = {x64,arm64}-{win32,darwin,linux}（与 resources/rg/ 同矩阵）
 *
 * 用法（Node ≥ 20）：
 *   node scripts/fetch-ast-grep.mjs               # 只拉宿主平台
 *   node scripts/fetch-ast-grep.mjs --all         # 六平台全拉
 *   node scripts/fetch-ast-grep.mjs x64-darwin    # 指定平台（可多个）
 *
 * 失败即 exit 1 并打印手动下载说明：本脚本没有源码镜像之外的兜底，缺资源时
 * {@code AstGrepBinary.available()} 返回 false，两个工具自动 not-enabled（不崩）。
 *
 * ⚠️ 与 front/scripts/prepare-backend.mjs 的 checkAstGrepNative 互为镜像：那边只检查
 *    「jar 里有没有 ast-grep 资源」并打印平台清单，不会自动生成 ⇒ 缺资源时先跑本脚本。
 */
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RES_ROOT = resolve(__dirname, '../src/main/resources/ast-grep')

/** ast-grep CLI 版本 · 与 AstGrepBinary.VERSION 必须一致（tmp 缓存按版本隔离）。 */
const VERSION = '0.51.3'

/**
 * 平台矩阵：npm 平台包名 → 包内可执行文件名。
 * 包内布局固定为 package/<exe>（见 https://www.npmjs.com/package/@ast-grep/cli）。
 */
const PLATFORMS = {
  'x64-win32': { pkg: '@ast-grep/cli-win32-x64-msvc', exe: 'ast-grep.exe' },
  'arm64-win32': { pkg: '@ast-grep/cli-win32-arm64-msvc', exe: 'ast-grep.exe' },
  'x64-darwin': { pkg: '@ast-grep/cli-darwin-x64', exe: 'ast-grep' },
  'arm64-darwin': { pkg: '@ast-grep/cli-darwin-arm64', exe: 'ast-grep' },
  'x64-linux': { pkg: '@ast-grep/cli-linux-x64-gnu', exe: 'ast-grep' },
  'arm64-linux': { pkg: '@ast-grep/cli-linux-arm64-gnu', exe: 'ast-grep' },
}

/** 官方源；失败重试国内镜像（同一 tarball）。 */
const REGISTRIES = ['https://registry.npmjs.org', 'https://registry.npmmirror.com']

/** 二进制最小尺寸（防拉到 404 HTML / 半截文件）；实测 55,559,680 B。 */
const MIN_BINARY_BYTES = 20_000_000

/** 魔数校验：Windows PE / Linux ELF / macOS Mach-O（含 fat）。 */
function assertBinaryMagic(buf, platform) {
  const hex = buf.subarray(0, 4).toString('hex').toUpperCase()
  const ok =
    hex.startsWith('4D5A') || // MZ
    hex === '7F454C46' || // ELF
    hex === 'CFFAEDFE' || // Mach-O 64 LE
    hex === 'CAFEBABE' // Mach-O fat
  if (!ok) {
    throw new Error(`[${platform}] 文件魔数不像可执行文件（前 4 字节 ${hex}）`)
  }
}

/** 宿主平台键（与 Java PlatformKey / RipgrepRunner 同一套 os.name/os.arch 映射）。 */
function hostPlatform() {
  const os = process.platform === 'win32' ? 'win32' : process.platform === 'darwin' ? 'darwin' : 'linux'
  const arch = process.arch === 'arm64' ? 'arm64' : 'x64'
  return `${arch}-${os}`
}

function tarballUrl(registry, pkg, version) {
  const scoped = pkg.replace('/', '%2F') // @scope/name → @scope%2Fname
  const bare = pkg.slice(pkg.indexOf('/') + 1)
  return `${registry}/${scoped}/-/${bare}-${version}.tgz`
}

function download(url, dest) {
  // --fail: HTTP ≥400 直接非零退出（否则会把错误页写进 tgz）
  execFileSync('curl', ['-sS', '--fail', '-L', '-m', '300', '-o', dest, url], { stdio: 'inherit' })
}

function sha256(buf) {
  return createHash('sha256').update(buf).digest('hex')
}

function fetchOne(platform) {
  const meta = PLATFORMS[platform]
  if (!meta) {
    throw new Error(`未知平台 ${platform}（可选：${Object.keys(PLATFORMS).join(', ')}）`)
  }
  const work = mkdtempSync(join(tmpdir(), `ast-grep-${platform}-`))
  const tgz = join(work, 'pkg.tgz')
  try {
    let lastErr
    for (const registry of REGISTRIES) {
      const url = tarballUrl(registry, meta.pkg, VERSION)
      try {
        console.log(`[${platform}] 下载 ${url}`)
        download(url, tgz)
        lastErr = undefined
        break
      } catch (e) {
        lastErr = e
        console.warn(`[${platform}] 下载失败（${registry}）：${e.message}`)
      }
    }
    if (lastErr) throw lastErr

    execFileSync('tar', ['-xzf', tgz, '-C', work], { stdio: 'inherit' })
    const extracted = join(work, 'package', meta.exe)
    if (!existsSync(extracted)) {
      throw new Error(`[${platform}] tarball 内没有 package/${meta.exe}`)
    }
    const size = statSync(extracted).size
    if (size < MIN_BINARY_BYTES) {
      throw new Error(`[${platform}] 二进制过小（${size} B < ${MIN_BINARY_BYTES}），疑似下载不完整`)
    }
    const raw = readFileSync(extracted)
    assertBinaryMagic(raw, platform)

    const gz = gzipSync(raw, { level: 9 })
    const outDir = join(RES_ROOT, platform)
    mkdirSync(outDir, { recursive: true })
    const out = join(outDir, `${meta.exe}.gz`)
    writeFileSync(out, gz)
    console.log(
      `[${platform}] OK → ${out}\n` +
        `  原始 ${raw.length} B → gzip ${gz.length} B（${(raw.length / gz.length).toFixed(2)}×）\n` +
        `  sha256(原始)=${sha256(raw)}`
    )
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
}

const argv = process.argv.slice(2)
let targets
if (argv.includes('--all')) {
  targets = Object.keys(PLATFORMS)
} else if (argv.length > 0) {
  targets = argv.filter((a) => !a.startsWith('-'))
} else {
  targets = [hostPlatform()]
}

let failed = 0
for (const platform of targets) {
  try {
    fetchOne(platform)
  } catch (e) {
    failed++
    console.error(`[${platform}] 失败：${e.message}`)
  }
}

if (failed > 0) {
  console.error(
    '\n手动兜底：从 https://www.npmjs.com/package/@ast-grep/cli 下载对应平台包，' +
      `解压出的 ast-grep[.exe] 放到 src/main/resources/ast-grep/<平台>/ 下，` +
      `用 gzip 压缩后命名为 ast-grep[.exe].gz（版本必须 ${VERSION}）。`
  )
  process.exit(1)
}
console.log(`\n完成：${targets.length} 个平台 → ${RES_ROOT}`)
