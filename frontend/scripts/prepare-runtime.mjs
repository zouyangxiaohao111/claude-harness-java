#!/usr/bin/env node
/**
 * 准备随包运行时（Windows Git for Windows PortableGit），供 Tauri 安装包分发。
 *
 * 职责：
 *   取 PortableGit 自解压包 → 解压到 front/src-tauri/backend/git/ → 校验
 *   （bin/bash.exe + cmd/git.exe + 实际执行 git --version）→ 写 VERSION 标记。
 *
 * ⚠️ 为什么不用 MinGit（实测结论，2026-11-15）：
 *   MinGit 2.45.2 与 2.56.1 两版解压后都**只有 usr/bin/sh.exe（dash）**，
 *   没有 bin/bash.exe ⇒ BashTool 无法使用（`[[ ]]`、shopt、source 等 bash 语法全废）。
 *   必须用 PortableGit（含 GNU bash 5.x）。代价：压缩包约 60MB、解压约 300MB。
 *
 * ⚠️ 不内置 Node / Python：它们只被 MCP 的 npx/uvx 与用户脚本使用，
 *    光有解释器没有包管理/联网装包能力，内置收益为零（详见方案文档）。
 *
 * 用法：
 *   # 指定离线包（推荐，构建机可复现）
 *   set NEXUSAI_GIT_PACKAGE=D:\cache\PortableGit-2.56.1-64-bit.7z.exe
 *   node scripts/prepare-runtime.mjs
 *
 *   # 或放到默认缓存目录
 *   front/.runtime-cache/PortableGit-<版本>-64-bit.7z.exe
 *
 * 跳过（仅用于暂时没有离线包的本地构建；出正式包禁止）：
 *   set NEXUSAI_SKIP_BUNDLED_GIT=1
 *
 * 路径约定（与 prepare-backend.mjs 同款）：
 *   out = <front>/src-tauri/backend —— 对应 tauri.conf.json bundle.resources 的 "backend"
 */
import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const out = resolve(__dirname, '../src-tauri/backend')
const gitDir = join(out, 'git')
const cacheDir = resolve(__dirname, '../.runtime-cache')
const marker = join(gitDir, 'VERSION')

/** 目标平台：只随包 Windows（macOS / Linux 系统自带 bash/git）。 */
const isWin = process.platform === 'win32'

/**
 * 裁剪配方版本：清单变化时递增，强制已准备好的目录重新解压 + 裁剪。
 * 判定见 {@link alreadyPrepared}（marker 内容参与比对）。
 */
const PRUNE_RECIPE = 'pruned-v1'

/**
 * 整目录删除：与 BashTool / git 核心无关的编辑器、Perl、文档、GUI 资源。
 *
 * ⚠️ 每一项都必须是「删掉不影响 bash 启动、git init/commit/push、常用 POSIX 命令」的。
 * 时区数据（usr/share/zoneinfo、ucrt64/share/zoneinfo）**保留**（用户明确要求）。
 */
const PRUNE_DIRS = [
  'usr/share/vim',          // 44MB · vim 运行时（提交编辑器已设为 true，不会开 vim）
  'usr/share/perl5',        // 19MB · Perl 模块（只有 git svn / send-email 用）
  'usr/share/mintty',       //  3MB · 终端模拟器资源
  'usr/share/gtk-doc',      // 文档
  'usr/lib/perl5',          // 20MB · Perl 核心/厂商模块
  'usr/bin/core_perl',      // Perl 目录
  'usr/bin/vendor_perl',
  'ucrt64/share/doc',       // 24MB · 文档
  'ucrt64/share/licenses',
  'ucrt64/share/antiword',
]

/** usr/bin 下按名字删除：编辑器 / Perl。 */
const PRUNE_USR_BIN =
  /^(vim|vimdiff|view|rvim|rview|ex|vimtutor|nano|rnano|mintty)(\.exe)?$|^perl.*\.exe$|^msys-perl5_.*\.dll$|^msys-svn_swig_perl-.*\.dll$/i

/** ucrt64/bin 下与 BashTool / git 提交无关的单文件（pdftotext 保留：etc/gitconfig 的 astextplain 可能用）。 */
const PRUNE_UCRT64_BIN = ['scalar.exe']

function fail(msg) {
  console.error('')
  console.error(`[prepare-runtime] 构建中止：${msg}`)
  console.error('')
  process.exit(1)
}

function warn(msg) {
  console.warn(`[prepare-runtime] ⚠️ ${msg}`)
}

/** 解析离线包来源：显式 env 优先，其次 .runtime-cache 下的 PortableGit-*.7z.exe。 */
function resolvePackage() {
  const explicit = process.env.NEXUSAI_GIT_PACKAGE
  if (explicit) {
    const p = resolve(explicit)
    if (!existsSync(p)) {
      fail(`NEXUSAI_GIT_PACKAGE 指向的文件不存在：${p}`)
    }
    return p
  }
  if (existsSync(cacheDir)) {
    const candidates = readdirSync(cacheDir)
      .filter((n) => /^PortableGit-.*\.7z\.exe$/i.test(n))
      .sort()
    if (candidates.length > 0) {
      return join(cacheDir, candidates[candidates.length - 1])
    }
  }
  return null
}

/**
 * 目标目录是否已是**同一个包**的完整解压结果（bash.exe + git.exe + VERSION 匹配）。
 *
 * ⚠️ 必须比对包名：只判文件存在会让升级 PortableGit 版本时静默沿用旧目录。
 */
function alreadyPrepared(pkg) {
  if (!existsSync(join(gitDir, 'bin', 'bash.exe'))
    || !existsSync(join(gitDir, 'cmd', 'git.exe'))
    || !existsSync(marker)) {
    return false
  }
  return readFileSync(marker, 'utf8').trim() === markerOf(pkg)
}

/** VERSION 标记内容：包文件名 + 裁剪配方（同时用于诊断与幂等比对）。 */
function markerOf(pkg) {
  return `PortableGit from ${pkg.split(/[\\/]/).pop()} (${PRUNE_RECIPE})`
}

/** 递归统计目录/文件字节数（仅用于打印裁剪前后体积）。 */
function sizeOf(p) {
  try {
    const st = statSync(p)
    if (!st.isDirectory()) {
      return st.size
    }
    return readdirSync(p).reduce((sum, name) => sum + sizeOf(join(p, name)), 0)
  } catch {
    return 0
  }
}

/**
 * 裁剪 PortableGit：只保留 bash / git / POSIX 工具与依赖。
 *
 * ⛔ 不能凭目录名乱删：bash 依赖 ucrt64/bin 的 DLL，git 依赖 ucrt64/libexec/git-core。
 * 所以这里只删「明确与 BashTool 无关」的整块目录与少量单文件，删完由
 * {@link verifyRuntime} 实跑功能清单兜底 —— 任何一项缺失或失败都会中止构建。
 */
function prune() {
  const before = sizeOf(gitDir)
  const remove = (p) => {
    if (!existsSync(p)) {
      return
    }
    try {
      rmSync(p, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 })
    } catch (e) {
      warn(`裁剪失败（保留原样）：${p} — ${e.message}`)
    }
  }

  for (const rel of PRUNE_DIRS) {
    remove(join(gitDir, rel))
  }
  const usrBin = join(gitDir, 'usr', 'bin')
  if (existsSync(usrBin)) {
    for (const name of readdirSync(usrBin)) {
      if (PRUNE_USR_BIN.test(name)) {
        remove(join(usrBin, name))
      }
    }
  }
  for (const name of PRUNE_UCRT64_BIN) {
    remove(join(gitDir, 'ucrt64', 'bin', name))
  }

  const after = sizeOf(gitDir)
  console.log(
    `[prepare-runtime] 裁剪：${(before / 1024 / 1024).toFixed(0)}MB → ${(after / 1024 / 1024).toFixed(0)}MB（配方 ${PRUNE_RECIPE}）`
  )
}

/**
 * 裁剪后的功能自检（实跑，不看文件清单）。
 *
 * 覆盖：bash 启动、git init/add/commit/log、BashTool/技能常用的 POSIX 命令、
 * ssh、file（magic 数据库保留）。任何一项失败都中止构建 —— 宁可不出包，
 * 也不能出一个「bash 起来但脚本全炸」的安装包。
 */
function verifyRuntime() {
  const bash = join(gitDir, 'bin', 'bash.exe')
  const env = {
    ...process.env,
    PATH: [
      join(gitDir, 'cmd'),
      join(gitDir, 'bin'),
      join(gitDir, 'usr', 'bin'),
      process.env.PATH || '',
    ].join(';'),
  }
  const script = [
    'set -e',
    'bash --version | head -1',
    'git --version',
    'for c in grep sed awk find ls cat head tail sort uniq cut tr wc xargs env date dirname basename rm mv cp mkdir chmod ln readlink realpath sleep ssh file; do',
    '  command -v "$c" >/dev/null || { echo "MISSING $c"; exit 1; }',
    'done',
    'ssh -V 2>&1 | head -1',
    'tmp=$(mktemp -d)',
    'cd "$tmp"',
    'git init -q',
    'echo ok > a.txt',
    'git add .',
    'git -c user.name=nexusai -c user.email=ci@nexusai.invalid commit -qm prune-check',
    'git log --oneline | head -1',
    'rm -rf "$tmp"',
  ].join('\n')

  const r = spawnSync(bash, ['-c', script], { encoding: 'utf8', env })
  if (r.status !== 0) {
    fail(
      `裁剪后功能自检失败（退出码=${r.status}）\nstdout:\n${r.stdout || ''}\nstderr:\n${r.stderr || ''}`
    )
  }
  console.log('[prepare-runtime] 裁剪后功能自检通过（bash / git init-commit / POSIX 工具 / ssh）')
}

/**
 * 解压离线包。
 *
 * - `.7z.exe` 是 7z 自解压：直接执行，`-o<目录> -y` 静默解压（PortableGit 官方格式）。
 * - `.zip` 走 PowerShell Expand-Archive（Git Bash 的 GNU tar 不支持 zip，已验证会报错）。
 */
function extractPackage(pkg, dest) {
  mkdirSync(dest, { recursive: true })
  if (/\.7z\.exe$/i.test(pkg)) {
    const r = spawnSync(pkg, [`-o${dest}`, '-y'], { stdio: 'inherit' })
    if (r.status !== 0) {
      fail(`PortableGit 自解压失败：${pkg}（退出码=${r.status}）`)
    }
    return
  }
  // 单引号包裹；发布包路径不含单引号，不做额外转义
  const ps = `Expand-Archive -LiteralPath '${pkg}' -DestinationPath '${dest}' -Force`
  const viaPs = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', ps], {
    stdio: 'inherit',
  })
  if (viaPs.status !== 0) {
    fail(`解压失败：${pkg} → ${dest}`)
  }
}

/** PortableGit 是扁平结构；若解压出了一层唯一子目录则下钻（防御其他打包形态）。 */
function normalizeLayout(dest) {
  if (existsSync(join(dest, 'bin', 'bash.exe'))) {
    return
  }
  const entries = readdirSync(dest, { withFileTypes: true }).filter((e) => e.isDirectory())
  if (entries.length === 1) {
    const inner = join(dest, entries[0].name)
    if (existsSync(join(inner, 'bin', 'bash.exe'))) {
      for (const name of readdirSync(inner)) {
        cpSync(join(inner, name), join(dest, name), { recursive: true })
      }
      rmSync(inner, { recursive: true, force: true })
    }
  }
}

/** 构建期门禁：可执行文件齐全且真的能跑。 */
function verify() {
  const bash = join(gitDir, 'bin', 'bash.exe')
  const git = join(gitDir, 'cmd', 'git.exe')
  if (!existsSync(bash)) {
    fail(`解压结果缺少 bin/bash.exe：${bash}`)
  }
  if (!existsSync(git)) {
    fail(`解压结果缺少 cmd/git.exe：${git}`)
  }
  const probe = spawnSync(git, ['--version'], { encoding: 'utf8' })
  if (probe.status !== 0) {
    fail(`随包 git 无法执行：${git} --version 退出码=${probe.status} stderr=${(probe.stderr || '').trim()}`)
  }
  console.log(`[prepare-runtime] 随包 Git 校验通过：${(probe.stdout || '').trim()}`)
}

function main() {
  if (!isWin) {
    // macOS / Linux 系统自带 bash 与 git，不随包；但要清掉可能的跨平台残留
    // （同一工作区先在 Windows 构建过，再在 mac 构建时会留下 backend/git）。
    if (existsSync(gitDir)) {
      rmSync(gitDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 })
      console.log('[prepare-runtime] 非 Windows：已清理残留 backend/git（macOS/Linux 使用系统 Git）')
    }
    console.log('[prepare-runtime] 非 Windows：跳过随包 Git（系统自带 bash/git）')
    return
  }

  const pkg = resolvePackage()
  if (pkg && alreadyPrepared(pkg)) {
    console.log(`[prepare-runtime] 已就绪：${readFileSync(marker, 'utf8').trim()}`)
    return
  }
  if (!pkg) {
    if (process.env.NEXUSAI_SKIP_BUNDLED_GIT === '1') {
      warn('未找到 PortableGit（已设置 NEXUSAI_SKIP_BUNDLED_GIT=1）⇒ 本次出包不含随包 Git')
      warn('新装机器将无法使用 Bash 工具；仅限本地临时构建，正式发布禁止跳过')
      return
    }
    fail(`未找到 PortableGit 离线包。
  任选一种：
    1) set NEXUSAI_GIT_PACKAGE=<绝对路径>\\PortableGit-<版本>-64-bit.7z.exe
    2) 把包放到 ${cacheDir}\\PortableGit-<版本>-64-bit.7z.exe
  获取：Git for Windows 官方 releases 的 "PortableGit-<版本>-64-bit.7z.exe"
  ⛔ 不要用 MinGit：实测其不含 bash.exe（只有 usr/bin/sh.exe），BashTool 无法使用。
  临时跳过（正式发布禁止）：set NEXUSAI_SKIP_BUNDLED_GIT=1`)
  }

  console.log(`[prepare-runtime] PortableGit: ${pkg}（${(statSync(pkg).size / 1024 / 1024).toFixed(1)} MB）`)
  if (existsSync(gitDir)) {
    // Windows 上大量小文件 + 杀软扫描会偶发 EPERM ⇒ 带重试
    rmSync(gitDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 300 })
  }
  extractPackage(pkg, gitDir)
  normalizeLayout(gitDir)
  verify()
  prune()
  // 裁剪后再跑一次完整功能自检：删错任何依赖都会在这里中止构建
  verifyRuntime()
  writeFileSync(marker, `${markerOf(pkg)}\n`, 'utf8')
  console.log(`[prepare-runtime] 完成 → ${gitDir}`)
}

main()
