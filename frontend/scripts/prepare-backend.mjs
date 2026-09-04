#!/usr/bin/env node
/**
 * 打包后端产物并裁剪 JRE，供 Tauri 桌面端随应用分发。
 *
 * 职责链路：
 *   mvn package（backend）→ 用 JDK25 jdeps 求 jar 模块 + 拼 Spring 补丁模块集
 *   → jlink 裁 JRE → jar + jre 拷入 front/src-tauri/backend/
 *
 * 用法：
 *   JAVA_HOME=/path/to/jdk25 node scripts/prepare-backend.mjs
 *
 * 路径约定（本脚本位于 front/scripts/，front/package.json 已 "type":"module"）：
 *   backend = <仓库根>/backend          —— resolve(__dirname, '../..', 'backend')
 *   out     = <front>/src-tauri/backend —— resolve(__dirname, '../src-tauri/backend')
 *     （out 对应 tauri.conf.json bundle.resources 的 "backend" 条目）
 */
import { execSync } from 'node:child_process'
import { cpSync, existsSync, readdirSync, rmSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// ESM 下定位本文件目录（不能用 CommonJS 的 __dirname）
const __dirname = dirname(fileURLToPath(import.meta.url))

const backend = resolve(__dirname, '../..', 'backend')
const out = resolve(__dirname, '../src-tauri/backend')
const JAR_REL = join('target', 'nexusai-backend.jar') // backend-relative，勿用 resolve（会锚定 cwd）

/** Spring Boot 运行必需、但 jdeps 可能漏报/裁剪过度的补丁模块集（与基线并集去重排序） */
const PATCH_MODULES = [
  'java.naming',
  'java.management',
  'java.instrument',
  'java.security.jgss',
  'jdk.unsupported',
  'java.sql',
  'java.xml',
  'java.net.http',
  'java.rmi',
  'jdk.crypto.ec',
  'jdk.zipfs',
  'jdk.localedata',
]

/** 取 JDK bin 下工具全路径（Windows 带 .exe，Unix 无后缀） */
function jdkBinTool(javaHome, name) {
  const exe = process.platform === 'win32' ? `${name}.exe` : name
  return resolve(javaHome, 'bin', exe)
}

/** 递归统计目录字节大小 */
function dirSize(dir) {
  let total = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    total += entry.isDirectory() ? dirSize(p) : statSync(p).size
  }
  return total
}

/** 输出 out 目录产物与 jre/bin 体积 */
function printSummary(jreDir, jarPath) {
  const jarSizeMB = (statSync(jarPath).size / 1024 / 1024).toFixed(1)
  const binSizeMB = (dirSize(join(jreDir, 'bin')) / 1024 / 1024).toFixed(1)
  console.log(`[prepare-backend] 完成 → out: ${out}`)
  console.log(`[prepare-backend] nexusai-backend.jar ${jarSizeMB} MB；jre/bin 约 ${binSizeMB} MB（jre 整体见上）`)
}

function main() {
  // 1) 打包后端 jar
  console.log('[prepare-backend] [1/4] mvn package（backend，跳过测试）…')
  execSync('mvn -DskipTests package', { cwd: backend, stdio: 'inherit' })

  // 2) JDK 定位与校验（需完整 JDK 25：jlink/jdeps/java 齐全）
  const javaHome = process.env.JAVA_HOME
  if (!javaHome) {
    console.error('[prepare-backend] 未检测到 JAVA_HOME 环境变量。')
    console.error('  请设置 JAVA_HOME 指向 JDK 25，例如：')
    console.error('    Windows: set JAVA_HOME=C:\\Program Files\\Java\\jdk-25.0.3')
    console.error('    macOS/Linux: export JAVA_HOME=/path/to/jdk-25')
    process.exit(1)
  }
  const tools = {
    jlink: jdkBinTool(javaHome, 'jlink'),
    java: jdkBinTool(javaHome, 'java'),
    jdeps: jdkBinTool(javaHome, 'jdeps'),
  }
  for (const [label, p] of Object.entries(tools)) {
    if (!existsSync(p)) {
      console.error(`[prepare-backend] 缺少 JDK 工具 ${label}: ${p}`)
      console.error('  请设置 JAVA_HOME 指向完整 JDK 25（含 jlink/jdeps），而非仅 JRE。')
      process.exit(1)
    }
  }
  console.log(`[prepare-backend] [2/4] 使用 JAVA_HOME: ${javaHome}`)

  // 3) jdeps 求基线模块，与补丁集并集去重排序 → --add-modules 串
  const jarPath = resolve(backend, JAR_REL)
  if (!existsSync(jarPath)) {
    console.error(`[prepare-backend] 未找到打包产物: ${jarPath}`)
    console.error('  请确认 mvn package 成功且 finalName=nexusai-backend。')
    process.exit(1)
  }
  console.log(`[prepare-backend] [3/4] jdeps 分析模块依赖: ${jarPath}`)
  const raw = execSync(`"${tools.jdeps}" --print-module-deps --ignore-missing-deps "${jarPath}"`, {
    encoding: 'utf8',
  }).trim()
  const baseModules = raw ? raw.split(',').map((s) => s.trim()).filter(Boolean) : []
  const finalModules = [...new Set([...baseModules, ...PATCH_MODULES])].sort()
  const addModules = finalModules.join(',')
  console.log(`[prepare-backend] jdeps 基线模块 ${baseModules.length} 个；补丁 ${PATCH_MODULES.length} 个；`)
  console.log(`[prepare-backend] 最终 --add-modules 共 ${finalModules.length} 个，关键含:`)
  console.log(`    ${PATCH_MODULES.join(', ')}`)
  console.log(`    （全量模块串已写入 jlink 参数，共 ${addModules.length} 字符）`)

  // 4) jlink 裁 JRE → out/jre；jar 拷入 out
  const jreDir = resolve(out, 'jre')
  // 清旧产物，防 jlink 「目录已存在」；上次打包留下的 jre 被 .gitignore 忽略但仍在磁盘，
  // 若不删则重复 tauri build 时 jlink 必然失败
  rmSync(jreDir, { recursive: true, force: true })
  console.log(`[prepare-backend] [4/4] jlink 裁剪 JRE → ${jreDir}`)
  execSync(
    `"${tools.jlink}" --add-modules ${addModules} --output "${jreDir}" --strip-debug --no-man-pages --no-header-files --compress=zip-6`,
    { stdio: 'inherit' }
  )
  cpSync(jarPath, resolve(out, 'nexusai-backend.jar'))
  printSummary(jreDir, jarPath)
}

// 直接执行才运行主流程；被 import 时仅导出/自检（不触发打包，避免误烧机）
// Windows 路径大小写不敏感 → 比较前统一小写，避免 d:/ 与 D:/ 差异导致误判
const invokedDirectly = (() => {
  if (!process.argv[1]) return false
  const scriptPath = resolve(process.argv[1])
  const selfPath = fileURLToPath(import.meta.url)
  return process.platform === 'win32'
    ? scriptPath.toLowerCase() === selfPath.toLowerCase()
    : scriptPath === selfPath
})()
if (invokedDirectly) {
  main()
}
